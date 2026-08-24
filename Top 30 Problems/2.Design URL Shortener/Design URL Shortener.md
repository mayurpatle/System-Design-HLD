# Mock Interview Transcript — "Design a URL Shortener"

**Format:** 45 minutes, calibrated at Google L5 depth
**Structure:** the six-phase HLD template
**Annotations:** `▶ WHY THIS WORKS` blocks explain the move. Not part of the interview.

> **Note on this problem:** this is the most-asked "easy" system design question in the industry, and its difficulty is inverted. The core design takes ten minutes and you have forty-five. Everyone can produce a working answer, so nobody differentiates on the happy path. The signal is entirely in what you do with the remaining thirty-five minutes — and the candidates who fail do so by running out of things to say, not by getting it wrong.

---

## Phase 1 — Scope & Clarify (0:00 – 0:05)

**INTERVIEWER:** Design a URL shortener. TinyURL, bit.ly, that kind of thing.

**CANDIDATE:** The core mechanism here is simple enough that I want to spend my scoping questions on the things that actually create design decisions, because most of the interesting engineering is in the periphery.

Five questions.

**Custom aliases** — can a user request a specific short code, like `/black-friday`, or are all codes system-generated?

**INTERVIEWER:** Yes, custom aliases are supported.

**CANDIDATE:** That's the first real constraint. Custom aliases mean I need a **uniqueness check with coordination** on the write path, which pure generation doesn't require. It also creates a namespace collision problem between generated and custom codes that I'll need to handle explicitly.

**Idempotency** — if the same long URL is submitted twice, should it return the same short code, or a new one?

**INTERVIEWER:** What's your view?

**CANDIDATE:** A new one, and I'd push back on the deduplication instinct.

It's tempting to dedupe — it saves storage and feels tidy. But two users shortening the same URL usually want *separate* links, because the link is the unit of analytics. If I shorten a product page for my email campaign and you shorten it for your Twitter post, we each need our own click attribution. Collapsing them destroys that.

There's also a deletion problem: if the link is shared and you revoke yours, mine breaks.

So: no deduplication by default. It costs a little storage and it's the correct product behavior.

**INTERVIEWER:** Agreed.

**CANDIDATE:** **Analytics** — do we track clicks, and at what granularity?

**INTERVIEWER:** Yes. Click counts, plus referrer, geography, and device at a basic level.

**CANDIDATE:** Good — that's a meaningful addition. It means the redirect path can't be a pure cache lookup with nothing else happening, and it constrains a decision I'll come to about which HTTP status code to return.

**Expiration** — do links expire?

**INTERVIEWER:** Optional per-link expiry. Default is never.

**CANDIDATE:** Last one — **who creates links?** Anonymous, or authenticated accounts?

**INTERVIEWER:** Both. Anonymous with rate limits, authenticated for analytics and management.

**CANDIDATE:** That matters for abuse, which I suspect is the genuinely hard part of this problem. URL shorteners are phishing infrastructure by construction — they hide the destination — and anonymous creation makes that worse.

So to confirm: custom aliases supported, no deduplication, click analytics with basic dimensions, optional expiry, mixed anonymous and authenticated creation. Out of scope: the account system itself, billing, and the browser extension.

And one framing. This is an unusual shape for a system: the read and write paths have almost nothing in common. Writes are low-volume, coordination-heavy, and can afford to be slow. Reads are enormous-volume, must be single-digit milliseconds, and do essentially one thing. I'm going to design them as two nearly independent systems.

**INTERVIEWER:** Go ahead.

> **▶ WHY THIS WORKS**
> The candidate names upfront that the core is simple and directs scoping toward the periphery — which is exactly the right instinct for this problem, and signals they know where the difficulty actually lives.
>
> The deduplication answer is the standout. It's a question most candidates answer "yes, dedupe to save space" without thinking. The candidate reasons from the *product* — the link is the unit of analytics, and collapsing two users' links destroys attribution — and then adds a second reason about revocation. Two independent arguments against the tempting answer.
>
> Flagging abuse as "probably the genuinely hard part" in scoping is a hook, and it's the right one. Nobody expects the phishing problem to be the interesting part of a URL shortener.

---

## Phase 2 — Estimation (0:05 – 0:10)

**CANDIDATE:** The numbers here are modest, and one of them reframes the whole system.

*[writes]*

