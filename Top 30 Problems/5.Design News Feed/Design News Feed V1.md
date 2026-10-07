# Mock Interview Transcript — "Design a News Feed"

**Format:** 45 minutes, calibrated at Google L5 depth
**Structure:** the six-phase HLD template
**Annotations:** `▶ WHY THIS WORKS` blocks explain the move. Not part of the interview.

> **Note on this problem:** the most-asked social system design question, and the one with the most rehearsed answer. Nearly every candidate says "fanout on write, with fanout on read for celebrities." Saying that gets you to par, not above it. The signal is in *where* you put the boundary between the two, what happens at the merge, and whether you notice that a modern feed isn't a list at all.

---

## Phase 1 — Scope & Clarify (0:00 – 0:05)

**INTERVIEWER:** Design a news feed. Think Facebook or Twitter.

**CANDIDATE:** Let me establish what the feed actually *is* first, because there's a fork here that changes the entire design and it's easy to skip past.

**Is the feed chronological, or ranked?**

**INTERVIEWER:** Ranked. Assume a relevance model orders it.

**CANDIDATE:** That's the most consequential answer I'll get, and I want to say why immediately.

If the feed were chronological, it's a merge of sorted lists — I can precompute it, store it as an ordered structure, and append to it. The feed *is* a list.

If it's ranked, the feed is not a list. It's a **ranked view over a candidate set**, computed at read time. What I precompute is no longer the answer — it's the pool of candidates the ranker draws from. That distinction changes what fanout means, what I cache, and what pagination even is.

**INTERVIEWER:** Good. Keep going.

**CANDIDATE:** Four more.

**Follow graph** — symmetric friendship, or asymmetric follow?

**INTERVIEWER:** Asymmetric. Follows, not friends.

**CANDIDATE:** Which means follower counts are unbounded. A friendship graph has a natural ceiling — nobody has ten million friends. A follow graph has accounts with a hundred million followers, and that asymmetry is where the hard problem lives.

**Content types** — text and images only, or do I need video, reshares, and ads interleaved?

**INTERVIEWER:** Text and images. Reshares yes. No ads.

**CANDIDATE:** Reshares matter — they create a deduplication problem I'll come back to.

**Freshness requirement** — if I post right now, how quickly must it appear in my followers' feeds?

**INTERVIEWER:** What would you say?

**CANDIDATE:** Seconds for the author's own view, tens of seconds for everyone else, and I'd argue that's not a compromise.

The author needs to see their own post immediately — if I post and refresh and it isn't there, I assume it failed and I post again. That's a real product bug.

For followers, a delay of thirty seconds is genuinely invisible in a ranked feed, because ranking already means the newest item isn't necessarily at the top. Nobody can tell whether an item's absence is latency or the ranker deciding it wasn't relevant yet.

That relaxation buys me a lot — it means fanout can be asynchronous and batched rather than synchronous on the post path.

**INTERVIEWER:** Agreed.

**CANDIDATE:** Last: **do users see historical posts from someone they just followed?**

**INTERVIEWER:** Interesting. What do you think?

**CANDIDATE:** I'd say no for the precomputed path, and it's worth being explicit because it removes a large amount of work.

If following someone required backfilling their history into my feed, every follow becomes a potentially enormous write operation. And the product value is low — the point of a follow is what they post *next*.

I'd handle it at read time instead: their recent posts become eligible candidates on my next feed load, without any backfill write.

So to confirm: ranked feed, asymmetric follow graph with unbounded follower counts, text/images/reshares, seconds for self and tens of seconds for followers, no historical backfill on follow. Out of scope: the ranking model itself — I'll treat it as a service I call — plus ads, notifications, and the social graph service.

> **▶ WHY THIS WORKS**
> The chronological-vs-ranked question is the right first question and the candidate immediately states its consequence: a ranked feed is not a list, it's a view over a candidate set. That reframing governs everything after it, and most candidates never make it — they design a chronological feed and then bolt ranking on.
>
> The asymmetric-follow observation is small and load-bearing: friendship graphs have a natural ceiling, follow graphs don't, and that unboundedness is the entire hard problem.
>
> The freshness answer, offered when bounced back, distinguishes self-visibility (must be instant, for a product reason) from follower-visibility (can lag, because ranking masks it). That's a product-informed engineering argument, not a preference.

