⬤ blocking — you'll be lost without it · ◐ nuance — you'll follow but miss why it matters · ○ bonus — mentioned once, look up later

1. Estimation foundations
	Concept	Where it appears
⬤	DAU → QPS derivation (divide by 86,400, apply peak multiplier)	Phase 2, everywhere
⬤	Read:write ratio and why it drives architecture	"fanout inverts the ratio"
⬤	Write amplification — one logical write becoming N physical writes	100M posts → 20B writes
⬤	Percentiles (p50/p90/p99) and why averages mislead	"the average follower count is useless"
◐	Power-law / long-tail distributions	median 50 vs max 100M followers
◐	Burst vs sustained load — why you can't provision for a burst	the 7-minute pipeline block
2. Data storage
	Concept	Where it appears
⬤	Partition key / sharding — and that it determines your access patterns	posts by author, pools by user
⬤	Primary key vs clustering key	PRIMARY KEY (author_id, post_id)
⬤	Redis basics — key-value, TTL, and sorted sets specifically (ZADD, ZREVRANGE)	the feed pool
⬤	Cache vs source of truth — what's authoritative, what's derived	pool holds IDs, post store holds content
◐	Denormalization and its consistency cost	graph stored in both directions
◐	LSM trees (roughly: why append-heavy writes are fast)	why Cassandra fits
○	Wide-partition problems and time-bucketing	mega-account partitions
3. IDs and API design
	Concept	Where it appears
⬤	Snowflake IDs — timestamp + machine + sequence, and why time-ordering matters	score: postId in the sorted set
⬤	Cursor vs offset pagination	why the cursor pins the ranking
⬤	Idempotency keys	POST /v1/posts
◐	Why auto-increment and UUIDv4 both fail here	the ID section
4. The core mechanic — fanout

This is the heart of the problem. If you only learn one section, learn this one.

	Concept
⬤	Fanout on write (push) — precompute each user's feed at post time
⬤	Fanout on read (pull) — assemble at request time
⬤	The celebrity problem — why push breaks at the tail
⬤	Hybrid fanout — push for most, pull for the few, merge at read
◐	Amortization — "write once, read many" as the reason push wins for small fanout
◐	Classification thresholds and hysteresis (avoiding flapping at the boundary)
5. Async and messaging
	Concept	Where it appears
⬤	Sync vs async — what's on the request path vs after it	post returns 201, fanout happens later
⬤	Message queues / Kafka at the level of "producer, topic, consumer, lag"	the fanout pipeline
⬤	Eventual consistency — and when it's acceptable	tens-of-seconds freshness
◐	Consumer lag and backlog	the fanout cascade failure
◐	Outbox pattern — atomically writing state + event	graph write consistency
6. Correctness at the merge
	Concept
⬤	Deduplication — and dedup on root content, not the wrapper
⬤	Filter at read vs undo the write — the deletion tradeoff
◐	Bloom filters — probabilistic "definitely not present"
◐	Tail latency amplification — waiting on N parallel calls gives you p99 of the max
◐	Deadline budgets / best-effort degradation — rank with what returned
○	Sampling bias — the candidate-representation problem
7. Production concerns
	Concept
⬤	Thundering herd / cache stampede
⬤	Request coalescing — N concurrent misses → 1 backend fetch
⬤	Leading vs lagging indicators (fanout lag vs user complaints)
◐	Backpressure and bounded queues
◐	Reconciliation jobs for eventual convergence
8. Ranking branch — only if you take that deep dive
	Concept
⬤	Latency budget decomposition — the 200ms broken into parts
⬤	Two-stage funnel — cheap wide pass, expensive narrow pass
◐	Feature store and training-serving skew
◐	Position bias — training on clicks teaches you to copy the current ranker
○	Multi-objective optimization and engagement pathologies
○	Online A/B vs offline replay
Learning order

Don't go top to bottom. This sequence gets you reading the transcript fastest:

1.  Estimation (§1)              ← needed to follow Phase 2 at all
2.  Redis sorted sets (§2)       ← the feed pool is meaningless without it
3.  Push vs pull (§4)            ← THE core idea; read this twice
4.  Async + Kafka (§5)           ← why the post returns before fanout finishes
5.  Snowflake IDs, cursors (§3)
6.  Merge problems (§6)
7.  Production failures (§7)
8.  Ranking (§8)                 ← optional, separate topic

Steps 1–4 get you ~80% of the transcript. Steps 5–7 are the depth.

Self-test before re-reading

If you can answer these in one or two sentences each, you're ready:

Why does a post from a 100M-follower account break fanout-on-write?
What's in the feed pool — posts or post IDs? Why does that distinction matter?
Why does the post service return 201 before fanout completes?
Why does a ranked feed need a different cursor than a chronological one?
What breaks if you store the follow graph in only one direction?
If 20 parallel reads each have p99 = 80ms, why isn't the combined p99 80ms?

Anything you can't answer, go to that section first.