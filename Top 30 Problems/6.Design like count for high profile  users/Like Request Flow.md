# Like Request Flows — Normal Post vs Hot Post

Two users tap the heart at the same moment. One on an ordinary post, one on a celebrity post. Here is exactly what happens in each case.

---

## Setup

```
REQUEST A                          REQUEST B
Alice likes post 5522              Dave likes post 1001
"my cousin's lunch photo"          "celebrity announcement"
~100 likes total                   ~10,000,000 likes
~1 like/sec                        ~20,000 likes/sec
NOT sharded                        SHARDED (100 shards)
```

---

# REQUEST A — Normal Post

```
  Alice taps ♡ on post 5522
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 1.  PHONE — optimistic update                       │
  │     heart fills, count 99 → 100                     │
  │     rendered INSTANTLY, before any network call     │
  └─────────────────────────────────────────────────────┘
        │
        ▼   POST /v1/posts/5522/like
        │   Idempotency-Key: 7f3a...
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 2.  LIKE SERVICE                                    │
  │     authenticate → alice                            │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 3.  IDEMPOTENCY GATE                    ~4 ms       │
  │                                                     │
  │     INSERT INTO likes_by_user                       │
  │       (user_id, post_id, liked_at)                  │
  │     VALUES (alice, 5522, now())                     │
  │     IF NOT EXISTS;                                  │
  │                                                     │
  │     partition = alice → Node 47                     │
  │     ~500 rows. No contention. Nobody else           │
  │     touches Alice's partition.                      │
  │                                                     │
  │     applied = TRUE  → genuinely new like, continue  │
  │     applied = FALSE → already liked, STOP           │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 4.  IS THIS POST SHARDED?               ~0 ms       │
  │                                                     │
  │     lookup in local in-process cache                │
  │     post 5522 → NOT sharded                         │
  │                                                     │
  │     (this map is small — only the ~100K hot posts   │
  │      are in it — and refreshed every few seconds)   │
  └─────────────────────────────────────────────────────┘
        │
        ▼   ── NORMAL PATH ──
  ┌─────────────────────────────────────────────────────┐
  │ 5.  INCREMENT THE COUNT DIRECTLY        ~1 ms       │
  │                                                     │
  │     INCR likes:count:5522     → returns 100         │
  │                                                     │
  │     ONE key. Users write it, users read it.         │
  │     No shards. No aggregator. No lag.               │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 6.  EMIT EVENT (fire and forget)        ~0 ms       │
  │     Kafka: {alice, 5522, liked}                     │
  │       → likes_by_post (for the liker list)          │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 7.  RESPOND                                         │
  │     200 { liked: true, count: 100 }   ← EXACT       │
  └─────────────────────────────────────────────────────┘

  TOTAL: ~6 ms.  Count is exact and current.
```

### Someone else reads that post's count

```
  Bob's feed hydrates post 5522
        │
        ▼
  GET likes:count:5522   →  100
        │
        └─▶ the SAME key Alice just incremented.
            Zero staleness.
```

---

# REQUEST B — Hot Post

Steps 1–4 are **identical**. The divergence is at step 5.

