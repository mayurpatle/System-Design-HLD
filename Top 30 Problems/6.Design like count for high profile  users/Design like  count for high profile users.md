# Mock Interview Transcript — "Design Like Count for High-Profile Users"

**Format:** 45 minutes, calibrated at Google L5 depth
**Structure:** the six-phase HLD template
**Annotations:** `▶ WHY THIS WORKS` blocks explain the move. Not part of the interview.

> **Note on this problem:** it looks trivial — it's a counter. That's the trap. This is the sharpest hot-key problem in the bank, and the thing that makes it hard is not volume but **concentration**: millions of writes to a single logical key, where adding nodes to your cluster does nothing. Candidates who treat it as "increment a number" run out of material in eight minutes.

---

## Phase 1 — Scope & Clarify (0:00 – 0:05)

**INTERVIEWER:** Design the like count for a post — specifically for high-profile users, where a single post can get millions of likes.

**CANDIDATE:** Before I design anything I want to split this, because I think "like count" is actually two systems wearing one name, and they have almost nothing in common.

When a feed renders a post, it needs two facts:

```
  1. "1.2M likes"        ← a NUMBER, same for everyone,
                            read by millions, hot key

  2. "you liked this"    ← a BOOLEAN, per viewer,
                            read once per view, naturally sharded
```

The count is a shared, contended, read-hot value. The membership check is per-user and touches nothing anyone else touches.

If I build one system for both, the membership check inherits the count's hot-key problem for no reason. So I'd design them separately, and I'll come back to why that matters more than it sounds.

**INTERVIEWER:** Good split. Keep going.

**CANDIDATE:** Four questions.

**Does the count need to be exact?**

**INTERVIEWER:** What do you think?

**CANDIDATE:** No — and I'd argue the product already tells us that.

```
  What the UI shows:   "1.2M likes"
  Underlying value:    1,234,567

  Anything from 1,150,000 to 1,249,999 renders identically.
  → required precision at that scale: roughly ±4%
```

The display format is doing rounding for us. Once a post is above a few thousand likes, nobody can distinguish exact from approximate, and nobody is auditing it.

That said, I'd want exactness at **small** counts. "3 likes" showing as "4 likes" is visible and looks broken. So the precision requirement is inversely proportional to the count, which is a useful property — the values that are hardest to compute exactly are exactly the ones where exactness doesn't matter.

**INTERVIEWER:** Agreed. Continue.

**CANDIDATE:** **Is a like a toggle or an append?**

**INTERVIEWER:** A toggle. Like, then unlike, then like again.

**CANDIDATE:** That's important, and it kills the simplest possible design.

If likes were append-only, the count is just a monotonic counter and I could be very sloppy about it. A toggle means I need to know **whether this user has already liked it** before I change the count — otherwise a double-tap or a client retry double-increments.

So the count can't be independent of the membership. The membership is what makes the increment idempotent.

**Does the count need to be monotonic from the user's view?** Meaning, can the displayed number ever go down while someone is watching?

**INTERVIEWER:** Interesting. Why do you ask?

**CANDIDATE:** Because it's a real failure mode of the approach I'm going to propose, and I'd rather surface it now than have it emerge as a bug.

If I split a counter into shards and sum them on read, two consecutive reads can return a *lower* number the second time, depending on which shard values happened to be visible. Users notice a like count going backwards — it looks like their like was rejected.

So I'd want monotonicity per viewer even though the underlying value is approximate.

**INTERVIEWER:** Good. Assume yes, it must not go backwards.

**CANDIDATE:** Last one — **do we need the full list of who liked a post?**

**INTERVIEWER:** Yes, but it's a secondary screen. Users tap through to see it.

**CANDIDATE:** So it's rare and can be slower. That matters, because it's the only requirement that forces me to index likes by post rather than by user, and I'd like to keep that off the hot path.

To confirm: approximate count acceptable above small values, exact at small values, likes are toggles so idempotency is required, count must not decrease from a viewer's perspective, liker list is a secondary lower-traffic feature.

> **▶ WHY THIS WORKS**
> Splitting "like count" into a shared hot number and a per-user boolean in the first ninety seconds is the move that governs the whole interview. Most candidates design one system and inherit the hot-key problem in a place it doesn't belong.
>
> The precision answer derives the requirement from the *display format* — "1.2M" means ±4% is invisible — rather than asserting "approximate is fine." And the observation that precision matters inversely to magnitude is a genuinely useful property: the hard values are the ones where accuracy doesn't matter.
>
> Asking about monotonicity is the standout. The candidate is surfacing a failure mode of a solution they haven't proposed yet, which shows they're designing forward rather than recalling a pattern.

