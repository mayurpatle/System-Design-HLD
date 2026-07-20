# Apache Kafka Design Documentation — Detailed Summary

**Source:** kafka.apache.org/documentation/#design (the official "Design" section of the Kafka docs, current as of Kafka 4.3). This is the primary-source rationale document written by the Kafka team — it explains *why* Kafka is built the way it is, with special depth on **storage (Persistence + Efficiency)**, which is the section you flagged.

---

## 1. Motivation

Kafka was designed to be a **unified platform for all of a large company's real-time data feeds**. That forced a demanding set of simultaneous requirements: very high throughput (log aggregation, clickstreams), graceful handling of large backlogs (periodic bulk loads from offline systems), low latency (traditional messaging), partitioned distributed processing to create derived feeds, and fault tolerance when feeding downstream serving systems. The result, in the authors' words, is a system that resembles a **database commit log more than a traditional message queue**.

---

## 2. Persistence (the storage core)

### 2.1 "Don't fear the filesystem!"

The founding storage insight: **disks are not slow if you use them sequentially**.

- Sequential vs random I/O differ enormously — on a six-disk 7200rpm RAID setup, linear writes run around 600 MB/s while random writes manage on the order of 100 KB/s: a **~6000x gap**. Sequential access is so OS-optimized (read-ahead prefetching, write-behind coalescing) that it can even beat random *memory* access in some measurements.
- Modern operating systems aggressively use all free RAM as **page cache**, and every read/write goes through it anyway. Maintaining a separate in-process cache means storing everything twice.
- The **JVM makes in-heap caching worse**: object overhead often doubles memory usage, and GC degrades as heap data grows.

So Kafka **inverts the traditional design**: instead of caching in application memory and flushing to disk under pressure, all data is immediately written to the filesystem log — which really means into the kernel's page cache — without forcing an fsync per write. Benefits: up to ~28–30 GB of effective cache on a 32 GB machine with zero GC cost, a cache that **survives process restarts** (an in-process 10 GB cache could take ~10 minutes to rebuild), and cache-coherency logic delegated to the OS, which does it better than application code. (The docs credit the Varnish architecture notes for this page-cache-centric philosophy.)

Crucially, **durability comes from replication, not fsync** — more on that under Replication.

### 2.2 "Constant Time Suffices"

Traditional message brokers keep per-consumer queues with **BTree** indexes for message metadata. BTrees are flexible but O(log N), and on disk that's not "practically constant": each seek costs ~10 ms, disks do one seek at a time, and performance of tree structures often degrades **superlinearly** as data outgrows cache.

Kafka instead builds the persistent queue on **simple file appends and sequential reads**: all operations are **O(1)**, and reads don't block writes or each other. Performance becomes **independent of data size** — the same on 50 KB as on 50 TB — which means cheap, high-capacity SATA drives work fine.

The killer consequence: because retaining data costs nothing in performance, Kafka **doesn't delete messages on consumption**. It retains them for a configurable window (say, a week), which is what enables multiple independent consumers, replay, and batch loads — features queues normally can't offer.

---

## 3. Efficiency

With sequential I/O solved, two remaining bottlenecks are attacked:

**Too many small I/Os** → the protocol is built around a **"message set" (batch)** abstraction. Producers send grouped messages, the broker appends whole chunks to the log, consumers fetch large linear chunks. Batching amortizes network round trips and turns bursty random writes into linear flows — an orders-of-magnitude speedup.

**Excessive byte copying** → one **standardized binary format shared by producer, broker, and consumer**, so the on-disk log is byte-identical to what travels over the network. This enables **zero-copy** via the Linux `sendfile` syscall: the normal file→socket path involves four copies and two syscalls (disk→page cache→user buffer→socket buffer→NIC); `sendfile` sends straight from page cache to the NIC. With many consumers on a topic, data is copied into page cache **once** and served repeatedly at near network-line rate. A fully caught-up cluster shows essentially **zero disk read activity** — everything is served from cache. (Caveat: `sendfile` is bypassed when TLS is enabled, since SSL libraries run in user space.)