```
WRITE PATH
  New links per day              100M
  100M / 86,400                  ≈ 1,200 writes/sec
  Peak (3×)                      ≈ 3,500 writes/sec

READ PATH
  Redirects per day              10B          (100:1 ratio)
  10B / 86,400                   ≈ 115K reads/sec
  Peak (3×)                      ≈ 350K reads/sec

STORAGE
  Per record ~500 B
    (code, long URL up to 2KB but ~200 B typical,
     userId, timestamps, flags)
  100M/day × 500 B               ≈ 50 GB/day
  5 years                        ≈ 90 TB
  → not large. A modest sharded cluster.

KEYSPACE
  Base62 (a-z A-Z 0-9)
    6 chars → 62^6  ≈ 56 billion
    7 chars → 62^7  ≈ 3.5 trillion
  5-year need: 100M × 365 × 5 ≈ 180 billion
  → 6 chars is NOT enough.  7 chars it is.

THE HOT SET  ← the number that matters
  Link popularity is extremely skewed.
  Assume ~1% of links drive ~95% of redirects.
  Total links after 5 yrs        180B
  ...but links go cold fast — most traffic is on
  links created in the last 30 days:
     30 days × 100M              = 3B links
     1% of those are hot         = 30M links
     30M × 300 B (code + URL)    ≈ 9 GB
```

**CANDIDATE:** That last block is the reframe.

**The entire hot working set is about nine gigabytes.** That fits in memory on a single machine, comfortably, with room to spare — and in a small cache cluster it fits many times over with replication.

Which means the read path, at 350,000 requests per second, should essentially **never touch the database.** A redirect is a hash lookup in RAM and an HTTP 302. The database exists to serve cache misses and to be the durable record, not to serve traffic.

That changes what I'm designing. This isn't a database scaling problem — it's a caching problem with a database attached. If I find myself designing elaborate database sharding for the read path, I've misread the workload.

The second thing worth naming: **7 characters, not 6.** Six characters gives 56 billion codes and I need 180 billion over five years. That's not close — it's short by a factor of three. Getting this wrong is the kind of mistake that's cheap to avoid now and extremely expensive to fix later, because changing code length after launch means every existing link either breaks or you run two formats forever.

**INTERVIEWER:** You assumed 1% of links drive 95% of traffic. Where's that from?

**CANDIDATE:** It's an assumption based on the general shape of link-sharing traffic, and I should be explicit that it's an assumption rather than a measurement.

The reasoning: link popularity follows a heavily skewed distribution. A link in a viral tweet or a large marketing email gets millions of clicks; the median link — someone sharing an article with a friend — gets a handful. That's the same power-law shape you see in content popularity generally.

There's also strong **time decay**, which matters as much as the skew. Links get most of their traffic in the first days after creation and then go essentially dormant. A link from three years ago is almost never clicked.

If I'm wrong, I'd expect to be wrong in the *conservative* direction — the real distribution is probably more skewed, not less, which makes the hot set smaller and the cache more effective. But it's the number I'd measure first in production, because the entire read-path design rests on it.

> **▶ WHY THIS WORKS**
> The hot-set calculation is the equivalent of the "200 MB" moment in the Uber transcript — a number that reframes what kind of problem this is. Concluding "this is a caching problem with a database attached, not a database scaling problem" tells the interviewer the candidate has read the workload correctly.
>
> The 6-vs-7 character point is small but consequential, and it's framed as a decision that's cheap now and expensive later. That kind of forward-looking framing is a maturity signal.
>
> When challenged on the assumption, the candidate defended it with a mechanism (power-law plus time decay), named the direction they'd likely be wrong in, and said it's the first thing they'd measure. That's the same pattern as the Netflix peak-multiplier defense.

---

## Phase 3 — API & Data Model (0:10 – 0:15)

**CANDIDATE:** Four endpoints, and one of them carries a decision worth arguing about.

*[writes]*

```
CREATE
  POST /api/v1/urls
    Idempotency-Key: <uuid>          ← for client retry safety
    { longUrl, customAlias?, expiresAt? }
  → 201 { shortUrl, code, createdAt }
  → 409 if customAlias is taken

REDIRECT                              ← 99% of all traffic
  GET /{code}
  → 302 Found
    Location: <longUrl>
    Cache-Control: private, max-age=0

ANALYTICS
  GET /api/v1/urls/{code}/stats?from=&to=
  → { totalClicks, byDay[], byReferrer[], byCountry[] }

MANAGE
  DELETE /api/v1/urls/{code}
  PATCH  /api/v1/urls/{code}   { expiresAt }
```

**CANDIDATE:** The decision worth discussing is the **302 rather than 301**, because it's a genuine tradeoff and the tempting answer is wrong.