---

## Phase 2 — Estimation (0:05 – 0:10)

**CANDIDATE:** Two numbers here, and the second one is where the design actually lives.

*[writes]*

```
SCALE
  DAU                             500M
  Posts per day                   100M
  Feed loads per user per day     ~10
  Average follows per user        ~200

READ VOLUME
  500M × 10 = 5B feed loads/day
  → ~58K loads/sec avg, ~175K peak

FANOUT WRITE VOLUME     ← the inversion
  Each post must reach every follower's feed.
  100M posts × 200 avg followers
  = 20 BILLION fanout writes/day
  → ~230K writes/sec avg, ~700K peak

  Fanout writes are ~4× the read volume.
```

**CANDIDATE:** That's the first thing worth naming. This *feels* like a read-heavy product — people scroll far more than they post. But at the storage layer it's **write-heavy**, because every post is amplified by follower count.

Fanout inverts the ratio. A hundred million posts becomes twenty billion writes.

But the number that actually determines the architecture is the second one:

```
FOLLOWER DISTRIBUTION   ← the average is a useless number

  median user         ~50 followers
  p90                 ~500
  p99                 ~10,000
  p99.99              ~1,000,000
  max               ~100,000,000

  ONE post from the max account:
    100M fanout writes for a single post

  At 230K writes/sec total capacity:
    100,000,000 / 230,000 ≈ 7 MINUTES
    of the ENTIRE fanout capacity, for one post.

  And there are thousands of accounts above 1M followers,
  posting several times a day.
```

**CANDIDATE:** The average of 200 followers is arithmetically true and completely misleading. If I design for 200, I get a system that works beautifully for the median user and dies whenever anyone popular posts.

**The follower distribution is the design.** Everything interesting about this problem is a consequence of that tail — and the tail isn't an edge case to handle later, it's the majority of the load. A small number of accounts generate a disproportionate share of all fanout work.

So my architecture has to treat "user posts to 50 followers" and "user posts to 100 million followers" as **different operations**, not the same operation at different scales.

**INTERVIEWER:** You said thousands of accounts above a million followers. Does the threshold matter?

**CANDIDATE:** It does, and I want to correct the framing slightly — including my own.

Follower count alone isn't the right metric. The cost of pushing an account is **followers × posting frequency.**

```
  Account A:  1,000,000 followers  ×  1 post/month
              → 1M writes/month     — cheap to push

  Account B:     50,000 followers  ×  100 posts/day
              → 5M writes/day       — 150× more expensive
```

Account A has twenty times the followers and generates a fraction of the load. So the classification should be on **expected fanout writes per day**, not on a follower threshold.

That also means the classification is dynamic. An account that starts posting heavily should move categories without anyone intervening.

> **▶ WHY THIS WORKS**
> "Fanout inverts the read/write ratio" reframes a product that everyone assumes is read-heavy. That's a genuine insight delivered in one sentence.
>
> But the standout is "the average is a useless number." Most candidates compute 100M × 200 and design against it. Showing the distribution — median 50, max 100M — and then computing that a *single* post consumes seven minutes of total fanout capacity makes the tail viscerally concrete.
>
> The unprompted correction — cost is followers × post frequency, not followers — is the kind of refinement that reads as having actually thought about the classification rather than reciting "celebrities are special."

---

## Phase 3 — API & Data Model (0:10 – 0:15)

**CANDIDATE:** Three endpoints, and the feed one has a subtlety worth dwelling on.

*[writes]*

```
POST /v1/posts
     Idempotency-Key: <uuid>
     { text, mediaIds[], resharedPostId? }
     → 201 { postId, createdAt }

GET  /v1/feed?cursor=<opaque>&limit=20
     → { items[], nextCursor }

POST /v1/follow    { targetUserId }
DELETE /v1/follow  { targetUserId }
```

**CANDIDATE:** The interesting part is that **cursor** and why it can't be a timestamp or an offset.

In a chronological feed, a cursor is easy — it's a timestamp, and page two is "everything older than this."

In a ranked feed, that breaks:

```
  User loads page 1.  Ranker returns items ranked 1-20.
  User scrolls.       30 seconds pass. New posts arrive.
  User loads page 2.  Ranker re-runs with new candidates.

  A post that was ranked #25 is now ranked #15.
  → it appears on page 2, but the user never saw it on page 1
  A post that was ranked #18 is now ranked #22.
  → the user saw it on page 1 AND sees it again on page 2
```

**CANDIDATE:** Duplicates and gaps, both visible to the user, and duplicates in particular are something people notice and complain about.

So the cursor has to **pin the ranking**, not just a position. When the user loads page one, I compute and store the ranked ordering for that session — the top few hundred item IDs — and the cursor references that stored ordering plus an offset into it.

```
  cursor = { feedSessionId, offset: 20 }
```

Page two reads from the same frozen ranking. New posts don't intrude mid-scroll; they appear on the next refresh, which is the correct behavior anyway — feeds should be stable while you're reading them and update when you pull to refresh.

The session ranking expires after a few minutes.

**CANDIDATE:** Data model, across three stores:

```
POSTS   (durable, source of truth)
  postId (PK, snowflake-style: time-ordered)
  authorId, text, mediaIds[], createdAt
  resharedPostId, isDeleted

SOCIAL GRAPH   (separate service)
  followers:  userId → [followerIds]      ← for fanout
  following:  userId → [followeeIds]      ← for pull
  Both directions. Both needed.

FEED CANDIDATE POOL   (Redis, per user)
  feed:{userId} → sorted set
                   member: postId
                   score:  postId (time-ordered)
  Capped at ~1000 entries, TTL on inactive users
```

**CANDIDATE:** Three things to flag.

**Post IDs are time-ordered**, Snowflake-style — timestamp plus machine plus sequence. That means sorting by ID sorts by time with no separate timestamp index, and the ID itself is the sort key in the Redis sorted set.

**The graph is stored in both directions.** Followers for pushing, following for pulling. That's a denormalization with a consistency cost, and it's unavoidable — a hybrid design needs both traversals.

**The feed store holds candidates, not a feed.** This is the payoff for the scoping question. It's a capped pool of recent post IDs the ranker draws from, not an ordered final answer. That's why it's capped at a thousand — a ranker doesn't need ten thousand candidates, and unbounded growth per user times 500 million users is expensive.

**INTERVIEWER:** Why cap at 1000?

**CANDIDATE:** Two reasons, one obvious and one less so.

Memory is the obvious one. Five hundred million active users times a thousand post IDs at roughly 16 bytes is around 8 GB per replica just for the pools — manageable, but it would be 80 GB at ten thousand entries, for candidates the ranker will never look at.

The less obvious reason: **candidates below a certain recency are effectively dead.** A ranked feed almost never surfaces a post from three weeks ago over a post from three hours ago, because recency is a heavy feature in essentially every feed ranker. So storing older candidates is paying to keep things the ranker will reject.

If a user is inactive long enough that their pool goes fully stale, the right answer isn't a bigger pool — it's to rebuild on read when they return, which I'll come to.

> **▶ WHY THIS WORKS**
> The pagination-stability problem is a genuinely subtle bug that most candidates never surface. Explaining it with a concrete before/after — an item ranked #25 becoming #15 and appearing on page 2 unseen — makes it undeniable, and the fix (pin the ranking in a session, not the position) follows naturally.
>
> "The feed store holds candidates, not a feed" delivers on the Phase 1 reframe and shows the scoping answer actually changed the data model rather than being noted and forgotten.
>
> The cap answer gives a memory reason and then a better one — recency dominates ranking, so old candidates are dead weight the ranker would reject anyway. That second reason shows the candidate is reasoning about the ranker's behavior, not just storage cost.

---

## Phase 4 — High-Level Design (0:15 – 0:27)

**CANDIDATE:** The architecture follows directly from the follower distribution. Let me draw the two paths and then the merge.

*[draws]*