```
  Dave taps ♡ on post 1001
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 1.  PHONE — optimistic update                       │
  │     heart fills, "1.2M" → "1.2M"                    │
  │     (rounding means his own +1 is invisible —        │
  │      but the filled heart is what he checks)         │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 2.  LIKE SERVICE            ← identical              │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 3.  IDEMPOTENCY GATE        ← identical    ~4 ms    │
  │                                                     │
  │     INSERT INTO likes_by_user                       │
  │     VALUES (dave, 1001, now()) IF NOT EXISTS;       │
  │                                                     │
  │     partition = dave → Node 91                      │
  │                                                     │
  │  ★ THE KEY POINT ★                                  │
  │    10 MILLION people are liking this post right     │
  │    now. Each write goes to THEIR OWN partition.     │
  │    dave→Node 91, erin→Node 12, frank→Node 3...      │
  │    Zero contention despite 20,000 writes/sec.       │
  │                                                     │
  │    This is why membership is indexed BY USER.       │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 4.  IS THIS POST SHARDED?               ~0 ms       │
  │     post 1001 → SHARDED, N = 100                    │
  └─────────────────────────────────────────────────────┘
        │
        ▼   ── HOT PATH ──
  ┌─────────────────────────────────────────────────────┐
  │ 5.  INCREMENT A RANDOM SHARD            ~1 ms       │
  │                                                     │
  │     r = random(0, 99)          → 47                 │
  │     INCR likes:shard:1001:47                        │
  │                                                     │
  │     Dave  → shard 47 → Node 8                       │
  │     Erin  → shard 3  → Node 15                      │
  │     Frank → shard 88 → Node 2                       │
  │                                                     │
  │     20,000/sec ÷ 100 = 200/sec per key.             │
  │                                                     │
  │  ★ We do NOT read the total here. ★                 │
  │    Summing 100 keys on the write path would         │
  │    defeat the entire purpose.                       │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 6.  EMIT EVENT              ← identical             │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │ 7.  RESPOND                                         │
  │     200 { liked: true, count: 1234567 }             │
  │                              ↑                      │
  │     read from likes:count:1001 — the whiteboard.    │
  │     Up to 1 second stale. Does NOT include Dave's   │
  │     own like yet — which is why step 1 exists.      │
  └─────────────────────────────────────────────────────┘

  TOTAL: ~6 ms.  Count is approximate and ~1s stale.
```

### Meanwhile, running independently

```
  ┌─────────────────────────────────────────────────────┐
  │  AGGREGATOR — every 1 second, for hot posts only    │
  │                                                     │
  │    MGET likes:shard:1001:0 ... :99      100 reads   │
  │    sum = 1,234,891                                  │
  │    SET likes:count:1001 = 1,234,891      1 write    │
  │         (only if larger — monotonic guard)          │
  └─────────────────────────────────────────────────────┘

  Runs on a schedule. Nobody's request waits for it.
```

### Someone else reads that post's count

```
  30,000 people/sec hydrate post 1001
        │
        ▼
  GET likes:count:1001   →  1,234,891
        │
        └─▶ ONE key, read-mostly (aggregator writes 1/sec)
            → replicate freely across nodes
            → cache in app tier with 1s TTL
            → Redis actually sees ~100 reads/sec
```

---

## Side by side

| Step | Normal (5522) | Hot (1001) |
|---|---|---|
| 1. Optimistic UI | identical | identical |
| 2. Auth | identical | identical |
| 3. Idempotency gate | `likes_by_user`, part = alice | **identical** — part = dave |
| 4. Sharded? | no | yes, N=100 |
| **5. Counter** | `INCR likes:count:5522` | `INCR likes:shard:1001:{rand}` |
| 6. Kafka event | identical | identical |
| 7. Response count | exact, live | ~1s stale, ±small |
| Aggregator | none | 100 reads → 1 write, per second |
| Read path | `GET likes:count:5522` | `GET likes:count:1001` |

---

## The three things worth noticing

**Only one step differs.** Step 5. Everything else — auth, idempotency, event emission, response shape — is the same code. The complexity is one branch, not two systems.

**The read path is byte-identical.** Both do `GET likes:count:{postId}`. A reader never knows or cares whether a post is sharded. What changed is *who writes that key*: users directly for normal posts, the aggregator for hot ones.

**The idempotency gate never needed sharding.** 20,000 concurrent writes for one post, and they're spread across 20,000 different user partitions. That was decided back in scoping when the count was split from membership — and it's why the hardest-looking part of the write path is the part that scales for free.

---

## If asked "what if a post is promoted mid-request?"

Dave's request reads the sharded-map at step 4 and sees "not sharded," so it writes `likes:count:1001` directly. A microsecond later the post is promoted.

That write isn't lost — **promotion copies the existing count key into shard 0** rather than deleting it:

```
likes:shard:1001:0 = <whatever likes:count:1001 held>
```

Anything still writing to the old key is picked up on the next aggregation pass. The transition is additive, not a cutover, which is what makes it safe without coordination.