```
  301 MOVED PERMANENTLY
    Browser caches the mapping, often indefinitely.
    Subsequent clicks NEVER reach our servers.
    + Enormous load reduction
    + Fastest possible user experience
    − ZERO analytics after the first click
    − Cannot change the destination
    − Cannot revoke a malicious link — the browser
      will keep redirecting to it forever

  302 FOUND
    Browser asks us every time.
    + Full click analytics
    + Destination can be changed
    + A malicious link can be killed instantly
    − Every click costs a request
```

**CANDIDATE:** The instinct is 301 — it's the semantically "correct" code for a permanent redirect, and it saves an enormous amount of traffic.

I'd choose 302, for two reasons that both outweigh the load saving.

**Analytics is the product.** For a commercial shortener, click tracking is why people pay. A 301 makes the first click visible and every subsequent one invisible, which means your analytics undercount by an unknown and unbounded factor.

**Revocation must work.** This is the stronger reason. Shorteners are used for phishing. When you discover a link is malicious, you need it dead immediately. With a 301, you cannot do that — every browser that has ever seen that link has cached the redirect, and it will keep sending users to the malicious destination regardless of what your servers say. There is no mechanism to recall it.

That's an irreversible mistake made at design time. And the load saving 301 buys is not needed, because the hot set fits in memory — I'm serving these from RAM anyway.

I'd also set `Cache-Control: private, max-age=0` explicitly on the redirect, because intermediary caches would otherwise reintroduce the same problem.

**CANDIDATE:** Data model is small:

```
URL_MAPPING
  code          VARCHAR(7)  PRIMARY KEY      ← partition key
  longUrl       TEXT
  userId        UUID NULL                    (null = anonymous)
  createdAt     TIMESTAMP
  expiresAt     TIMESTAMP NULL
  isCustom      BOOLEAN
  status        ACTIVE | BLOCKED | EXPIRED   ← for takedowns

CLICK_EVENT        (separate store, append-only)
  code, timestamp, referrer, countryCode,
  deviceType, ipHash
```

**CANDIDATE:** Partition key is `code`, hashed. Every lookup on the read path is by exact code, so this is a perfect fit — a single-partition point read with no scatter, and writes distribute uniformly because the codes are effectively random.

There's no access pattern that wants range scans over codes, so nothing is lost by hashing.

Two things I want to flag in the model.

**`status`, not deletion.** A blocked or deleted link keeps its row with a status flag rather than being removed. That's what lets me serve a "this link has been disabled" page rather than a 404, and it's what prevents the code from ever being reissued.

**Click events are a separate store.** They're append-only, enormous relative to the mappings, and never queried by the redirect path. Mixing them into the same store would put a 10-billion-row-per-day write stream next to my 350K QPS read path, which is the wrong neighbor.

**INTERVIEWER:** Why not reuse codes after a link expires? You'd save keyspace.

**CANDIDATE:** I'd never do it, and the keyspace saving isn't real anyway — 3.5 trillion codes against 180 billion needed means I'm using about five percent of the space over five years. There's no pressure.

But even if there were, reuse is dangerous in a way that's easy to underestimate. **Short links persist in the physical world.** They're printed on posters, embedded in QR codes on packaging, written into documentation, saved in bookmarks, and pasted into years-old forum posts.

If I expire a link and reissue its code, every one of those artifacts now points somewhere new. A conference badge from two years ago suddenly directs to a different site. And the failure is worse than a broken link, because the user has no indication anything changed — they get a working page that isn't the one the artifact promised.

There's a security version too: an attacker could watch for high-value codes to expire and claim them, inheriting all their residual traffic and trust.

So codes are permanently retired. It costs nothing and it removes an entire category of failure.

> **▶ WHY THIS WORKS**
> The 301-vs-302 discussion is the differentiator most candidates skip. The revocation argument is the strong one — it turns an apparent performance optimization into an irreversible safety hole — and adding the explicit `Cache-Control` header shows the candidate thought about intermediary caches too.
>
> The code-reuse answer is the standout. The candidate first dismisses the premise (no keyspace pressure exists), then argues it would still be wrong, then gives a concrete physical-world example (QR code on a conference badge), then adds a security angle. Four layers on a question that could have been answered in one sentence — and none of it is padding.

---

## Phase 4 — High-Level Design (0:15 – 0:27)

**CANDIDATE:** As I said in scoping, this is really two systems. Let me draw them separately.

*[draws]*