---

## Phase 2 — Estimation (0:05 – 0:10)

**CANDIDATE:** The aggregate numbers here are unremarkable. The concentration is the whole problem.

*[writes]*

```
GLOBAL AGGREGATE
  DAU                            500M
  Likes per day                  ~10B
  → ~115K likes/sec avg, ~350K peak

  Spread across 100M posts/day, that's
  ~100 likes per post. Trivial.

THE HIGH-PROFILE POST   ← the actual problem
  Celebrity posts. 10M likes in the first hour.
  Distribution is heavily front-loaded —
  roughly half arrive in the first 10 minutes.

     5,000,000 likes / 600 seconds
     ≈ 8,300 writes/sec  TO A SINGLE KEY
     peak minute may hit  ~20,000/sec

READ VOLUME ON THAT SAME KEY
  The post is in ~100M feeds.
  Say 50M people view it within the hour.
     ≈ 14,000 reads/sec of the same key
  Plus re-renders, scroll-backs → call it 30K/sec

  → ~50,000 ops/sec, ALL on one logical key
```

**CANDIDATE:** Here's why that number is different in kind from the aggregate.

```
  350,000 writes/sec spread over 100M posts
     → add nodes. Load distributes. Solved with money.

  20,000 writes/sec to ONE key
     → that key lives on ONE node.
       Adding 1,000 nodes changes nothing.
       999 of them sit idle while one melts.
```

**A hot key is not a throughput problem. It's a concentration problem, and capacity doesn't touch it.**

For reference on what a single key can actually absorb:

```
  Postgres row with row-level locking     ~1,000 writes/sec
     (serialized on the lock — this is optimistic)
  Cassandra single partition              ~5,000 writes/sec
     (before compaction and repair suffer)
  Redis single key, single node          ~100,000 ops/sec
     (but that saturates the node for everything else)
```

So a database row is off the table by an order of magnitude. Redis could technically absorb the writes, but one key monopolizing a node while the rest of the cluster idles is a bad use of the cluster and gives me no headroom for a bigger event.

The design therefore has to **break the concentration**, not absorb it.

**INTERVIEWER:** How does membership storage scale?

**CANDIDATE:** Differently, and much more comfortably — which is the payoff for splitting them in scoping.

```
  10B likes/day × 16 bytes (userId + postId)
  ≈ 160 GB/day  ≈ 58 TB/year

  Large, but it's uniformly distributed — every user
  likes things, so the write load spreads naturally
  across whatever you shard by.
```

The key point: **membership has no hot key if I index it by user.** "Has Alice liked post X" is a lookup in Alice's data, and Alice is one of 500 million users. Nobody else touches her partition.

If I indexed membership by *post*, I'd have 10 million rows in one partition for a celebrity post, and I'd have recreated the hot-key problem in the place I least need it.

That's a partitioning decision that follows directly from the split I made in Phase 1.

> **▶ WHY THIS WORKS**
> "A hot key is not a throughput problem, it's a concentration problem, and capacity doesn't touch it" is the reframe, and the illustration — 999 idle nodes while one melts — makes it concrete.
>
> Quoting rough single-key ceilings for three different stores is the kind of grounded number that shows the candidate has some feel for what infrastructure actually does, rather than treating everything as infinitely scalable.
>
> The membership answer closes the loop on the Phase 1 split: indexing by user means no hot key at all, and indexing by post would have recreated it. A scoping decision producing a partitioning decision is exactly the coherence interviewers look for.

---

## Phase 3 — API & Data Model (0:10 – 0:15)

**CANDIDATE:** Three operations, and the read one is where the interesting shape is.

*[writes]*

```
POST   /v1/posts/{postId}/like
       Idempotency-Key: <uuid>
       → 200 { liked: true, count: <approx> }

DELETE /v1/posts/{postId}/like
       → 200 { liked: false, count: <approx> }

GET    /v1/posts/{postId}/likers?cursor=&limit=50
       → { users[], nextCursor }        ← secondary screen

   NOTE: there is no GET for the count itself.
   The count is returned inline with the post during
   feed hydration — one of the 20 posts in a feed load,
   not a separate request.
```