```
┌─────────────────────────────────────────────────────────────────────┐
│  WRITE PATH                                                         │
└─────────────────────────────────────────────────────────────────────┘

  POST /v1/posts
       │
       ▼
  ┌──────────────┐
  │ Post Service │──▶ [Post Store]   durable write, returns 201
  └──────┬───────┘         │
         │                 │ author sees it immediately
         ▼                 │ (self-view served from own posts)
  ┌──────────────┐
  │    Kafka     │  post-created event
  └──────┬───────┘
         ▼
  ┌────────────────────────────────────────────────┐
  │  FANOUT SERVICE                                │
  │                                                │
  │   look up author's classification:             │
  │                                                │
  │   ┌──────────────┐        ┌─────────────────┐  │
  │   │ NORMAL       │        │ HIGH-FANOUT     │  │
  │   │ (push)       │        │ (pull)          │  │
  │   │              │        │                 │  │
  │   │ get follower │        │ DO NOTHING      │  │
  │   │ list, write  │        │                 │  │
  │   │ postId into  │        │ post stays in   │  │
  │   │ each pool    │        │ author's own    │  │
  │   │              │        │ timeline only   │  │
  │   └──────┬───────┘        └─────────────────┘  │
  └──────────┼─────────────────────────────────────┘
             ▼
    ┌──────────────────┐
    │  Feed Pools      │  feed:{userId} → sorted set of postIds
    │  (Redis)         │  capped at 1000
    └──────────────────┘


┌─────────────────────────────────────────────────────────────────────┐
│  READ PATH                                                          │
└─────────────────────────────────────────────────────────────────────┘

  GET /v1/feed
       │
       ▼
  ┌────────────────┐
  │  Feed Service  │
  └───────┬────────┘
          │
          ├──1──▶ [Feed Pool]        precomputed candidates from
          │                          NORMAL accounts I follow
          │
          ├──2──▶ [Social Graph]     which HIGH-FANOUT accounts
          │              │           do I follow?  (~5-20 typically)
          │              ▼
          │       [Their Timelines]  fetch recent posts from each
          │
          ├──3──▶  MERGE + DEDUPE    union both sets
          │                          drop reshare duplicates
          │                          drop deleted / blocked
          │
          ├──4──▶ [Ranking Service]  score candidates, order them
          │
          ├──5──▶  STORE SESSION     freeze this ordering, issue cursor
          │
          └──6──▶ [Post Store]       hydrate top 20 IDs into full posts
                                     → return
```

**CANDIDATE:** Let me trace a post.

Author posts. The post service writes durably to the post store and returns 201 immediately — that's the seconds-for-self requirement, and the author's own view reads from their own timeline rather than waiting for fanout.

A post-created event goes to Kafka. That's the async boundary: the user's request is done, and everything downstream happens on its own schedule within the tens-of-seconds budget.

The fanout service consumes it and checks the author's classification.

**Normal account:** fetch the follower list, write the post ID into each follower's pool. Fifty followers, fifty small Redis writes. Cheap.

**High-fanout account:** do nothing at all. The post exists in the post store and in the author's own timeline. It's never pushed anywhere.

Now the read.

Feed service reads my precomputed pool — that covers every normal account I follow, already sitting there.

Separately, it asks the graph which high-fanout accounts I follow. That's a short list — most people follow a handful of very large accounts — and it fetches those accounts' recent posts directly.

Merge the two sets, deduplicate, filter deleted and blocked content, hand the candidates to the ranker, freeze the ordering into a session, hydrate the top twenty, return.

**INTERVIEWER:** Why not just do fanout on read for everyone? It's simpler.

**CANDIDATE:** It is simpler, and it fails on the numbers.

```
  Pure pull, per feed load:
    fetch following list                       (1 lookup)
    fetch recent posts from 200 accounts       (200 lookups)
    merge, rank, return

  At 175K feed loads/sec × 200 accounts each
  = 35 MILLION timeline reads/sec
```

That's an enormous read amplification, and it happens on the user's latency path, where I have maybe two hundred milliseconds total.

The asymmetry that makes push correct for most accounts: a post is written once and read many times. Doing the merge work once at write time and amortizing it across every subsequent read is the right trade — *when the fanout is small.*

It stops being right precisely when fanout is large, which is why the hybrid exists. Push where the write is cheap and the amortization pays off; pull where the write would be catastrophic and the read cost is bounded because you only follow a few such accounts.

**INTERVIEWER:** How do you decide which accounts are high-fanout?