```
┌────────────────────────────────────────────────────────────────────┐
│  READ PATH   —  350K QPS, must be ~1ms, does almost nothing        │
└────────────────────────────────────────────────────────────────────┘

   User clicks bit.ly/aB3xK9p
        │
        ▼
   ┌──────────────┐
   │  Anycast /   │   geo-routed to nearest PoP
   │  Edge PoP    │
   └──────┬───────┘
          │
          ▼
   ┌──────────────────┐    HIT (~99%)
   │  Redis cluster   │──────────────┐
   │  code → longUrl  │              │
   │  ~9 GB hot set   │              │
   └──────┬───────────┘              │
          │ MISS (~1%)               │
          ▼                          │
   ┌──────────────────┐              │
   │  Redirect Svc    │              │
   │  (stateless)     │              │
   └──────┬───────────┘              │
          │                          │
          ▼                          ▼
   ┌──────────────────┐      ┌───────────────────┐
   │  URL Store       │      │  302 + Location   │
   │  sharded by      │      │  → user's browser │
   │  hash(code)      │      └─────────┬─────────┘
   └──────────────────┘                │
                                       │ fire-and-forget
                                       ▼
                              ┌──────────────────┐
                              │  Kafka           │
                              │  click events    │
                              └────────┬─────────┘
                                       ▼
                              ┌──────────────────┐
                              │ Stream aggregator│
                              │ → analytics store│
                              └──────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│  WRITE PATH  —  3.5K QPS, can be slower, does real work            │
└────────────────────────────────────────────────────────────────────┘

   POST /api/v1/urls
        │
        ▼
   ┌──────────────────┐
   │  API Gateway     │  auth · rate limit · idempotency key
   └──────┬───────────┘
          │
          ▼
   ┌──────────────────┐         ┌─────────────────────┐
   │  Create Service  │────────▶│  ID Generator       │
   │                  │         │  (ranged allocation)│
   │  · validate URL  │         └─────────────────────┘
   │  · get code      │
   │  · sync blocklist check
   │  · write         │
   │  · warm cache    │
   └──────┬───────────┘
          │
          ├──▶ [URL Store]
          │
          └──▶ [Kafka] ──▶ ┌────────────────────────┐
                           │  Abuse Scanner (async) │
                           │  deep scan · re-scan   │
                           └────────────────────────┘
```

**CANDIDATE:** Trace a redirect.

Request hits the nearest edge PoP by anycast — that matters because a redirect is one round trip and geographic latency dominates it. From Mumbai, reaching a US-East origin costs 200 milliseconds regardless of how fast the lookup is.

The edge queries the cache. Hit rate should be around ninety-nine percent given the hot-set math. On a hit, we return a 302 immediately. Total server time: sub-millisecond.

On the roughly one percent miss, the redirect service reads from the URL store — a single-partition point lookup by code — populates the cache, and returns.

Then the click event is published to Kafka **after the response is sent.** That ordering matters: the analytics write must never sit on the user's latency path, and it must never be able to fail the redirect. If Kafka is down, users still get redirected and we lose some analytics. That's the correct priority.

Trace a create.

Gateway authenticates and rate limits. The idempotency key protects against a client retrying after a timeout and getting two different short codes for one intent.

Create service validates the URL is well-formed and resolvable, gets a code from the ID generator, does a **synchronous** check against a known-malicious blocklist, writes to the store, and warms the cache.

Then it publishes to Kafka for the asynchronous abuse scanner, which does the expensive work — fetching the page, checking reputation services, looking for redirect chains.

**INTERVIEWER:** Why is one abuse check synchronous and the other async?

**CANDIDATE:** Because they have completely different cost and confidence profiles.

The synchronous check is a lookup against a locally-cached set of known-bad domains — a Bloom filter or an in-memory set. It's sub-millisecond and it's high-confidence: if the domain is on a well-maintained blocklist, reject immediately with a clear error. There's no reason to let that link exist even briefly.

The asynchronous check is expensive: actually fetching the destination, following redirect chains, submitting to reputation APIs that take hundreds of milliseconds. Putting that on the create path would make link creation take a second or more, which is a bad experience for the overwhelming majority of users creating legitimate links.

The tradeoff is a window where a malicious link is live before the async scan catches it. I'd bound that by scanning quickly — seconds, not minutes — and by rate-limiting anonymous creation so an attacker can't generate thousands of links inside the window.

And there's a point I want to make here that I think is the actual hard part of abuse for a shortener: **a link that is clean at creation can become malicious later.** An attacker registers a link pointing at a benign page, waits for it to be distributed and trusted, then changes what's served at that URL. Nothing in my creation-time scanning catches that, because at creation time the link was genuinely fine.

So scanning cannot be a one-time gate at creation. It has to be **periodic re-scanning**, prioritized by traffic — the links getting the most clicks are the ones worth re-checking most often, because those are both the highest-value targets and the highest-impact failures.

**INTERVIEWER:** Why put the cache at the edge rather than just in front of the database?