**CANDIDATE:** That last note matters for the read numbers. The count isn't fetched by a dedicated call; it rides along with post hydration. So a feed load of 20 posts needs 20 counts, and my 30,000 reads/sec figure is really 30,000 feed impressions that happen to include this post.

Which means the count needs to be available wherever hydration happens — cheaply, and in a batch.

**CANDIDATE:** Data model — three stores, one per question:

```
1. DISPLAY COUNT           the number everyone reads
   Redis:  likes:count:{postId} → integer
   Single key. Read-hot, written only by an aggregator.

2. COUNTER SHARDS          the writes land here
   Redis:  likes:shard:{postId}:{0..N} → integer
   N keys per post, spread across the cluster.

3. MEMBERSHIP              "did I like it", and "who liked it"
   Cassandra:
     likes_by_user   ((user_id, bucket), post_id, ts)
     likes_by_post   ((post_id, bucket), user_id, ts)
```

**CANDIDATE:** Two things to explain.

**The count exists in two forms**, and that's deliberate rather than redundant. Writes go to shards so they distribute; reads go to a single aggregate so they're cheap. An aggregator moves value from one to the other. I'll cover the mechanics in the deep dive because it's the core of the design.

**Membership is stored twice**, indexed both ways, because there genuinely are two questions:

```
  likes_by_user      "has Alice liked post X?"
                     partition = Alice → no hot key
                     this is the HOT path (every feed render)

  likes_by_post      "who liked post X?"
                     partition = post → HOT KEY, 10M rows
                     this is the COLD path (secondary screen)
```

The bucket in the partition key is what keeps the post-indexed table from producing a single enormous partition. For a celebrity post I'd bucket by something like a time window or a hash range, so 10 million likers become many bounded partitions rather than one unbounded one.

**INTERVIEWER:** Why store membership twice? That's a denormalization with a consistency cost.

**CANDIDATE:** It is, and I'd defend it on the access-pattern asymmetry.

The two questions need opposite partition keys, and no single index serves both. If I only had the post-indexed table, then checking "did Alice like this" would require reading a partition containing 10 million rows — on every feed render, for every post. That's catastrophic.

If I only had the user-indexed table, the liker list would require scanning every user in the system.

So both exist. The write path does two inserts, and since a like is a single logical event I'd write them through one path — an event that fans out to both tables — so they converge even if one write fails.

The consistency cost is low-severity: a brief window where the liker list doesn't show someone who has liked. Nobody notices, and it converges in seconds.

I'd also note that these two tables are *not* the count. The count is derived from membership conceptually, but computing it by counting rows is exactly what I can't afford — that's a 10-million-row aggregation on a hot partition. The count is maintained incrementally in Redis, and the tables are the durable record.

**INTERVIEWER:** So what happens if the Redis count and the Cassandra tables disagree?

**CANDIDATE:** They will, and I'd plan for it rather than trying to prevent it.

Redis is maintaining an approximate count under high write volume; Cassandra holds the exact membership. Drift comes from lost increments, shard resets, or aggregator gaps.

I'd run a **reconciliation job**: periodically, for the posts that matter — high-count ones — count the actual rows in `likes_by_post` and correct the Redis value.

Two things about it:

It runs **rarely and off-peak**, because counting 10 million rows is expensive and there's no urgency. Daily is fine.

It corrects **downward carefully.** If reconciliation finds the true count is lower than displayed, dropping the number visibly is worse than the error. I'd either apply corrections gradually or only correct when the discrepancy exceeds the display precision — which, given "1.2M" rounding, is a fairly wide band.

That's the monotonicity requirement from scoping showing up as an operational constraint.

> **▶ WHY THIS WORKS**
> Noting that the count rides along with hydration rather than having its own endpoint is a small realism detail that reframes the read numbers correctly.
>
> The two-table membership design with opposite partition keys, and the explicit statement that the hot path uses the user-indexed one, follows directly from Phase 2's analysis. And identifying which table is hot-path versus cold-path is the reason the denormalization is worth its cost.
>
> "The count is derived from membership conceptually, but computing it by counting rows is exactly what I can't afford" pre-empts the obvious question about why a separate counter exists at all.
>
> The reconciliation answer connects back to the Phase 1 monotonicity question — corrections must not visibly decrease the count — which is a constraint most people wouldn't think to apply to a repair job.

---

## Phase 4 — High-Level Design (0:15 – 0:27)

**CANDIDATE:** The architecture is shaped entirely by breaking the concentration. Let me draw the write path first.

*[draws]*

