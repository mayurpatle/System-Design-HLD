# Like Count — Alternate Deep Dive: The Membership Store

**Drop-in replacement for Phase 5 (0:27 – 0:42)** of the Like Count transcript, for when the interviewer picks membership instead of the sharded counter.

> **Why this branch is different:** the counter deep dive is about *contention* — many writes to one place. This one is about *volume and lifecycle* — 58 terabytes a year that grows forever, indexed two ways, with a deletion problem nobody wants to talk about. The counter branch is cleverer; this one is more like the work you actually do.

---

**CANDIDATE:** That's the skeleton. The two areas with real depth are the sharded counter mechanics and the **membership store** — 58 terabytes a year, two indexes, and a lifecycle problem. Preference?

**INTERVIEWER:** Membership. Start with the storage.

---

## Phase 5 — Deep Dive: The Membership Store (0:27 – 0:42)

**CANDIDATE:** Good — this is the part that's less clever than the counter and more likely to be what actually hurts in production.

**Level one: the shape of the data.**

```
  10B likes/day, retained indefinitely

  Per like, stored TWICE:
    likes_by_user:  (user_id, post_id, liked_at)     ~28 B + overhead
    likes_by_post:  (post_id, bucket, liked_at, user_id)  ~36 B + overhead

  Cassandra row overhead is significant for narrow rows —
  timestamps per cell, clustering key repetition.
  Call it ~60 B per row after overhead, ~120 B for both copies.

  10B × 120 B  ≈  1.2 TB/day
                 ≈ 440 TB/year

  With RF=3     ≈ 1.3 PB/year of physical storage
```

**CANDIDATE:** I want to correct my own estimate from earlier — I said 58 terabytes a year in Phase 2, and that was the raw tuple size for one index without replication or row overhead. The realistic figure is closer to a petabyte a year of physical storage.

That changes the character of the problem. At 58 TB you shrug. At 1.3 PB/year, growing forever, storage cost becomes a first-order design concern and lifecycle is not optional.

**INTERVIEWER:** Does it all need to be kept?

**CANDIDATE:** That's exactly the right question, and the honest answer is that the two indexes have completely different retention arguments.

```
  likes_by_user     "did Alice like post X?"
    Needed as long as the post can appear in a feed.
    A five-year-old post can resurface — someone links it,
    it's on a profile, it's in search.
    If the row is gone, Alice sees an unfilled heart on a
    post she liked. Visible, and users notice.
    → effectively PERMANENT

  likes_by_post     "who liked post X?"
    Needed for the liker list screen.
    Nobody scrolls to liker #4,000,000.
    → the TAIL is dead weight
```

So I'd treat them differently rather than applying one policy to both.

For `likes_by_post`, cap what's retained per post — keep the most recent N thousand likers plus, separately, the ones the viewer follows, since "liked by your friend Bob and 2.3M others" is the actual product surface. Everything else is a row nobody will ever read.

That's a meaningful reduction: for a 10-million-like post, retaining 10,000 gives you 99.9% savings on the dominant contributor to storage.

For `likes_by_user`, I'd keep it, but I'd tier it — recent likes on fast storage, older ones on cheaper storage with slower reads. The access distribution is heavily recency-skewed even though the retention requirement is permanent.

**Level two: the two indexes, and what each one costs.**

**CANDIDATE:** Let me be precise about why both exist, because it's the design decision the whole store rests on.

```
  THE HOT QUERY, in full:

    Feed renders 20 posts.
    For each: "has this viewer liked it?"

    likes_by_user:
      SELECT post_id FROM likes_by_user
       WHERE user_id = alice
         AND post_id IN (20 ids);

      → 1 partition, ~500 rows, clustering key locates
        the 20 directly
      → ONE round trip, ~2 ms

    likes_by_post (if it were the only index):
      20 separate queries, each into a partition that
      may hold 10M rows, several of them hot keys
      → catastrophic
```

Frequency ratio is what settles it:

```
  "did I like this?"    175K feed loads/sec × 20 posts
                        = 3.5 MILLION checks/sec

  "who liked this?"     liker-list screen taps
                        maybe 5K/sec
                        → 700× less frequent
```