**CANDIDATE:** Because for this workload, network latency dominates everything else.

A redirect does one thing. The server-side work — a hash lookup — is microseconds. The user-perceived latency is almost entirely the round trip to wherever the server is.

If all my capacity is in one region, a user in Singapore pays 200 milliseconds of speed-of-light before any work happens. Pushing the cache to edge locations turns that into 10 or 20 milliseconds.

And the workload is unusually well-suited to edge caching: the data is tiny, immutable in practice, and has an extremely skewed access distribution. That's close to the ideal case — you get very high hit rates from a small footprint.

The complication is **invalidation**, which is the reason I need to be careful here. When a link is blocked for abuse, that change must propagate to every edge location fast, or blocked links keep serving from stale edge caches. So I'd keep edge TTLs short — minutes, not hours — and push explicit invalidations on block or delete rather than waiting for expiry.

That's the same tension as the 301 decision, one layer down: caching improves performance and impairs revocation, and revocation is a safety property I'm not willing to weaken.

> **▶ WHY THIS WORKS**
> Drawing the read and write paths as separate systems delivers on the framing from Phase 1, and it makes the asymmetry visible rather than asserted.
>
> "Click event published after the response is sent" with the explicit priority statement — users still get redirected if Kafka is down — is a small correctness detail that shows the candidate thought about the failure ordering.
>
> The abuse answer is where this transcript earns its depth. The sync/async split is well-reasoned, and then the candidate volunteers the insight that creation-time scanning is structurally insufficient because links can turn malicious after the fact. That's a genuinely non-obvious observation that most candidates never reach.
>
> Connecting edge-cache invalidation back to the 301 decision — "the same tension, one layer down" — is the coherence move that ties a design together.

---

**CANDIDATE:** That's the skeleton. The two areas with real depth are **code generation** — which has four viable approaches with genuinely different tradeoffs, including a security problem that isn't obvious — and the **abuse pipeline**, which I've started on. Preference?

**INTERVIEWER:** Code generation. Walk me through the options.

---

## Phase 5 — Deep Dive: Code Generation (0:27 – 0:42)

**CANDIDATE:** Four approaches. Let me build up to the one I'd choose, because the reasoning is what matters.

**Approach one: hash the URL and truncate.**

```
  code = base62( md5(longUrl) )[0:7]

  + Stateless — no coordination at all
  + Deterministic — same URL always yields the same code
  − Collisions are certain. Truncating a 128-bit hash to
    42 bits (7 base62 chars) means birthday collisions
    start appearing around 2^21 ≈ 2 million URLs.
  − Collision handling requires a READ before every WRITE
  − Determinism conflicts with the no-dedup decision
    I made in scoping
```

**CANDIDATE:** The read-before-write is the killer. Every creation becomes a lookup plus a conditional insert, and under concurrency you need the insert to be atomic on the code, so you're doing a compare-and-set against the database on every write. At 3,500 writes per second that's survivable, but it's coordination I don't need.

And determinism actively fights the requirement — I decided in scoping that the same URL submitted twice should produce two different links, for analytics attribution. A deterministic hash makes that impossible without adding a salt, at which point it isn't a hash of the URL any more.

**Approach two: random generation with a uniqueness check.**

```
  code = random 7 base62 chars
  attempt INSERT; on conflict, retry

  + Non-deterministic — satisfies the no-dedup requirement
  + Codes are unguessable
  − Still a read-modify-write against the database
  − Collision probability rises as the space fills
```

**CANDIDATE:** Better than hashing, and still coordination-per-write. The collision rate is low early — with 3.5 trillion codes and 180 billion used, we're at five percent occupancy, so retries are rare — but the mechanism is still "guess and check against the database," which is unsatisfying when a deterministic alternative exists.

**Approach three: a global counter, base62-encoded.**

```
  n = INCR global_counter
  code = base62(n)

  + No collisions, ever, by construction
  + No read before write
  + Dense keyspace use — shortest possible codes
  − A global counter is a coordination point on every write
  − Sequential codes are ENUMERABLE  ← the real problem
```

**CANDIDATE:** This eliminates collisions entirely, which is a genuine advantage. But it introduces two problems, and the second one is more serious than it first appears.

**Approach four — what I'd actually build: ranged allocation with a permutation.**

Let me take the two problems in turn, because the solution is really two independent fixes.

**Fixing the coordination cost: range allocation.**