```
┌──────────────────────────────────────────────────────────────────┐
│  WRITE PATH                                                      │
└──────────────────────────────────────────────────────────────────┘

  POST /v1/posts/{postId}/like    (user: alice)
       │
       ▼
  ┌────────────────┐
  │  Like Service  │
  └───────┬────────┘
          │
          ├──1──▶ [likes_by_user]   INSERT IF NOT EXISTS
          │       Cassandra          partition = alice
          │                          ← IDEMPOTENCY GATE
          │                          already liked? → return, no-op
          │
          │       (only if this was a NEW like:)
          │
          ├──2──▶ [Counter Shard]   INCR likes:shard:{postId}:{r}
          │       Redis              r = random 0..N-1
          │                          ← writes SPREAD across N keys
          │
          └──3──▶ [Kafka]           like-event
                       │
                       ▼
                 [likes_by_post]    async, for the liker list
                 Cassandra           partition = (postId, bucket)


┌──────────────────────────────────────────────────────────────────┐
│  AGGREGATION  (background, continuous)                           │
└──────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────┐
  │  Aggregator                                  │
  │                                              │
  │   every ~1s, for HOT posts:                  │
  │     sum = Σ likes:shard:{postId}:{0..N-1}    │
  │     SET likes:count:{postId} = sum           │
  │                                              │
  │   ← N reads, ONE write, once per second      │
  │     regardless of how many users are reading │
  └──────────────────────────────────────────────┘


┌──────────────────────────────────────────────────────────────────┐
│  READ PATH  (during feed hydration)                              │
└──────────────────────────────────────────────────────────────────┘

  hydrate 20 posts
       │
       ├──▶ [likes:count:{postId}]    MGET, single key per post
       │     Redis, replicated         ← ONE read, not N
       │
       └──▶ [likes_by_user]           "did alice like these 20?"
             Cassandra                 partition = alice
                                       ONE partition read, 20 rows
```

**CANDIDATE:** Let me trace a like.

Alice taps like. The Like Service first writes to `likes_by_user` with an insert-if-not-exists condition. That's the **idempotency gate** — if Alice already liked this post, the insert fails, we return the current state, and nothing else happens. No double increment.

That gate is why the toggle semantics from scoping matter. Without it, a client retry after a timeout would increment twice.

If it was genuinely new, we increment **one randomly chosen shard** out of N. That's the concentration break: instead of 20,000 writes/sec hitting one key, it's 20,000/N per shard. With N=100, that's 200/sec per key, which is nothing.

Then a Kafka event carries the like to the post-indexed table asynchronously, for the liker list. That's off the critical path because nobody is waiting for the secondary screen.

Separately and continuously, the aggregator sums the shards into a single display key.

**INTERVIEWER:** Why not just have readers sum the shards themselves?

**CANDIDATE:** Because that trades a write problem for a much worse read problem, and the read volume is higher.

```
  READERS SUM THE SHARDS
    each read = N Redis reads (N=100)
    30,000 reads/sec × 100 = 3,000,000 Redis ops/sec
    for ONE post

  AGGREGATOR SUMS, READERS READ ONE KEY
    aggregator: 100 reads/sec (once per second)
    readers:    30,000 reads/sec on one key
    total:      ~30,100 ops/sec
```

A hundred-fold difference. And it gets worse as I add shards — more shards is better for writes and linearly worse for reads if readers do the summing.

The aggregator decouples them completely: **shard count can grow for write scaling without affecting read cost at all.**

There's a second benefit. The display key is written only by the aggregator — roughly once per second — so it's effectively read-only from the cluster's perspective. That means I can replicate it aggressively, cache it at the application tier, and put it behind a CDN if I wanted, none of which is practical for a key that users are writing to constantly.

**INTERVIEWER:** How does the user see their own like immediately if the aggregator lags a second?

**CANDIDATE:** That's a client concern, and I'd solve it there rather than tightening the server.

The client does an **optimistic update**: on tap, immediately render the heart filled and increment the displayed number locally. Then send the request. If it succeeds, the local state was right and nothing changes visually. If it fails, revert and show an error.

So the user's own action feels instant regardless of server-side aggregation lag.

For everyone *else*, a one-second delay in seeing the count rise is completely invisible — nobody is watching a celebrity post's like count with a stopwatch.

This is worth naming as a general pattern: **the latency requirement for your own action is completely different from the latency requirement for observing others' actions.** Conflating them leads to over-engineering the server for a problem the client solves in three lines.