The hot query gets the good partition key. The cold query is allowed to be expensive.

**INTERVIEWER:** Why not derive one from the other instead of writing twice?

**CANDIDATE:** You can't, in either direction, and it's worth showing why.

```
  Derive likes_by_post FROM likes_by_user?
    "who liked post 1001" → scan all 500M user partitions
    → full cluster scan per query

  Derive likes_by_user FROM likes_by_post?
    "did alice like these 20" → read 20 partitions,
    several with 10M rows, searching for one user
    → and this happens 3.5M times/sec
```

Neither derivation is viable, so both indexes exist. The cost is a second write per like and a consistency window between them.

I'd note that Cassandra's built-in secondary index doesn't solve this either — it's a *local* index, partitioned with the base table, so "who liked post X" would fan out to every node and merge. A second table is a globally-partitioned index that knows exactly which nodes to ask. That's why manual denormalization is standard practice here rather than a workaround.

**Level three: bucketing, and the write path's consistency.**

**CANDIDATE:** The bucket in `likes_by_post` is doing real work and the choice of bucketing function matters.

```
  PRIMARY KEY ((post_id, bucket), liked_at, user_id)

  Option A: bucket = hash(user_id) % 100
    + writes distribute perfectly and immediately
    + no coordination, no state
    − liker list requires querying ALL 100 buckets
      and merging by liked_at
    − buckets exist even for posts with 3 likes

  Option B: bucket = floor(like_sequence / 10000)
    + reading recent likers touches only the newest bucket
    + posts with few likes have exactly one bucket
    − requires a counter to know the sequence
      → reintroduces a hot key. Self-defeating.

  Option C: bucket = time window, e.g. hour_since_post
    + recent likers = most recent bucket, single partition
    + naturally sparse for quiet posts
    − a viral hour puts everything in ONE bucket
      → hot partition exactly when it matters most
```

I'd choose **A**, hash-based, and accept the fan-out on the cold path.

The reasoning is that the cold path is 700× less frequent, so paying 100 parallel reads there is cheap, while options B and C both reintroduce concentration on the *write* path — which is the thing this entire design exists to avoid.

I'd make the bucket count adaptive though: posts start with a single bucket, and bucket count increases as the post grows. A post with 100 likes shouldn't have 100 partitions holding one row each.

**INTERVIEWER:** How do the two tables stay consistent?

**CANDIDATE:** They don't, strictly — and I'd design for convergence rather than atomicity.

The write is:

```
  1. INSERT likes_by_user ... IF NOT EXISTS    ← synchronous, gates
  2. INCR counter shard                        ← synchronous
  3. Kafka event → INSERT likes_by_post        ← asynchronous
```

Step 3 can fail or lag. The failure mode is that Alice's like is recorded in her own index and in the count, but she doesn't yet appear in the post's liker list.

That's genuinely low-severity — nobody is watching the liker list waiting for their name. So I'd accept eventual consistency, with three things making it safe:

**The gate is step 1, not step 3.** Since `likes_by_user` is both the idempotency check and the hot-path read, correctness of the user-visible state depends only on the synchronous write. Step 3 failing degrades a secondary screen, not the primary experience.

**The event carries everything needed to retry.** Kafka gives at-least-once delivery, and the insert is naturally idempotent — same `(post_id, bucket, liked_at, user_id)` written twice produces one row. So retries are free.

**A reconciliation job as backstop.** Periodically, for high-value posts, compare row counts between the two tables and repair gaps. Runs rarely, off-peak.

**INTERVIEWER:** What happens when a user is deleted?

**CANDIDATE:** This is the problem I'd flag as the genuinely hard one, and I don't think it has a clean answer.

```
  Alice deletes her account.
  She has liked ~5,000 posts over 8 years.

  likes_by_user[alice]
    → one partition, one delete. Trivial.

  likes_by_post
    → 5,000 rows, scattered across 5,000 different
      posts, in 5,000 different partitions,
      each identified by (post_id, hash(alice)%N, liked_at, alice)
```

The second one is the problem. To delete them you need to know **which posts Alice liked** — which you do, from her user-index partition — but then it's 5,000 individual deletes across the cluster.

For one user that's fine. The issue is scale and legal timelines:

```
  Deletions at, say, 100K users/day
    × ~5,000 likes each
    = 500 MILLION deletes/day
    ≈ 6,000 deletes/sec, sustained, forever
```

That's a permanent background write load comparable to a meaningful fraction of the live traffic, purely for deletion.

And in Cassandra, deletes are **tombstones** — they're writes, not removals. So you're adding rows to delete rows, and those tombstones have to survive `gc_grace_seconds` before compaction can reclaim anything. A high-churn deletion workload can make read performance worse rather than better.

Three approaches, none great:

**Delete eagerly, throttled.** Read the user's like list, enqueue 5,000 deletes, process at a controlled rate over hours or days. Correct, slow, and it adds sustained write and tombstone load.

**Delete the user-index only, filter at read.** The `likes_by_post` rows remain but reference a deleted user, and the liker-list query filters them out by joining against user status. Cheap to execute, but it means personal data physically persists, which may not satisfy a deletion obligation.

**Crypto-shred.** Store `user_id` in `likes_by_post` encrypted with a per-user key. Delete the key and the rows become unreadable immediately without touching them. Instant, no write amplification, no tombstones — and it changes `likes_by_post` from a plaintext index to one where you can't query by user without the key, which is a real functional cost.

I'd lean toward the third for the compliance property, combined with lazy physical cleanup during normal compaction. But I want to be honest that this is the part of the design I'm least confident about, and it's the kind of thing that gets decided by a legal requirement rather than an engineering preference.

> **▶ WHY THIS WORKS**
> **Correcting the Phase 2 estimate** rather than letting it stand is the opening move, and it changes the problem's character — 58 TB is a shrug, 1.3 PB/year is a design constraint. Revising your own number when you have better information reads as accuracy rather than inconsistency.
>
> **Different retention policies for the two indexes** is the insight most candidates miss. They apply one policy to "likes." The user index is effectively permanent because an unfilled heart on a post you liked is user-visible; the post index has a dead tail nobody will ever scroll to. Same data, opposite conclusions.
>
> **Showing that neither index can be derived from the other** pre-empts the obvious "why write twice" objection with a concrete failure for each direction, then rules out Cassandra's secondary index with the local-vs-global distinction.
>
> **Three bucketing options, each rejected for a specific reason** — and both rejected options fail the same way, by reintroducing write concentration. Recognizing that two different-looking mistakes are the same mistake is a good sign.
>
> **The deletion problem is the strongest part.** It's the least glamorous corner of the design and the candidate treats it as the hardest: 6,000 sustained deletes/sec forever, Cassandra tombstones making deletion a *write* problem, three approaches with honest tradeoffs, and an admission that it's the part they're least confident about and that legal will probably decide it. Naming the unglamorous thing as the hard thing is unusual and credible.

---

## Comparison to the counter branch

| | Counter deep dive | Membership deep dive |
|---|---|---|
| Core problem | Write **contention** on one key | Write **volume** and lifecycle |
| Hard part | Sharding, aggregation, monotonicity | Two indexes, retention, deletion |
| Feels like | A puzzle with a clever answer | Ordinary work done carefully |
| Standout | Bottleneck moves to the idempotency gate | Deletion is a sustained write problem forever |
| Risk | Over-indexing on the clever bit | Sounding like a storage audit |

**Pick the counter branch** if the interviewer seems interested in the distributed-systems puzzle, or if they've asked about hot keys.

**Pick membership** if they've probed storage, cost, data lifecycle, or privacy. It's also the better branch if you want to talk about GDPR-style deletion, since it arises naturally rather than being bolted on.

## The L4 core of this branch

- Two indexes, opposite partition keys, and why neither derives from the other
- The frequency ratio (3.5M/sec vs 5K/sec) as the justification
- Bucketing to keep the post-index partitions bounded
- Eventual consistency between the tables, with the gate on the synchronous write
- Deletion is expensive because rows are scattered across many partitions
- One honest self-critique

The L5 extras: correcting the storage estimate, different retention policies per index, the three bucketing options with both rejections failing identically, Cassandra tombstones making deletion a write-amplification problem, crypto-shredding, and admitting deletion is the least-confident part of the design.