```
  Instead of INCR per write, each Create Service instance
  claims a BLOCK of the counter:

    Instance A claims [1,000,000 – 1,000,999]
    Instance B claims [1,001,000 – 1,001,999]

  Then it issues codes from its block locally, in memory,
  with zero coordination.

  Coordination happens ONCE PER 1,000 IDs, not once per ID.
  At 3,500 writes/sec that's ~3.5 coordination calls/sec
  instead of 3,500.
```

**CANDIDATE:** That reduces the coordination by three orders of magnitude and makes the counter a non-issue. The allocator can be a small strongly-consistent store — etcd, or a single row with an atomic increment.

The cost is **gaps**. If an instance dies holding a partially-used block, those IDs are lost forever. At a thousand per block and a keyspace of 3.5 trillion, losing even thousands of blocks is irrelevant — I'd rather waste IDs than reclaim them, because reclamation would reintroduce exactly the coordination I just removed.

I'd size the block by write rate: large enough that allocation is infrequent, small enough that a crash doesn't waste much. A thousand is a reasonable default; a high-throughput instance might take ten thousand.

**Fixing the enumerability: this is the important one.**

```
  Sequential counter, base62-encoded:

    n = 1,000,001  →  code = "4c92"
    n = 1,000,002  →  code = "4c93"
    n = 1,000,003  →  code = "4c94"

  The codes are ADJACENT. An attacker who creates one
  link learns roughly where the counter is, and can then
  enumerate every link around it.
```

**CANDIDATE:** This is a real and serious problem, and it's the reason I wouldn't ship a naive counter.

The consequence is that **your entire link database becomes crawlable.** Short links are frequently used for things that are unlisted rather than genuinely private — a Google Doc shared by link, an internal dashboard, an unreleased product page, a password reset. None of those are protected by authentication; they're protected by the URL being unguessable.

Sequential codes make them all guessable. An attacker walks the counter and harvests everything.

The fix is to keep the counter's uniqueness guarantee but destroy its ordering:

```
  Apply a bijective (reversible, one-to-one) transformation
  before encoding:

    n  →  permute(n)  →  base62  →  code

  A FORMAT-PRESERVING ENCRYPTION scheme — a small Feistel
  network over the keyspace works well — gives:

    · one-to-one, so uniqueness is preserved exactly
    · outputs that appear random and unordered
    · deterministic and reversible with the key

    n = 1,000,001  →  "kR7dQ2m"
    n = 1,000,002  →  "9xLp4Wz"      ← no visible relation
    n = 1,000,003  →  "Bv2nH8t"
```

**CANDIDATE:** The property that makes this work is **bijection**. A permutation over the keyspace maps each counter value to exactly one code and each code back to exactly one counter value. So I keep the guarantee that made the counter attractive — no collisions, no read-before-write — while the output reveals nothing about ordering or volume.

It also hides business information. Sequential codes leak your creation rate: an attacker creates a link today and one tomorrow, decodes both, and knows exactly how many links you issued in between. That's a competitive intelligence leak most people never consider.

**INTERVIEWER:** How do custom aliases interact with generated codes?

**CANDIDATE:** They collide, and it needs an explicit answer rather than hoping it doesn't happen.

The problem: a user requests the alias `4c92`, and my counter later generates that same code. Now two links want one code.

Three options.

**Partition the namespace by length.** Generated codes are exactly 7 characters; custom aliases must be 8 or more, or must contain a character I never generate. Clean separation with zero runtime cost. The downside is a product constraint — a user can't have a short custom alias, which is often exactly what they want.

**Check on generation.** When the generator produces a code, check whether it's been claimed as a custom alias, and skip if so. This reintroduces a read on the write path — the thing I built the counter to avoid.

**Reserve custom aliases in the same space.** When a custom alias is claimed, write it into the same table with a marker. The generator's permuted output would then need checking against it.

I'd choose the first — **partition by length or character class.** It's the only one that preserves the no-read-on-write property, and the product constraint is mild and explainable. Most custom aliases are words like `summer-sale`, which are naturally longer than seven characters anyway.

If the product genuinely required short custom aliases, I'd fall back to the check-on-generation approach and accept the read, because correctness beats elegance and the write path has headroom.

**INTERVIEWER:** What if the ID generator is unavailable?

**CANDIDATE:** It largely already is handled by the design, which is one of the nicer properties of range allocation.

Each instance holds a block of a thousand IDs in memory. If the allocator goes down, instances keep serving from their current block — that's up to a thousand more creations each, which at typical rates is minutes of runway per instance.

To extend that, I'd have instances **pre-fetch the next block** when the current one is, say, twenty percent remaining, rather than waiting for exhaustion. Then an allocator outage has to last longer than it takes to burn eighty percent of a block before anything fails.