**INTERVIEWER:** How do you decide which posts are "hot" enough to need shards?

**CANDIDATE:** I wouldn't shard everything, because for the vast majority of posts it's pure overhead.

```
  99.9% of posts: fewer than 1,000 likes total
     → a single key. No shards, no aggregator.
       Read and write the same key directly.

  0.1% of posts: high volume
     → sharded, aggregated
```

So there's a **promotion mechanism**. Every post starts unsharded. When its write rate crosses a threshold — say a few hundred per second, or a total count crossing some level — it gets promoted: shards are created, the existing count is placed in shard zero, and writes start distributing.

The transition needs care. A naive switch loses writes that were in flight against the old single key. I'd handle it by making the promotion additive rather than a cutover — the old key becomes shard zero rather than being replaced, so anything still writing to it is still counted.

I'd also detect hotness at the **application tier**, not by asking the storage layer. The Like Service can track per-post write rates in a local sliding window and report them, which is cheaper than instrumenting Redis for hot-key detection.

> **▶ WHY THIS WORKS**
> The idempotency gate being the *first* step of the write path, and being the same operation that answers "did Alice already like this," is a clean piece of design — one operation serving correctness and deduplication.
>
> The "why not let readers sum" answer is the strongest in the phase: it does the arithmetic (3M ops/sec vs 30K), then names the decoupling property that matters more — shard count can grow for writes without touching read cost. That second-order observation is what makes the aggregator obviously correct rather than merely reasonable.
>
> The optimistic-update answer refuses to solve a client problem on the server, and generalizes it: latency requirements for your own action versus observing others' actions are different problems. That's a reusable principle stated crisply.
>
> Not sharding 99.9% of posts, with a promotion mechanism and a note about the transition losing in-flight writes, shows the candidate is thinking about the whole distribution rather than the headline case.

---

**CANDIDATE:** That's the skeleton. The two areas with real depth are **the sharded counter mechanics** — choosing N, the monotonicity problem, and drift — and the **membership storage** at 10 million entries per post. Preference?

**INTERVIEWER:** The counter. Start with how you pick N.

---

## Phase 5 — Deep Dive: Sharded Counters (0:27 – 0:42)

**CANDIDATE:** Good, because N is a genuine tradeoff and the monotonicity problem underneath it is subtler than it looks.

**Level one: choosing N.**

```
  N too small
    shards still hot.  20,000/sec ÷ 10 = 2,000/sec per key.
    Still above what one key should absorb comfortably.

  N too large
    aggregator does N reads per cycle.
    N = 10,000 → 10,000 reads/sec just to maintain one count.
    And most shards are near-empty, wasting memory
    and adding aggregation latency.

  The bound that matters:
      writes_per_sec / N  <  safe_per_key_rate

  20,000 / N < 1,000   →   N > 20
  Round up for headroom → N = 100
```

So N is derived from the write rate, not chosen by feel. And because I'm promoting posts dynamically, N doesn't have to be one number — a moderately hot post might get 16 shards and a global event might get 500.

I'd make N **adaptive**: start at a low value on promotion and increase it if per-shard write rates stay high. Increasing N is safe — new shards start at zero and the sum still works. Decreasing N is harder, because you'd have to merge shard values without losing concurrent writes, so I'd simply never decrease it. A post that was hot and cooled off carries some wasted keys, which is cheap.

**Level two: the monotonicity problem.**

This is the failure I flagged in scoping, and here's the mechanism.

```
  Shards for a post, N=4:

    t=0    shard0=100  shard1=100  shard2=100  shard3=100
           aggregator sums → 400 → SET count=400

    Reader A sees 400.

    t=1    writes arrive; shards now
           shard0=150  shard1=140  shard2=130  shard3=120

           aggregator reads them SEQUENTIALLY, and
           writes continue during the read:

             reads shard0 → 150
             reads shard1 → 140
             reads shard2 → 130
             reads shard3 → 120
                            ─────
                            540 → SET count=540

    That's fine. But suppose the aggregator instance
    that wrote 540 was slow, and a SECOND instance
    started earlier, read stale values, and finishes AFTER:

             reads at t=0.5 → sums to 480
             writes count=480   ← AFTER the 540 write

    Reader B now sees 480.  The count went BACKWARDS.
```

**CANDIDATE:** Two separate causes here, and they need different fixes.

**Out-of-order aggregator writes.** Two aggregator runs overlapping, and the slower one lands last with older data.

