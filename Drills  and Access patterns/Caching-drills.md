# Day 3 Drills — Week 3: Caching & Read-Heavy Architectures

> **15-day revision plan · Day 3 drills**
> Four drills, ~40 minutes total. These map onto what interviewers actually probe.
> Do them with a timer. Speak aloud where the drill says aloud.

---

## Drill 1 — Hit ratio arithmetic (10 min)

The single most important applied skill in this week. Work these **without a calculator**.

### Part A — URL shortener

```
GIVEN
  Read traffic            350,000 reads/sec
  Total items             180 billion
  Hot set (1% of 30 days) 30M items × 300 B  =  ~9 GB

COMPUTE
  a) Backend reads/sec at 99% hit rate
  b) Backend reads/sec at 90% hit rate
  c) Backend reads/sec at 50% hit rate
  d) Redis nodes for the hot set, RF=2, 64 GB usable/node
```

Then the reasoning question:

> **Does the architecture change between (a) and (b)?**

<details>
<summary>Answers</summary>

```
a) 350,000 × 0.01  =   3,500 reads/sec
b) 350,000 × 0.10  =  35,000 reads/sec
c) 350,000 × 0.50  = 175,000 reads/sec
d) 9 GB × 2 = 18 GB, + ~40% headroom ≈ 25 GB
   → 6 nodes (3 primary + 3 replica), tiny cluster
```

**The architecture does not change between (a) and (b).** 3,500 vs 35,000 reads/sec is a 10× difference and *neither* breaks a hash-partitioned store.

That robustness is the answer to give under challenge: the design survives being wrong about the hit ratio by an order of magnitude. Only (c) starts to matter, and a 50% hit rate would mean the working-set assumption was wrong by two orders of magnitude.

</details>

### Part B — News feed pool

```
GIVEN
  Active users            500M
  Pool size               1,000 postIds
  Per entry               16 B (8 member + 8 score)

COMPUTE
  a) Total data
  b) With RF=2 and 40% headroom
  c) Nodes at 64 GB usable
  d) Ops/sec per node, given ~225K total cluster ops/sec
```

<details>
<summary>Answers</summary>

```
a) 500M × 1,000 × 16 B  =  8 TB
   (with Redis sorted-set overhead ~2×, call it 16 KB/user
    → same 8 TB figure)
b) 8 TB × 2 = 16 TB, +40% ≈ 22 TB
c) 22 TB ÷ 64 GB ≈ 340 nodes (170 primary + 170 replica)
d) 225,000 ÷ 170 ≈ 1,300 ops/sec per node
```

**The finding: this cluster is memory-bound, not throughput-bound.** Each node runs at ~1.3% of its ~100K ops/sec capacity while nearly full on RAM.

You're buying machines for their memory and the CPU comes along unused — which means **pool size is the cost lever**, not Redis performance. Halve the pool, halve the cluster.

</details>

---

## Drill 2 — Recall, no notes (10 min)

Write these out cold, then check.

### The five patterns

Name each, and for each say **who talks to the database**:

```
cache-aside  ·  read-through  ·  write-through
write-behind  ·  refresh-ahead
```

<details>
<summary>Answers</summary>

| Pattern | Who talks to the DB |
|---|---|
| **Cache-aside** | The *application*. Check cache, miss → app reads DB, app populates cache. |
| **Read-through** | The *cache*. App only talks to cache; cache fetches from DB on miss. |
| **Write-through** | The *cache*, synchronously. Write goes to cache, cache writes DB, then acks. |
| **Write-behind** | The *cache*, asynchronously. Acks immediately, flushes to DB later. Fast, risks loss. |
| **Refresh-ahead** | The *cache*, proactively. Refreshes hot entries before they expire. |

Cache-aside is the default in practice — the app owns the logic, nothing magic happens, and failures are visible.

</details>

### Eviction policies

Name three, plus the weakness of each:

<details>
<summary>Answers</summary>

```
LRU   (least recently used)
  weakness: NOT SCAN RESISTANT
  one large sequential scan evicts your entire hot set,
  because every scanned item looks "recently used"

LFU   (least frequently used)
  weakness: STALE POPULARITY
  something popular last month keeps its high count and
  won't evict, even though nobody wants it now
  (needs frequency decay to fix)

W-TinyLFU
  window LRU for recency + frequency sketch for popularity
  admits a new item only if it's likely more valuable
  than the eviction candidate
  → gets scan resistance AND frequency awareness
```

**The trap:** if you wrote "TTL" as an eviction policy, that's the confusion. TTL is *expiry* — time-based removal. Eviction is what happens when **memory fills**. Different mechanisms, often conflated.

</details>

### Stampede mitigations

Four of them:

<details>
<summary>Answers</summary>

```
1. Request coalescing / single-flight
   N concurrent misses on one key → ONE backend read,
   the rest wait on it

2. Probabilistic early expiry
   refresh before the TTL fires, with randomness, so
   the key never expires at the exact moment everyone
   needs it

3. Jittered TTLs
   never give many keys the same expiry timestamp —
   otherwise they all miss simultaneously

4. Negative caching
   cache "does not exist" for a short TTL, so scanning
   for random keys doesn't generate a DB read each time
```

</details>

### Invalidation strategies

Four, ordered by freshness vs complexity:

<details>
<summary>Answers</summary>

```
1. TTL only                simplest, staleness = TTL
2. TTL + explicit delete   invalidate on write
3. Write-through           cache updated with the DB
4. Pub/sub invalidation    push invalidations to all
                           nodes and edges — needed when
                           multiple layers cache
```

</details>

### Hot key mitigations

Three, and which one **doesn't** work:

<details>
<summary>Answers</summary>

```
WORKS
  · local in-process cache in front (~50 ns, no network)
  · replicate the key under N variants, read a random one
  · increase replica count — for READ-hot keys only

DOESN'T WORK
  · adding nodes to the cluster
    the key lives on ONE node; 999 others stay idle
```

And the critical distinction: **replication scales reads, never writes.** A read-hot key can be replicated freely. A write-hot key cannot, because every replica needs every write.

That's the Like Count insight — the display key is read-mostly so you replicate it; the counter shards are write-hot so you *shard* them instead.

</details>

---

## Drill 3 — Diagnose the symptom (10 min)

The most interview-relevant drill. These come as follow-ups, not main questions.

For each, name the likely cause and the fix, in two sentences.

1. **"Cache hit ratio dropped from 99% to 60% overnight, no traffic change."**

2. **"p99 latency spikes every 5 minutes, exactly."**

3. **"One Redis node is at 95% CPU; the other 19 are at 4%."**

4. **"Costs tripled with no traffic increase and no errors."**

5. **"Users report seeing deleted content for about an hour."**

6. **"The database falls over at exactly the moment a post goes viral."**

7. **"After a deploy, the cache is cold and the database can't take the load."**

<details>
<summary>Answers</summary>

**1.** Working set grew past cache capacity, **or** a key-structure change broke lookups so everything misses. Check eviction rate and key cardinality — rising evictions means capacity; flat evictions with falling hits means the keys changed.

**2.** Synchronized expiry or synchronized compaction. Many keys given the same TTL at deploy time all expire together. Add **jitter** to TTLs and to any periodic background job.

**3.** Hot key. Add a local in-process cache in front of it, or replicate the key under N suffixed variants and read a random one. Adding cluster nodes does nothing.

**4.** The silent regression — something broke cache reuse. In the LLM case it's dynamic content injected early in the prompt killing prefix caching; in a normal cache it's a key-format change or a TTL drop. **Monitor hit ratio as a first-class metric**, because nothing fails when this happens.

**5.** TTL-only invalidation with a long TTL. Needs **explicit invalidation on delete**, pushed to every layer including edge caches. This is caching impairing revocation.

**6.** Cache stampede. The viral post isn't cached yet, thousands of concurrent requests all miss and hit the same DB row. Request coalescing plus probabilistic early expiry.

**7.** Cold-start thundering herd. Warm the cache before accepting traffic, or ramp traffic gradually so the cache fills as load rises.

</details>

**Target: 5 of 7.** Below that, re-read the concept material before Day 4.

---

## Drill 4 — The revocation tradeoff (10 min)

The thread running through four different transcripts. For each scenario, decide **TTL vs explicit invalidation**, and state what caching costs you.

| Scenario | Your call |
|---|---|
| URL shortener: 301 vs 302 for the redirect | ? |
| Blocked malicious link cached at 200 edge PoPs | ? |
| Feed pool entry for a post the author just deleted | ? |
| Notification preferences cached for the filter stage | ? |
| Post content cache when the author edits it | ? |

<details>
<summary>Answers</summary>

**301 vs 302 → use 302.** A 301 is cached by the browser, often indefinitely. You **cannot recall it** — every browser that has seen the link keeps redirecting to it regardless of what your servers say. That turns an apparent performance optimization into an irreversible safety hole, because you can never kill a malicious link.

**Blocked link at edge PoPs → explicit invalidation, short TTL as backstop.** Waiting for a TTL means the malicious link keeps serving from stale edges. Push invalidation on block, and keep edge TTLs in minutes rather than hours precisely so this path is bounded.

**Deleted post in the feed pool → filter at read, don't un-fanout.** Removing it from 500,000 pools costs as much as writing it. A Bloom filter of recently-deleted IDs at the feed service answers "definitely not deleted" for almost everything cheaply.

**Notification preferences → short TTL, and fail closed.** This one is **security-relevant**: a stale friend set means someone sees content after being unfriended. If the preference fetch fails, serve nothing rather than serving unfiltered — the opposite of the usual availability instinct.

**Post content on edit → explicit `DEL` plus short TTL.** Deletes especially, because serving deleted content is a real problem rather than a cosmetic one.

</details>

### The general question

> **What does caching always cost you, in every one of these cases?**

<details>
<summary>The sentence to have ready</summary>

**Caching improves performance and impairs revocation.** So anything with a safety or privacy dimension needs explicit invalidation rather than TTL expiry — and anything you literally cannot invalidate (a 301 in a browser cache) should not be cached at all.

</details>

---

## Self-check before Day 4

- [ ] I can compute a hit ratio's effect on backend load in my head
- [ ] I know why the news feed cache is memory-bound, not throughput-bound
- [ ] I can name all five patterns and who talks to the DB in each
- [ ] I know LRU's weakness (scan resistance) and what W-TinyLFU adds
- [ ] I can distinguish **eviction** from **expiry**
- [ ] I can give four stampede mitigations
- [ ] I diagnosed at least 5 of the 7 symptoms in Drill 3
- [ ] I can state the caching/revocation tradeoff in one sentence
- [ ] I know which keys can be replicated (read-mostly) and which cannot (write-hot)

**Miss three or more → redo Drills 2 and 3 before Day 4.**

That last checkbox is worth dwelling on. It's the Like Count insight and it generalizes:

> **Replication scales reads, never writes.** A read-hot key gets replicas. A write-hot key gets sharded. Applying the wrong one is the most common hot-key mistake.

---

**Tomorrow — Day 4: Week 4, Messaging & Async Decomposition.** Queues vs logs, the outbox pattern, delivery guarantees, DLQs, and the dual-write bug.