If the allocator is down long enough that an instance exhausts both blocks, that instance fails creates with a clear 503. Reads are entirely unaffected — the read path never touches the generator — which is the right failure profile. Ninety-nine percent of traffic continues normally while the one percent that creates links degrades.

I'd also make the allocator itself simple and replicated. It's doing one thing — atomically incrementing a number — and that's about as easy as a strongly-consistent service gets.

> **▶ WHY THIS WORKS**
> Four approaches, each with a specific reason for rejection, building to the chosen one. That structure is much stronger than presenting the answer and defending it.
>
> The enumerability problem is the level-three insight and it's genuinely non-obvious. Most candidates propose a counter and never consider that sequential codes make the entire database crawlable — and the observation that short links protect *unlisted* resources rather than authenticated ones is what makes the severity clear. The business-intelligence leak is a bonus that costs one sentence.
>
> The Feistel/format-preserving-encryption answer is precise about *why* it works: bijection preserves uniqueness while destroying order. That's the property that matters, stated exactly.
>
> The custom alias answer gives three options, picks one, explains the tradeoff, and then says what they'd do if the product constraint were unacceptable. Having a fallback ready for your own rejected constraint is a strong signal.

---

## Phase 6 — Failure Modes & Wrap (0:42 – 0:47)

**INTERVIEWER:** Five minutes. What breaks?

**CANDIDATE:** Five things.

**Cache stampede on a viral link.** A link goes viral — a celebrity tweet — and jumps from zero to a hundred thousand requests per second. If it isn't cached, every one of those requests misses simultaneously and hits the same database partition. That partition is now receiving a hundred thousand queries per second for one row, and it falls over, taking down every link on that shard.

Mitigations: request coalescing at the cache layer, so a hundred thousand concurrent misses for the same key produce **one** database read while the rest wait on it. Probabilistic early expiry, so a hot key gets refreshed before it expires rather than all at once at the moment of expiry. And a negative cache for codes that don't exist, because a common attack is requesting random codes to force misses.

**Hot shard from a single link.** Related but distinct — even cached, one extremely popular link concentrates traffic on whichever cache nodes hold that key. Handle it by replicating hot keys across multiple cache nodes rather than relying on the hash placement, since the working set is tiny and duplicating a few hundred keys costs nothing.

**Analytics backpressure.** At 350,000 redirects per second, the click stream is 350,000 events per second. If Kafka is slow or the aggregator falls behind, the queue grows.

The critical property is that **this must never affect redirects.** Publishing is fire-and-forget, after the response, with a bounded local buffer that drops events when full rather than blocking. Losing analytics is acceptable; adding latency to a redirect is not. I'd alert on the drop rate so the loss is visible rather than silent.

**The link that turns malicious.** I raised this earlier — clean at creation, weaponized later. The mitigation is periodic re-scanning prioritized by click volume, plus a fast takedown path. What I'd add here is that takedown must invalidate **every** cache layer, including edge PoPs, and that's the operation I'd most want to test regularly, because it's the one that matters most and is exercised least.

**Expired links and the deletion question.** An expired link should return a clear "this link has expired" page rather than a 404, because the user should know the link *was* valid rather than thinking they mistyped. And the row is retained with an EXPIRED status rather than deleted — both to serve that page and to guarantee the code is never reissued, which I argued earlier.

**Observability** — four metrics:

- **Cache hit ratio**, and specifically at the edge. My entire capacity model assumes ~99%. A drop means either the working set grew or invalidation is over-firing, and both need investigation before they become a database problem.
- **Redirect p99 latency by region.** The user experience is geographic; a global average hides a region routing to the wrong PoP.
- **Create success rate and ID block exhaustion warnings.** The leading indicator of an allocator problem.
- **Blocked-link click attempts.** A spike means an active campaign is running, which is operationally interesting and worth alerting on.

**INTERVIEWER:** What would you revisit?

**CANDIDATE:** Two things.

The analytics pipeline got about one paragraph and it deserves more. At 350,000 events per second, aggregation is a real streaming problem — you need pre-aggregated rollups by dimension and time bucket, because computing "clicks by country for the last 30 days" from raw events at query time would be enormously expensive. And there's a cardinality issue lurking that I recognize from other systems: referrer URLs are effectively unbounded as a dimension, so naive per-referrer aggregation would explode. I'd want to cap it to a top-N plus an "other" bucket, and I didn't say that.

The bigger one is abuse. I gave it a sync check, an async scan, and periodic re-scanning, and I think that's directionally right but substantially incomplete for the actual adversarial environment.