Fix: make the write conditional on being an increase. In Redis, a small Lua script that reads the current display value and only sets if the new sum is larger. That makes the display key monotonically non-decreasing by construction, which is exactly the property I want given that likes vastly outnumber unlikes.

**Torn reads across shards.** The aggregator reads shard zero, then writes land, then it reads shard three. The sum is internally inconsistent — it never corresponds to a single moment in time.

That one I'd simply accept. The sum is approximate anyway, the error is bounded by the writes arriving during one aggregation pass, and at ±4% display precision it's invisible. Trying to get a consistent snapshot across 100 keys would need coordination that costs far more than the error is worth.

The important thing is that the error is **bounded and always in the same direction** — a torn read undercounts slightly, never overcounts wildly.

**INTERVIEWER:** What about unlikes? Those decrement.

**CANDIDATE:** They complicate the monotonic-write trick, and I'd handle it by keeping the two directions separate.

```
  Instead of one shard set with +1 and −1:

    likes:shard:{postId}:{r}     ← increments only
    unlikes:shard:{postId}:{r}   ← increments only

    display = Σ likes − Σ unlikes
```

Both underlying sets are monotonically increasing, which keeps each individually safe to aggregate. The *difference* can still decrease, which is correct — if more people unlike than like, the count should genuinely fall.

This is essentially a **PN-counter**, the CRDT construction for a counter that supports decrements: track increments and decrements separately, each monotonic, and take the difference. That structure exists precisely because a single value that goes both directions is hard to merge safely across replicas.

So the monotonic-write guard applies to each half independently, not to the displayed difference. Genuine decreases pass through; spurious ones from aggregation races don't.

In practice unlikes are a small fraction of likes, so the display is overwhelmingly increasing anyway.

**Level three: drift, and what happens when Redis loses data.**

**CANDIDATE:** The shards are in Redis, which is memory-first. A node failure or an unclean failover can lose recent increments.

Three things about that.

**The loss is bounded to one shard.** If shard 47 of 100 is lost, I've lost roughly 1% of the count. At display precision, that's invisible. Sharding accidentally gives me failure isolation — losing one key loses one Nth of the value rather than all of it.

That's worth stating: the sharding I did for write distribution also limits blast radius on data loss. Same mechanism, second benefit.

**Cassandra is the durable record.** `likes_by_post` has every like as a row. The Redis counters are a fast cache of a value that's reconstructable. So drift is repairable, which is why the reconciliation job exists.

**Reconciliation must respect monotonicity.** As I said in the data model — if the true count is lower than displayed, I'd rather leave the display slightly high than visibly decrease it. I'd only correct downward if the error exceeds display precision, and then gradually.

**INTERVIEWER:** Say a post is getting 100,000 likes per second — a global event, far beyond your estimate. Does this design hold?

**CANDIDATE:** Partly, and I'd want to be honest about where it stops.

With N=1000 shards, 100,000/sec is 100 writes/sec per shard. The shards themselves are fine — that scales linearly and I can keep increasing N.

Two things break before the shards do.

**The aggregator.** Summing 1,000 keys every second is 1,000 reads/sec for one post, and if there are many such posts simultaneously, the aggregator becomes the bottleneck. Fix: aggregate hierarchically — sum groups of shards into intermediate values, then sum those. That turns a wide fan-in into a tree and bounds the fan-out at any level.

**The idempotency gate.** Every like does a conditional insert into `likes_by_user`. At 100,000/sec globally that's fine, because it's partitioned by user and spreads perfectly. But it's a conditional write, and in Cassandra that's a lightweight transaction with a Paxos round — considerably more expensive than a normal write.

At extreme rates I'd reconsider that. Options: accept a small double-count risk and use a plain write with a client-side dedup cache, or move the idempotency check to a faster store — a Redis set of recent likers per post, with Cassandra as the durable record written asynchronously.

I'd lean toward the second: **check idempotency in Redis on the hot path, persist to Cassandra asynchronously.** The failure mode is that a Redis loss could allow a rare double-count, which at display precision doesn't matter.

The thing I'd want to flag is that this is a case where the *idempotency mechanism* becomes the bottleneck rather than the counter — which is a bit counterintuitive, since the counter is the thing everyone thinks about.