**CANDIDATE:** Not a fixed follower threshold, for the reason I gave in estimation — the cost is followers times post frequency.

I'd compute an **expected daily fanout cost** per account:

```
  cost = follower_count × posts_per_day_rolling_average
```

and classify above some cost threshold, recomputed on a schedule — hourly is plenty, since this doesn't change fast.

Two refinements matter.

**Hysteresis.** An account hovering at the boundary shouldn't flip categories repeatedly, because each flip creates a window of inconsistency — posts made under one classification are in follower pools, posts under the other aren't. I'd use different thresholds for promotion and demotion so it takes a real change to switch.

**Demotion is dangerous.** Moving an account from pull to push means its recent posts aren't in anyone's pool. If I just flip the flag, followers see a gap. So demotion either backfills or, more simply, the read path keeps treating recently-demoted accounts as pull for a grace period covering the pool window.

**INTERVIEWER:** What about users who haven't logged in for months? You're still fanning out to them.

**CANDIDATE:** That's a large and easy win, and I should have raised it.

```
  Total accounts        ~2 billion
  Daily active          ~500 million
  → 75% of fanout writes go to pools nobody reads
```

Three quarters of twenty billion daily writes are pure waste.

So: only fan out to users active within some window — say thirty days. For everyone else, skip the write entirely.

When a dormant user returns, their pool is empty or stale, so the read path detects that and **rebuilds on demand** — pull from everyone they follow, rank, populate the pool, and they're back on the push path from then on.

The cost is a slower first load for a returning user, which is completely acceptable. Someone who hasn't opened the app in two months will tolerate a second.

This is essentially the same insight as the celebrity problem, applied to the other end: **don't do work whose result nobody will consume.** The celebrity case is "don't write to a hundred million pools for one post"; this is "don't write to pools nobody reads."

> **▶ WHY THIS WORKS**
> The pure-pull rejection uses arithmetic (35 million timeline reads/sec) rather than assertion, and then names the underlying principle — write once, read many, so amortize at write time when the write is cheap. That principle is what makes the hybrid boundary a reasoned choice rather than a memorized pattern.
>
> The classification answer refuses the obvious "follower count > 1M" answer and gives a cost function instead, then volunteers two refinements — hysteresis and the demotion gap — that show the candidate thought about the transition, not just the states.
>
> The inactive-user question got the best possible response: immediate acknowledgment that it's a large win they should have raised, the arithmetic showing 75% waste, the mechanism, and then a connection to the celebrity problem as the same principle applied at the other end. Recognizing two problems as one insight is a coherence signal.

---

**CANDIDATE:** That's the skeleton. The two deepest areas are **the fanout boundary and what happens at the merge** — which is where the hybrid actually gets hard — and **ranking**, including how the candidate pool interacts with the model. Preference?

**INTERVIEWER:** The merge. Tell me what goes wrong there.

---

## Phase 5 — Deep Dive: The Merge (0:27 – 0:42)

**CANDIDATE:** Good, because this is where the hybrid stops being elegant. Push and pull are each straightforward. Combining them creates four problems that neither has alone.

**Level one: the merge is where correctness lives.**

```
  MERGE INPUTS
    A. Feed pool           ~1000 postIds, precomputed, cheap read
    B. Pull-path timelines  N × ~50 recent posts, live reads

  These two sets have DIFFERENT freshness, DIFFERENT
  completeness guarantees, and can OVERLAP.

  Everything that goes wrong in a hybrid feed
  goes wrong right here.
```

**Level two: the four problems.**

**Problem one — reshare duplication.**

```
  I follow Alice, Bob, and Carol.
  All three reshare the same post P.

  My pool contains: P (via Alice), P (via Bob), P (via Carol)

  Naive render → the same content three times in my feed.
```

The fix is deduplicating on the **underlying content**, not the reshare. Every reshare carries a reference to the root post, so the merge groups by root ID and picks one representative — typically the reshare from whoever the ranker thinks I care about most, or the earliest.

The subtlety is that the *other* reshares are still signal. Three people I follow sharing something is strong evidence it's relevant to me, so the count feeds the ranker even though only one item renders. Discarding the duplicates entirely loses that.

**Problem two — deleted and blocked content.**

