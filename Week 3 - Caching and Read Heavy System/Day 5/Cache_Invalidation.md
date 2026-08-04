# Cache Invalidation — Strategies, Trade-offs, and Who Uses What

> *"There are only two hard things in Computer Science: cache invalidation and naming things."* — Phil Karlton

Capstone note for the caching set. **Eviction** (see `cache-eviction-policies.md`) is *involuntary* — the cache drops entries because it's out of room. **Invalidation** is *voluntary* — you remove or refresh an entry because it's no longer **correct**. Different problem, different failure mode: eviction hurts hit rate, bad invalidation serves **wrong data**. Related: `cache-patterns.md` (write-through vs. write-invalidate live here too).

The whole topic reduces to one tension: **how much staleness can you tolerate vs. how much load/complexity will you pay to reduce it?**

---



## The strategies



### 1. TTL / time-based expiration

Attach an expiry; the entry self-destructs when it lapses. Purely passive — no coordination with writes.

- **Trade-off:** dead simple and self-healing, but you accept a **staleness window** equal to the TTL, and everything expiring at once causes load spikes. Short TTL = fresher + more origin load; long TTL = less load + staler.
- **When:** staleness is tolerable and bounded; data has no clean "changed" event; you want zero write-path coupling.
- **Real:** DNS records, CDN `max-age`, Redis key TTLs, session stores (TTL = session lifetime).



### 2. Write-invalidate (delete-on-write)

On a write, delete the cache key; the next read misses and repopulates. This is the invalidation half of **cache-aside**.

- **Trade-off:** simple and keeps the cache honest, but exposes the two classic races — the **stale-set race** and the **thundering herd** (both below). Deleting (not updating) is deliberately idempotent and safe under retries.
- **When:** the default for read-heavy app caches (Redis in front of Postgres/MySQL).
- **Real:** the standard cache-aside setup everywhere; Facebook uses idempotent **deletes** rather than sets precisely for this safety.



### 3. Write-through / update-on-write

On a write, update the cached value in place so cache and store stay in sync.

- **Trade-off:** reads are always warm and consistent, but every write pays the cache-write cost and you **pollute the cache with data nobody reads**.
- **When:** read-your-writes matters and writes are infrequent (profiles, settings).



### 4. Key versioning / key-based expiration

Never mutate a cached value — change the **key**. Embed a version, `updated_at`, or content hash in the key. A write produces a *new* key; the old one is never invalidated, it just ages out via LRU.