> **▶ WHY THIS WORKS**
> Deriving N from the write rate with an explicit inequality, then noting that N should be adaptive and that it can safely increase but not decrease, turns a parameter into a reasoned decision.
>
> The monotonicity section identifies *two* distinct causes — out-of-order aggregator writes and torn reads — and gives different treatment to each: fix one with a conditional write, accept the other because the error is bounded and directional. Knowing which problems to fix and which to accept is a senior instinct.
>
> The PN-counter answer connects the unlike problem to a known CRDT construction, and explains *why* that construction exists (a bidirectional value is hard to merge safely). Recognizing a pattern rather than inventing one.
>
> "Sharding for write distribution also limits blast radius on data loss" is a nice second-order observation about a mechanism already in the design.
>
> The 100K/sec question gets the best possible answer: the shards hold, but two *other* things break first, and one of them — the idempotency gate — is counterintuitive. Identifying that the bottleneck moves somewhere unexpected under extreme load is exactly the kind of thing that comes from thinking a design through rather than recalling it.

---

## Phase 6 — Failure Modes & Wrap (0:42 – 0:47)

**INTERVIEWER:** Five minutes. What breaks?

**CANDIDATE:** Five things.

**Thundering herd on the display key.** If `likes:count:{postId}` is cached at the application tier with a TTL and it expires while 30,000 requests per second are reading it, every one of those misses and hits Redis simultaneously.

Standard mitigations: request coalescing so concurrent misses produce one backend read, and probabilistic early expiry so the key refreshes before it expires rather than at the instant everyone needs it. Given the read concentration here, I'd consider never expiring it at all — the aggregator writes it every second, so a subscriber-based invalidation would be more appropriate than a TTL.

**The double-tap race.** A user taps like twice rapidly, or taps and the client retries after a timeout. Two concurrent requests both check the idempotency gate before either has written.

The conditional insert handles this — one succeeds, one fails — provided the check and the write are the same atomic operation. If they were separate read-then-write steps, both would see "not liked" and both would increment. That's why the gate is `INSERT IF NOT EXISTS` rather than a `SELECT` followed by an `INSERT`.

**Promotion transition losing writes.** Covered in the HLD — making the old single key become shard zero rather than replacing it means in-flight writes are still counted. I'd add that the promotion should be idempotent, since detection might fire twice from different service instances.

**Aggregator falling behind or dying.** If it stops, the display count freezes while shards keep accumulating. Users see a stalled count on the hottest posts, which is exactly where it's most visible.

I'd run multiple aggregator instances with post assignment partitioned between them, so one instance failing affects a subset. And monitor **aggregation lag** — the time since a hot post's display value was last updated — as the primary health metric for this system.

**Cross-region divergence.** If likes are accepted in multiple regions, each region has its own shards and the counts differ.

The PN-counter structure helps here: increment-only sets merge cleanly by taking the maximum per shard, which is exactly the CRDT merge for a G-counter. So regional counts can converge without coordination. Each region can serve its own approximate count immediately and converge asynchronously — which is appropriate, because a slightly different like count in different regions is not a correctness problem.

**Observability** — four metrics:

- **Aggregation lag for hot posts.** Directly measures whether displayed counts are current where it matters most.
- **Per-shard write rate distribution.** If shards are unevenly loaded, the random assignment isn't working, or N needs to increase.
- **Promotion event rate.** A spike means many posts are going viral at once, which is a leading indicator of aggregator load.
- **Reconciliation drift magnitude.** How far Redis had diverged from Cassandra when the repair ran. Growing drift means increments are being lost somewhere.

**INTERVIEWER:** What would you revisit?

**CANDIDATE:** Two things.

The membership storage got less attention than it deserves. Fifty-eight terabytes a year of like records, growing forever, with two indexes — that's a real storage problem with its own lifecycle questions. Do old likes get archived? What happens when a user is deleted and their likes must be removed from millions of posts? I treated it as a table and it's a subsystem.

The more important one: **I designed for the read pattern where the count is displayed, and I haven't thought carefully about whether the count is used for anything else.**

If like counts feed the ranking model — which they almost certainly do, since engagement is a ranking feature — then an approximate, eventually-consistent, occasionally-drifting count is being used as a model input. That's fine for display and potentially not fine for ranking, because systematic undercounting on the hottest posts could bias the ranker in a way nobody would notice.

I'd want to know whether ranking reads the display value or the durable record, and if it's the display value, whether the approximation error is uniform or correlated with post popularity. My suspicion is that it's correlated — the hottest posts have the most shards and the most aggregation lag, so they'd be undercounted most — which is exactly the kind of subtle systematic bias that's very hard to detect downstream.