**End-to-end batch compression** → compressing messages individually wastes the redundancy *between* messages (repeated JSON field names, user agents, etc.). Kafka compresses **whole batches** at the producer; the broker validates but stores the batch **still compressed**, and it travels to the consumer compressed, where it's finally decompressed. Supported codecs: GZIP, Snappy, LZ4, ZStandard. This matters most when the bottleneck is network bandwidth (e.g., cross-datacenter pipelines).

---

## 4. The Producer

- **Load balancing:** producers send **directly to the leader broker** of a partition — no routing tier. Any broker answers metadata requests about which brokers are alive and who leads each partition. The client chooses the partition: random, or **semantic partitioning by key hash** (e.g., all events for a user ID land in one partition, enabling consumers to make locality/ordering assumptions).
- **Asynchronous send:** the producer accumulates records in memory and sends larger batches, bounded by a byte size and a max linger time (e.g., 64 KB or 10 ms) — an explicit knob trading a little latency for a lot of throughput.

## 5. The Consumer

- **Pull, not push:** consumers issue fetch requests with an explicit offset and receive a chunk of log from that position. Push systems (Scribe, Flume) struggle with diverse consumers — the broker dictates rate and can overwhelm a slow consumer; with pull, a slow consumer just **falls behind and catches up**. Pull also enables optimal batching (grab everything available past your position) without guessing what the consumer can absorb. The naive-pull busy-wait problem is solved with **long polling** (block until data or a size threshold arrives). They considered a fully pull-based, store-and-forward design (producers persist locally) and rejected it: with thousands of producers, thousands of extra disks make things less reliable and miserable to operate.
- **Consumer position — the sneaky-hard problem:** most brokers track per-message consumed/acknowledged state — meaning locks, multiple states per message, and handling messages sent-but-never-acked; if the broker marks messages consumed on delivery, consumer crashes lose data. Kafka sidesteps all of it: since each partition is consumed by exactly one group member, a consumer's state is **one integer per partition** (the next offset), cheaply checkpointed. Side benefit: consumers can deliberately **rewind and re-consume** — "violates the contract of a queue," but essential in practice (e.g., replay after fixing a bug).
- **Offline data load:** long retention means batch systems (Hadoop, warehouses) can bulk-load periodically, parallelized per node/topic/partition, and failed tasks restart from their old offset with no duplicate danger.
- **Static membership:** persistent `group.instance.id`s keep group membership stable across restarts/deploys, avoiding mass rebalances that force big stateful apps to rebuild local state; duplicate IDs are fenced with an exception.

## 6. Message Delivery Semantics

The three classic contracts: **at-most-once** (may lose, never redeliver), **at-least-once** (never lose, may redeliver), **exactly-once**. The problem splits into producer-side durability and consumer-side processing.

- **Producer side:** a message is *committed* once all ISR replicas have it. On a network error the producer can't know if the write landed (like inserting into a DB with an autogenerated key). Pre-0.11 the only option was resend → at-least-once with possible duplicates. Since 0.11, the **idempotent producer** (broker-assigned producer ID + per-message sequence numbers, deduplicated broker-side) makes retries safe, and **transactions** allow atomic writes across multiple partitions. Producers also pick their durability latency point via `acks` (async / leader-only / committed).
- **Consumer side:** the order of "save position" vs "process messages" decides the semantics — position-first gives at-most-once; process-first gives at-least-once (idempotent updates absorb duplicates when messages have primary keys).
- **Exactly-once:** for Kafka→Kafka processing (the Kafka Streams pattern), commit the **offsets in the same transaction as the output records**; with `read_committed` consumers, aborted transactions' output is invisible. For external systems, the trick is storing the offset **in the same store as the output** (e.g., a Connect HDFS sink writing data + offsets atomically) — simpler and more general than two-phase commit. Default remains at-least-once.

## 7. Using Transactions (directly, without Streams)