A determined attacker doesn't use one link — they generate thousands across many accounts, cycle destinations, use redirect chains through other shorteners to defeat single-hop scanning, and cloak by serving benign content to scanner IP ranges and malicious content to real users. Cloaking in particular defeats my entire scanning design, because my scanner sees a clean page every time.

That's genuinely hard and it's an ongoing adversarial problem rather than a feature to implement. If I were building this for real, I'd expect abuse to consume more engineering effort over time than the core service, and I'd want to say plainly that what I described is a starting point rather than a solution.

**INTERVIEWER:** Good. That's time.

> **▶ WHY THIS WORKS**
> The cache stampede answer is the right one for this system and the mitigations are specific — request coalescing, probabilistic early expiry, negative caching for nonexistent codes — rather than a generic "add caching."
>
> "Takedown is the operation that matters most and is exercised least" is an operational instinct that reads as real experience.
>
> The self-critique is the strongest part. The candidate names a specific technical gap (referrer cardinality in analytics — connecting to a problem they'd recognize from time-series systems), then makes a much larger admission: that the abuse design is directionally right and substantially incomplete against a real adversary, naming cloaking as the specific technique that defeats it. Saying "I'd expect abuse to consume more engineering effort than the core service" is an honest assessment of where the real difficulty in this product lives.

---

# What To Extract

## The clock

| Phase | Time | What happened |
|---|---|---|
| Scope | 0–5 | Aimed questions at the periphery; rejected deduplication with a product argument; flagged abuse as the hard part |
| Estimate | 5–10 | Hot set fits in 9 GB → "caching problem with a database attached"; 6 chars is not enough |
| API + model | 10–15 | 302 over 301 defended on revocation; code reuse rejected in four layers |
| HLD | 15–27 | Two independent paths; sync vs async abuse checks; links that turn malicious; edge invalidation tied back to 301 |
| Deep dive | 27–42 | Four generation approaches; range allocation; **enumerability**; Feistel permutation; custom alias namespace |
| Wrap | 42–47 | Five failure modes; four metrics; honest admission that abuse is under-designed |

## The four moves that carried it

**1. Rejecting deduplication.** The tempting answer is "same URL, same code, saves space." The candidate reasons from the product — the link is the unit of analytics — and adds a revocation argument. Two independent reasons against the obvious answer.

**2. 302 over 301 on revocation grounds.** Turns an apparent performance optimization into an irreversible safety hole. You cannot recall a 301 from browsers that have cached it, which means you cannot kill a malicious link.

**3. Enumerability.** A sequential counter makes the entire link database crawlable, and short links protect *unlisted* resources rather than authenticated ones. The Feistel permutation preserves the uniqueness guarantee while destroying the ordering. This is the level-three insight of the problem.

**4. Links that turn malicious after creation.** Creation-time scanning is structurally insufficient, because an attacker can register a clean link, wait for it to be trusted, then change what's served. Scanning must be periodic and traffic-prioritized.

## The pushbacks

| Challenge | The move |
|---|---|
| "Should the same URL return the same code?" | Reasoned from the product; two independent arguments against dedup |
| "Where's the 1%/95% assumption from?" | Named the mechanism, the direction of likely error, and that it's the first thing to measure |
| "Why not reuse expired codes?" | Dismissed the premise, then argued it's still wrong, with a physical-world example and a security angle |
| "Why is one abuse check sync and one async?" | Cost and confidence profiles differ; then volunteered the harder insight about links turning malicious |
| "Why cache at the edge?" | Latency dominates a one-round-trip operation; then named the invalidation cost and tied it to the 301 decision |
| "How do custom aliases interact?" | Three options, picked one, gave the fallback if the constraint were unacceptable |
| "What if the ID generator is down?" | Already handled by range allocation; pre-fetch to extend runway; reads unaffected |

## The lesson this problem teaches

An easy problem does not produce an easy interview. It produces one where **everyone's happy path is identical**, so the entire signal comes from depth in the places most candidates treat as trivia: the redirect status code, code reuse, enumerability, and abuse.

The failure mode here isn't getting it wrong. It's finishing the core design at minute twelve and having nothing substantive left to say.

## Delivering this at L4

The core that reads as above band:

- No deduplication, with the analytics-attribution reason
- The hot-set calculation → this is a caching problem
- 7 characters not 6, with the arithmetic
- 302 over 301, with the revocation argument
- Range-allocated counter rather than per-write coordination
- Enumerability and *some* fix for it
- Cache stampede on a viral link
- One honest self-critique

The L5 extras: the Feistel/format-preserving-encryption specifics, the custom-alias namespace analysis with a fallback, links turning malicious after creation, edge invalidation tied back to the 301 decision, and the closing admission that abuse is under-designed against a real adversary.