```
  A post is fanned out to 500,000 pools.
  The author deletes it.

  Option A: remove it from 500,000 pools
            → same fanout cost as writing it
  Option B: leave it, filter at read
            → cheap write, cost paid on every read
```

I'd filter at read. Deletion is comparatively rare, and paying a 500,000-write cost to undo a 500,000-write cost doubles the expense of a mistake.

The filter has to be cheap, since it runs on every feed load over a thousand candidates. A Bloom filter of recently-deleted post IDs, held in memory at the feed service, answers "definitely not deleted" for the overwhelming majority with no lookup. Only the small number of possible-positives need a check against the post store.

Blocks and mutes work the same way but are per-viewer, so the filter is on author ID against my block list — small, cacheable per session.

**Problem three — the pool is incomplete and you can't tell.**

```
  My pool is capped at 1000.
  I follow 5,000 accounts.
  A very active account I follow floods the pool.

  → posts from less active accounts get EVICTED
  → I stop seeing people who post rarely
  → and nothing in the system reports this
```

This is the one I'd worry about most, because it's a silent quality degradation rather than a visible failure. The user just gradually sees a narrower set of people, and neither they nor the system knows.

Mitigations: cap per-author entries within a pool, so no single account can consume more than some fraction. And track pool eviction pressure per user as a metric — high pressure means the pool is too small for that user's follow graph, and they may need a larger pool or a partial pull path.

**Problem four — the merge is a latency floor.**

```
  Pull path: fetch from N high-fanout accounts.
  These are parallel, but latency = SLOWEST of N.

  N=20, each ~10ms typical but p99 ~80ms
  → p99 of the max is much worse than 80ms
```

Tail amplification: fanning out to twenty reads and waiting for all of them means your latency is the maximum of twenty samples, which lands far into the tail of the individual distribution.

The fix is to treat the pull path as **best-effort with a deadline.** Set a budget — say 50 milliseconds — and rank with whatever returned. Missing one high-fanout account's recent posts from one feed load is invisible to the user; a feed that takes 800 milliseconds is not.

That's only acceptable because the feed is ranked. In a chronological feed, a missing post leaves a visible hole. In a ranked feed, absence is indistinguishable from the ranker deciding it wasn't relevant.

**Level three: what the merge means for the ranker.**

There's a structural problem the hybrid creates that I want to name, because I think it's the most interesting consequence.

```
  The ranker scores candidates. But the two sources
  don't have equal representation:

    Pool (push):   up to 1000 candidates, several days deep
    Pull:          ~50 recent posts × N accounts, live

  So high-fanout accounts are systematically
  OVER-represented in the candidate set relative to
  how many I follow — I fetch their recent posts
  directly and completely, while normal accounts
  compete for capped pool slots.

  → the ranker sees a biased sample
  → feeds drift toward large accounts
  → which is a PRODUCT problem, not a bug
```

**CANDIDATE:** This isn't a correctness failure — every candidate is legitimate. But the candidate *distribution* is shaped by the storage architecture rather than by relevance, and the ranker has no way to know that.

Left alone, it means feeds skew toward big accounts because those posts are structurally more likely to be candidates. Which is a real dynamic in social products, and at least partly an artifact of exactly this design decision.

Mitigations: normalize the number of candidates drawn per source so pull accounts don't get unlimited slots. Or pass source metadata to the ranker so it can correct for the sampling bias explicitly.

I'd want the ranker's owners to know about it either way, because a bias introduced by the storage layer that the model can't observe is the kind of thing that gets discovered a year later during a fairness review.

**INTERVIEWER:** That's a good observation. How would you actually test that the merge is correct?

**CANDIDATE:** Correctness here is hard to assert because there's no single right answer to compare against — a ranked feed is a judgment, not a computation.

So I'd split it into things that *are* checkable and things that aren't.

**Checkable, deterministically:** no duplicate root posts in a single response. No deleted or blocked content. No item appearing on both page one and page two of the same session. Every returned post ID exists and is visible to this viewer. Those are assertions and they belong in a test suite that runs on every change.

**Checkable statistically:** the distribution of sources in a feed — what fraction of items came from pull versus push — should be stable across releases. A sudden shift means something changed in the merge, even if no individual feed looks wrong.