Only the **producer** is transactional in Kafka, but it can transactionally update the consumer's committed offsets — that's what yields end-to-end exactly-once. The recipe mirrors Streams: one consumer per partition via group assignment; the producer wraps output records + offset commits in one transaction; use one producer per consumer instance to survive rebalances cleanly; set `transactional.id` (which also **aborts in-flight transactions of a previous incarnation** — zombie fencing) and consume with `isolation.level=read_committed`, `enable.auto.commit=false`. On abort, the application must explicitly rewind the consumer position and reprocess. The docs also standardize a producer exception taxonomy — retriable (handled internally), refresh-retriable (retry after metadata refresh), **abortable** (abort + reprocess), application-recoverable (restart producer), invalid-configuration, and general KafkaException.

## 8. The Share Consumer (queue-style consumption — newer addition)

**Share groups** sit alongside consumer groups for workloads that fit classic queue semantics better: partitions can be assigned to **multiple consumers**, group size can **exceed partition count**, and records are **individually acknowledged**. Records are handed out under a **time-limited acquisition lock** (default 30 s); the holder can acknowledge, release, reject (poison-pill handling — delivery attempts are counted), renew, or do nothing (lock expires and the record is redelivered). Per-partition caps on acquired records plus automatic lock expiry keep delivery progressing despite consumer failures.

## 9. Replication (why fsync isn't needed)

Replication is per-partition: one **leader** takes all writes; **followers** fetch from the leader like ordinary consumers (which batches naturally) and maintain byte-identical logs. Replication is the default mode of existence — an unreplicated topic is just RF=1.

- **ISR:** the controller tracks broker liveness (heartbeat sessions); the leader tracks which followers are caught up (`replica.lag.time.max.ms`). In-sync = live session + not too far behind. The ISR set is persisted in cluster metadata. Kafka handles **fail/recover** failures only — not Byzantine ones.
- **Commit rule:** a message is committed when **all current ISR members** have it; only committed messages are ever served to consumers. Guarantee: a committed message survives as long as **at least one ISR replica** stays alive. Kafka stays available through node failures (short failover) but not necessarily through network partitions.
- **ISR vs majority quorum:** majority-vote systems (Zab, Raft, Viewstamped Replication) need 2f+1 replicas to tolerate f failures — 5 copies to survive 2 failures is unaffordable for primary data storage (fine for metadata, which is why such algorithms show up in ZooKeeper/HDFS journal). Kafka's ISR model tolerates **f failures with only f+1 replicas**, at the cost of committing at the pace of the slowest ISR member (mitigated by letting producers choose whether to block, via `acks`). The closest academic relative is Microsoft's **PacificA**.
- **No stable-storage assumption:** unlike many replication algorithms, Kafka doesn't require crashed nodes to recover with data intact — disk errors are the most common real-world failure, and per-write fsync would cost 2–3 orders of magnitude in performance. A replica rejoining the ISR must **fully re-sync first**, even if it lost unflushed data.
- **Unclean leader election:** if *every* replica dies, you choose: wait for an ISR member to return (consistency; possibly stay down forever) or accept the first replica back as leader (availability; committed data may vanish). Default since 0.11: wait (`unclean.leader.election.enable=false`). The docs note this dilemma exists in every quorum scheme.
- **Availability vs durability knobs:** `acks=all` means "all *current* ISR" — which could be just one replica! Hence `min.insync.replicas`: with `acks=all`, writes fail unless the ISR is at least that big. Together with disabled unclean election, these let you pick durability over availability explicitly.
- **Replica management:** partitions and leaderships are spread round-robin across brokers; the **controller** detects broker failure and elects new leaders for all affected partitions in a **batch**, making failover fast even with thousands of partitions.

## 10. Log Compaction

For **keyed, mutable data** (e.g., a topic of user-ID→email updates), time-based retention breaks the "replay the log to rebuild state" property, since old updates get dropped wholesale. Compaction is **per-record retention**: remove a record only when a **newer record with the same key** exists, guaranteeing the log always holds at least the **latest value for every key** — a full snapshot, not just recent changes.