- **Trade-off:** eliminates invalidation races entirely (there's nothing to invalidate), but churns memory and requires you to derive keys from state.
- **When:** you can cheaply compute a version from the source of truth; immutable-asset delivery.
- **Real:** Rails **"Russian-doll" caching** (the record's `updated_at` is baked into the fragment cache key, so an edit yields a fresh key automatically); **content-hashed static assets** (`app.a1b2c3d4.js` served `immutable` — a rebuild changes the filename, so no purge is ever needed); Stack Overflow's versioned cache keys.



### 5. Tag / surrogate-key / group invalidation

Tag each cached object with one or more labels; invalidate by tag to drop *all* objects sharing it in one operation — without knowing their URLs.

- **Trade-off:** the answer to "one change invalidates thousands of pages"; costs tag bookkeeping and a mapping from tag → objects.
- **When:** one content change fans out across many cached responses (an author edit touches every article; a product update touches listing + detail + recommendations).
- **Real:** **Fastly surrogate keys** (via the `Surrogate-Key` header; used by Anthropic, Webflow, Discord among others) — purge a tag and every object carrying it is invalidated globally in ~150ms; **Cloudflare Cache-Tags**; **Next.js** `revalidateTag`.



### 6. Explicit purge (URL / path, or purge-all)

Directly tell the cache to drop a specific URL, or nuke everything.

- **Trade-off:** surgical when you know the exact URL; purge-all is a blunt instrument that spikes origin load while the cache refills. Under CDN shielding, purges can race across POPs (Fastly's advice: purge twice, spaced out).
- **When:** a single known resource changed; emergencies (purge-all).
- **Real:** CloudFront `create-invalidation` by path; Fastly single-URL purge; most CMS "clear cache" buttons.



### 7. Event-driven / change-data-capture (CDC) invalidation

Invalidation is driven off the **database's own change stream** (binlog / commit log). A daemon tails the log, translates each committed write into the cache keys it affects, and broadcasts deletes.

- **Trade-off:** accurate and decoupled — the cache can't drift from the DB because invalidation *derives* from the same commits — but it's real infrastructure, with ordering and cross-region replication races to manage.
- **When:** large systems where app-code invalidation is too scattered or error-prone to trust.
- **Real:** **Facebook** `mcsqueal` — a daemon on each MySQL host reads the commit log, extracts deletes, and batches them through `mcrouter` to every frontend cluster's memcache; Debezium → Kafka CDC pipelines feeding cache-invalidation consumers; **Next.js CMS-webhook →** `revalidateTag` is the small-scale version of the same idea.



### 8. Lease-based invalidation

The cache hands a **lease token** to the first client that misses a key. Only the token-holder may repopulate, and the token is voided if a delete/invalidation arrives meanwhile. Tokens are also rate-limited per key.

- **Trade-off:** directly kills both hard races — a voided lease blocks a **stale set**, and rate-limited tokens throttle the **thundering herd** — at the cost of the cache arbitrating repopulation.
- **When:** extreme read/write concurrency on hot keys where the races actually bite.
- **Real:** **Facebook memcache leases** — a token is granted at most once per ~10s per key; a within-window reader is told to retry (by which time the holder has filled the cache), and a delete invalidates the outstanding token so a stale value can't be written.



### 9. Stale-while-revalidate / soft purge (serve-stale)

Don't hard-delete — mark the entry **stale** and keep serving it while a fresh copy loads in the background.

- **Trade-off:** protects latency and shields the origin during refresh; the price is briefly serving known-stale data.
- **When:** slight staleness is fine and you never want a user to wait on a refresh (content sites, catalogs).
- **Real:** the HTTP `stale-while-revalidate` directive; **Fastly soft purge**; **Next.js ISR** (serves the cached page, regenerates in the background, swaps it in). The variant when the *writer* must see their change immediately is **read-your-own-writes** — Next.js `updateTag` and Facebook's "web server invalidates its own cluster" both provide this.

---



## The two hard problems (name these unprompted)

**Cache stampede / thundering herd / dogpile.** A hot key is invalidated (or expires) and N concurrent readers all miss and slam the origin at once. Mitigations: **leases / single-flight / request coalescing** (one loader fills, the rest wait), **probabilistic early expiration** (refresh slightly before TTL, jittered, so keys don't expire in lockstep), **TTL jitter**, and **serve-stale** while one worker refreshes.

**Stale-set race (the cache-aside classic).** A read miss loads the old value from the DB and writes it to cache *just after* a concurrent write deleted the key — leaving a stale entry that survives until TTL. Mitigations: **delete-after-write ordering**, **delayed double-delete** (delete, wait, delete again to catch racing repopulates), **leases** (the racing set is rejected), **versioned keys** (no shared key to poison), and a **short TTL** as a backstop.

---



## When to use what


| Need                                   | Reach for                           | Real example                        |
| -------------------------------------- | ----------------------------------- | ----------------------------------- |
| Bounded staleness, zero write coupling | TTL                                 | DNS, CDN `max-age`, Redis TTL       |
| Read-heavy app cache, simple           | Write-invalidate (cache-aside)      | Redis + Postgres default            |
| Read-your-writes, rare writes          | Write-through / `updateTag`         | profile & settings caches           |
| Avoid invalidation races entirely      | Key versioning                      | Rails Russian-doll, hashed assets   |
| One change → many cached pages         | Tag / surrogate-key purge           | Fastly, Cloudflare, `revalidateTag` |
| One known URL changed                  | Explicit path purge                 | CloudFront invalidation             |
| Cache must never drift from DB         | Event-driven / CDC                  | Facebook `mcsqueal`, Debezium       |
| Hot-key races under high concurrency   | Leases                              | Facebook memcache                   |
| Never make users wait on refresh       | Stale-while-revalidate / soft purge | Next.js ISR, Fastly soft purge      |




## Real systems → strategy

- **Facebook / Meta (memcache):** idempotent **deletes** for invalidation, **leases** for the stale-set + thundering-herd races, and `mcsqueal` CDC off the MySQL commit log to broadcast invalidations region-wide.
- **Fastly (CDN):** **surrogate keys** for tag-based purging, **soft purge** + `stale-while-revalidate` for serve-stale, ~150ms global purge.
- **Cloudflare / CloudFront:** **Cache-Tags** and path-based `create-invalidation`; streaming/video leans on **TTL + versioned URLs** because purging huge objects is too costly.
- **Vercel / Next.js:** **ISR** (stale-while-revalidate), on-demand `revalidateTag` **/** `revalidatePath`, `updateTag` for read-your-own-writes, CMS **webhook → revalidate**.
- **Rails / Basecamp:** **key-based ("Russian-doll") expiration** — `updated_at` in the cache key, so writes never explicitly invalidate anything.
- **Front-end build tooling:** **content-hashed immutable filenames** — invalidation-by-never-needing-it.

---



## Interview framing

Lead by distinguishing **eviction (capacity, involuntary) from invalidation (correctness, voluntary)** — many candidates blur them. Then frame every choice as the **staleness-vs-load-vs-complexity** dial: TTL is cheapest and staleist; CDC/leases are most correct and most complex. The senior signal is naming the **two hard races unprompted** — the stale-set race and the thundering herd — and knowing that the elegant strategies (**key versioning** and **serve-stale**) mostly *sidestep* invalidation rather than solve it: version the key and there's nothing to invalidate; serve stale and a miss never becomes a stampede. If pushed for the highest-end answer, describe **CDC-driven invalidation off the DB log** (the `mcsqueal` pattern) — invalidation that can't drift because it's derived from the same commits that changed the data.

---

Sources: *Scaling Memcache at Facebook* (USENIX NSDI '13) — leases & mcsqueal; Fastly docs — surrogate keys, purging, soft purge (`fastly.com/documentation`); Next.js docs — ISR & revalidating (`nextjs.org/docs`); AWS CloudFront invalidation docs.