**Not checkable automatically:** whether the feed is good. That's online metrics and human review.

The one I'd add specifically for this system: a **shadow comparison** where you compute the feed both with the hybrid path and with a pure-pull path for a small sample of users, and compare the candidate sets. Pure pull is slow but it's the ground truth for "what should have been eligible." If the hybrid is systematically missing things the pure path finds, that's the pool-eviction problem showing up, and it's the only way I'd reliably catch it.

> **▶ WHY THIS WORKS**
> Framing the merge as "where everything that goes wrong in a hybrid feed goes wrong" is accurate and it structures the whole section.
>
> The four problems are well-chosen: two are common (dedup, deletion), one is subtle and silent (pool eviction narrowing your feed with no signal), and one is a latency property most candidates miss (waiting on N parallel reads means p99 of the maximum, not p99 of one).
>
> The level-three observation is the strongest moment in the transcript. Noting that the storage architecture biases the ranker's candidate distribution toward large accounts — and that this is a product-level consequence the model cannot observe — connects an infrastructure decision to a systemic outcome. That's a staff-flavored observation.
>
> The testing answer separates deterministic assertions from statistical checks from things that can't be automated, and then proposes shadow comparison against pure-pull as ground truth. That's the right structure and the shadow idea is the only reliable way to catch the silent failure they identified.

---

## Phase 6 — Failure Modes & Wrap (0:42 – 0:47)

**INTERVIEWER:** Five minutes. What breaks?

**CANDIDATE:** Five things.

**Fanout backlog cascade.** A burst of posting — a major event, a coordinated campaign — pushes fanout lag from seconds to minutes. Feeds go stale, users refresh more aggressively, read load rises, and if the two share resources the write path gets worse.

Mitigations: separate the fanout consumer pool from the read path entirely so they can't starve each other. Prioritize by recency — a two-minute-old post is worth more than a ten-minute-old one, so drop the tail rather than delaying everything uniformly. And alert on fanout lag as a primary metric, since it's the leading indicator that feeds are about to feel broken.

**A normal account going viral mid-post.** An account classified as push suddenly gains followers rapidly. Fanout is already in progress against a follower list that's growing underneath it, and what should have been a cheap write becomes an expensive one after the classification decision was already made.

Handle it by making fanout **abortable**: if a fanout job exceeds a size threshold mid-execution, stop, mark the account high-fanout, and let the read path pick up the remainder. Partial fanout is fine because the pull path covers what push didn't.

**The follower list read is itself expensive.** For an account with 500,000 followers still classified as push, just *fetching* the list is a large read before any writes happen. I'd paginate the fanout — process followers in chunks and checkpoint — so a failure mid-fanout resumes rather than restarting.

**Thundering herd on a viral post.** A post from a high-fanout account is pulled by millions of feed loads simultaneously, all hitting that author's timeline. Standard caching applies, with request coalescing so a million concurrent reads for the same timeline produce one backend fetch.

**Graph inconsistency between directions.** I store followers and following separately. If a follow write updates one and fails on the other, fanout and pull disagree — someone gets posts they shouldn't, or misses posts they should get.

I'd write both through a single transaction where possible, or accept eventual consistency with a reconciliation job. The failure is low-severity — a briefly wrong feed — so eventual consistency is fine, but it needs to actually converge rather than drift.

**Observability** — four metrics:

- **Fanout lag p99.** How long from post to appearing in follower pools. The primary health metric for the write path.
- **Feed load p99, split by whether the pull path was used.** The pull path is the slow one; averaging them hides it.
- **Pool eviction pressure per user.** The silent-degradation metric from the deep dive.
- **Fraction of feed loads that hit the deadline and ranked on partial candidates.** Rising means the pull path is degrading and feeds are quietly getting worse.

**INTERVIEWER:** What would you revisit?

**CANDIDATE:** Two things.

The ranking service got treated as a black box, and it's doing an enormous amount of load-bearing work. Every feed load ranks up to a thousand candidates within a tight latency budget, which is a real serving problem — feature fetching, model inference, and caching all matter, and its latency directly bounds the feed's. I gave it one box.

The more important one: **I assumed a fixed pool size and a single classification threshold, and neither should be global.**