That's a question I'd want answered before shipping, and I don't have an answer for it.

**INTERVIEWER:** Good. That's time.

> **▶ WHY THIS WORKS**
> The double-tap answer explains *why* the design is correct — the gate is atomic because it's a conditional insert rather than a read-then-write — rather than just asserting that idempotency is handled.
>
> The cross-region answer reaches for the CRDT merge property that was already established in the deep dive, and notes that regional divergence in a like count isn't a correctness problem. Reusing a structure introduced earlier for a new purpose is a coherence signal.
>
> The self-critique is the strongest part and it's genuinely novel: the candidate realizes their approximate count is probably a *ranking model input*, that the approximation error is likely correlated with post popularity, and that this would produce a systematic bias invisible to everyone downstream. Finding a second-order consequence of your own design in an adjacent system — and admitting you don't have the answer — is exactly what a real design review surfaces.

---

# What To Extract

## The clock

| Phase | Time | What happened |
|---|---|---|
| Scope | 0–5 | **Split count from membership**; derived precision from display format; asked about monotonicity before proposing the solution that breaks it |
| Estimate | 5–10 | Aggregate is trivial, **concentration is the problem**; single-key ceilings for three stores; membership has no hot key if indexed by user |
| API + model | 10–15 | Count rides with hydration; two membership indexes with opposite partition keys; reconciliation must respect monotonicity |
| HLD | 15–27 | Idempotency gate first; shards for writes, aggregate for reads; **why readers must not sum**; optimistic client update; promotion for 0.1% of posts |
| Deep dive | 27–42 | N derived from write rate; two causes of non-monotonicity treated differently; PN-counter for unlikes; **the bottleneck moves to the idempotency gate** |
| Wrap | 42–47 | Five failure modes; four metrics; the count-as-ranking-input bias |

## The four moves that carried it

**1. Splitting "like count" into two systems in the first ninety seconds.** A shared hot number and a per-user boolean have opposite requirements. Designing one system for both means membership inherits the hot-key problem for no reason.

**2. "A hot key is a concentration problem, not a throughput problem."** 999 idle nodes while one melts. Capacity cannot fix it — you must break the concentration.

**3. Why readers must not sum the shards.** 30,000 reads/sec × 100 shards = 3 million ops/sec. The aggregator makes shard count independent of read cost, which means N can grow for write scaling without any read penalty.

**4. The bottleneck moves under extreme load.** At 100K likes/sec the shards are fine — the aggregator's fan-in and the *idempotency gate* break first. Nobody expects the deduplication mechanism to be the constraint.

## The pushbacks

| Challenge | The move |
|---|---|
| "Does it need to be exact?" | Derived precision from the display format; noted precision matters inversely to magnitude |
| "Why store membership twice?" | Opposite partition keys for opposite questions; identified which is hot-path |
| "What if Redis and Cassandra disagree?" | Reconciliation, run rarely, correcting downward only beyond display precision |
| "Why not let readers sum shards?" | Arithmetic (100×), then the decoupling property that matters more |
| "How does the user see their own like instantly?" | Client-side optimistic update; generalized to a principle about self vs others |
| "What about unlikes?" | Separate monotonic increment/decrement sets — a PN-counter |
| "100,000 likes/sec?" | Shards hold; the aggregator and the idempotency gate break first |

## Why this problem is worth the reps

The machinery here appears everywhere: **view counts, retweet counts, reaction counts, vote counts, real-time leaderboards, rate limiting on a hot key, inventory counters during a flash sale.** All of them are "many writes to one logical value," and all of them are solved with some combination of sharding, aggregation, and accepting approximation.

The transferable insight is the ordering: **split the read-hot value from the write-hot value, let each scale independently, and connect them with a background aggregator.**

## Delivering this at L4

The core that reads as above band:

- Splitting count from membership in scoping
- Hot key = concentration, not throughput; capacity doesn't help
- Sharded counters with a random shard per write
- The aggregator, and why readers must not sum
- Idempotency via conditional insert, not read-then-write
- Membership indexed by user for the hot path
- Optimistic client update for self-visibility
- One honest self-critique

The L5 extras: deriving precision from the display format, adaptive N with increase-only, the two distinct causes of non-monotonicity and treating them differently, the PN-counter for unlikes, promotion for only 0.1% of posts, the bottleneck moving to the idempotency gate, and the count-as-ranking-input bias at the close.