- **Use cases:** database change subscription (keeping caches/search/Hadoop in sync *and* able to do full reloads), event sourcing, and journaling local state for HA (the Samza/Streams changelog pattern). Inspired by LinkedIn's Databus, but Kafka is a source-of-truth store rather than just a cache. (The docs link to Kreps's "The Log" for the philosophy.)
- **Structure:** the log has a **dense head** (normal, all messages, sequential offsets) and a **compacted tail**. Offsets **never change** and every offset remains a valid position — reading a compacted-away offset just returns the next surviving message.
- **Tombstones:** key + null payload = delete marker; it removes prior values for the key and is itself purged after `delete.retention.ms` (default 24 h) — a consumer lagging more than that may miss deletes.
- **Guarantees:** caught-up consumers see *every* write (with `min/max.compaction.lag.ms` bounding when a record may/must become compactable); ordering is never changed, only records removed; a from-the-beginning read sees at least the final state of every key in write order.
- **Mechanics:** background **log cleaner** threads pick the log with the highest head-to-tail ("dirty") ratio, build a compact hash of last-offset-per-key (24 bytes/entry — an 8 GB buffer cleans ~366 GB of head at 1 KB messages), and recopy segments start-to-finish dropping superseded keys, swapping clean segments in without blocking reads. Enabled per topic via `log.cleanup.policy=compact`; time/size retention and compaction can coexist per-topic in one cluster.

## 11. Quotas

Multi-tenant self-defense: without limits, one misbehaving client can DOS the brokers and everyone else.

- **Two quota types:** network bandwidth (bytes/sec, since 0.9) and request rate (% of I/O + network thread time ≈ CPU, since 0.11).
- **Who's limited:** quotas attach to (user, client-id), user, or client-id groups, with a defined precedence order (most specific wins); overrides live in the metadata log and take effect **without broker restarts**.
- **Per-broker, not cluster-wide:** sharing usage data across brokers is harder than the quota feature itself, so each group gets X per broker.
- **Enforcement:** on violation, the broker computes the needed delay, responds immediately (empty for fetches), then **mutes the client's channel** until the delay passes — so even old clients that ignore the delay hint get throttled by backpressure. Measurement uses many small windows (e.g., 30×1 s) so throttling is smooth rather than bursty.

---

## Key Takeaways (TL;DR)

- The storage design rests on two bets: **sequential disk I/O is as fast as the network** (~6000x faster than random I/O), and the **OS page cache beats any JVM in-process cache** — so write straight to the filesystem log, skip fsync, and let replication provide durability.
- **O(1) append-only logs**, not O(log N) BTrees: performance independent of data size → retention is cheap → messages aren't deleted on consumption → replay, multiple subscribers, and batch loads become possible.
- Efficiency = **batching everywhere** (message sets, producer linger) + **one binary format end-to-end** enabling `sendfile` zero-copy + **batch-level compression** that stays compressed from producer through disk to consumer.
- **Pull-based consumers with a single-integer position** per partition eliminate per-message broker bookkeeping and give free rewind/replay.
- Delivery semantics are explicit and layered: at-least-once by default; **idempotent producer** kills retry duplicates; **transactions + offsets-with-output** give exactly-once for Kafka→Kafka; offsets-stored-with-output generalizes it to external sinks.
- Replication uses **ISR + commit-on-all-ISR** rather than majority quorums — f+1 replicas tolerate f failures — with `min.insync.replicas` and unclean-election settings as the explicit durability/availability dial, and no reliance on fsync or stable storage.
- **Compaction** keeps the latest value per key (tombstones for deletes, offsets immutable) so a topic doubles as a rebuildable table; **quotas** (bandwidth + CPU, enforced by delay + channel muting) make large multi-tenant clusters survivable.

## How this fits your reading sequence

This is the primary source the other two readings orbit: Kreps's "The Log" is the philosophy, the Confluent Internals course is the guided tour, and this document is the engineering rationale written by the team itself. The Persistence + Efficiency sections are the canonical answer to the interview question "why is Kafka fast?" (sequential I/O, page cache, zero-copy, batching), and the Replication section is the canonical answer to "why doesn't Kafka use Raft/majority quorums for data?" (ISR needs f+1 not 2f+1). Note this 4.3-era version also documents newer material worth knowing: KRaft-based controller liveness, the standardized transaction exception taxonomy, and **share groups** (queue semantics), which is a fresh topic most candidates won't know.