A user following fifty accounts and a user following five thousand have completely different needs from the pool — one is nowhere near capacity, the other is thrashing constantly. Similarly, whether an account should be push or pull depends partly on *who follows it*: an account with a million followers where most are inactive is much cheaper to push than one with two hundred thousand highly active followers.

So the right design probably makes both of those per-user or per-relationship decisions rather than global constants. That's meaningfully more complex — you'd need per-user pool sizing and a cost model that accounts for follower activity — and I don't think the version I described handles the extremes of the distribution well. Given that I opened by saying the distribution is the design, using global constants is somewhat inconsistent with my own framing.

**INTERVIEWER:** Fair. That's time.

> **▶ WHY THIS WORKS**
> The viral-account-mid-fanout case is a genuine race that most candidates never consider — the classification was correct when made and wrong by the time it executed. Making fanout abortable, with the pull path covering the remainder, is a clean answer that leans on the hybrid's existing structure.
>
> The metric list includes the two that only exist because of insights from earlier in the interview — pool eviction pressure and deadline-hit rate. Metrics that follow from your own analysis rather than a generic list is a small coherence signal.
>
> The self-critique is the strongest kind: the candidate identifies that their design uses global constants while their own opening argument was that the distribution is what matters. Catching an internal inconsistency in your own reasoning — and naming it as inconsistency rather than just a gap — is more convincing than any claim of completeness.

---

# What To Extract

## The clock

| Phase | Time | What happened |
|---|---|---|
| Scope | 0–5 | Ranked-vs-chronological first; "a feed is a view, not a list"; freshness split self from followers |
| Estimate | 5–10 | Fanout inverts read/write ratio; **the average follower count is useless**; cost = followers × frequency |
| API + model | 10–15 | Pagination stability requires pinning the ranking; pool holds candidates not a feed |
| HLD | 15–27 | Push/pull hybrid; pure-pull rejected with arithmetic; classification by cost with hysteresis; inactive-user win |
| Deep dive | 27–42 | Four merge problems; the silent pool-eviction one; **ranker bias from storage architecture** |
| Wrap | 42–47 | Five failure modes; four metrics; self-critique of using global constants |

## The four moves that carried it

**1. "The average follower count is a useless number."** Median 50, max 100M, and one post from the max account consumes seven minutes of total fanout capacity. Designing against the average produces a system that dies on the tail — and the tail *is* the load.

**2. Cost is followers × post frequency.** Refusing the standard "celebrities are special" framing for an actual cost function, then adding hysteresis and the demotion-gap problem. Shows the classification was thought through, not recalled.

**3. Inactive users are 75% of fanout waste.** And connecting it to the celebrity problem as the same principle at the other end — don't do work whose result nobody consumes.

**4. The storage architecture biases the ranker.** Pull accounts get complete recent timelines; push accounts compete for capped pool slots. So large accounts are structurally over-represented in the candidate set, the ranker can't see it, and feeds drift toward big accounts as an artifact of infrastructure. That's the observation that separates this from a rehearsed answer.

## The pushbacks

| Challenge | The move |
|---|---|
| "Why not pull for everyone?" | 35M timeline reads/sec arithmetic, then the write-once-read-many principle |
| "How do you classify high-fanout?" | Cost function not follower count; hysteresis; demotion creates a gap |
| "What about inactive users?" | Immediate concession, arithmetic, mechanism, then connected it to the celebrity insight |
| "What goes wrong at the merge?" | Four problems ordered from common to subtle, ending on the silent one |
| "How would you test the merge?" | Split deterministic / statistical / not-automatable; shadow comparison against pure pull as ground truth |

## Delivering this at L4

The core that reads as above band:

- Ranked vs chronological asked first, with the "not a list" consequence
- The follower distribution, not the average — with the celebrity arithmetic
- Push/pull hybrid with the pure-pull rejection on numbers
- Reshare deduplication on root content
- Deletion filtered at read, not unfanned-out
- Inactive-user fanout skipping
- Fanout lag as the primary metric
- One honest self-critique

The L5 extras: cost-based classification with hysteresis and the demotion gap, pagination stability via pinned session ranking, the silent pool-eviction problem, the pull-path deadline and tail amplification, and the ranker sampling-bias observation.

Deliver the core cleanly first.