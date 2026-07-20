# Apache Kafka Internal Architecture (Confluent "Kafka Internals" Course) — Detailed Summary

**Source:** developer.confluent.io — free course "Apache Kafka® Internal Architecture," presented by **Jun Rao** (co-creator of Kafka, co-founder of Confluent), with hands-on exercises by Danica Fine. ~2.5 hours, 15 modules.

**What the course covers:** how a broker actually processes requests, how the replication protocol guarantees durability, how the control plane works (ZooKeeper → KRaft), the consumer group protocol, the knobs behind durability/availability/ordering guarantees, transactions and exactly-once semantics, log compaction, Tiered Storage, cluster elasticity, and geo-replication.

---

## Module 1: Fundamentals — Kafka's Architecture at a Glance

Kafka is split into two independently scalable layers:

- **Storage layer:** a distributed, scale-out system of brokers that stores events durably.
- **Compute layer:** the clients — Producer API and Consumer API (the two primitives), plus higher-level APIs built on them: **Kafka Connect** (source/sink connectors for integrating external systems) and **Kafka Streams / ksqlDB** (stream processing, imperative Java vs declarative SQL).

Core concepts:

- **Event/record** = timestamp + optional key + value + optional headers. Key and value are just byte arrays (serializer-agnostic; with Schema Registry + Avro, the value starts with a magic byte + 4-byte schema ID, then the encoded payload). The key drives ordering, co-location of related events, and key-based (compacted) retention. Events should be small — store a pointer to a 10 GB video, not the video.
- **Topic** = an append-only, immutable log of related events (think "table" as the organizing unit).
- **Partition** = the unit of distribution and parallelism. A topic is split into partitions living on different brokers; producers write to and consumers read from partitions in parallel.
- **Offset** = a monotonically increasing, never-reused ID per event within a partition. Events are stored and delivered in offset order, which is what makes consumer progress tracking trivial.

---

## Module 2: Inside the Apache Kafka Broker (the Data Plane)

How a broker handles produce and fetch requests — this module explains why Kafka is fast.

**Produce path:**

1. The producer batches records per partition (controlled by `batch.size` / `linger.ms`) and chooses the partition (hash of key, or sticky/round-robin if keyless), then sends the batch to that partition's leader broker.
2. On the broker, a pool of **network threads** picks the request off the socket and places it on a shared **request queue**.
3. A pool of **I/O threads** validates the batch (e.g., CRC), and **appends it to the partition's commit log** — which on disk is a set of **segment files**. The write goes to the OS **page cache**, not immediately fsync'd; durability comes from replication rather than per-message flushes.
4. The request is not answered yet if `acks=all` — it's parked in a structure called **purgatory** until followers replicate the data. Then the response is queued and the network thread sends it back.

**Fetch path:** consumers issue fetch requests specifying partition + offset. The broker uses the offset index to locate data in the segment files and replies — often using **zero-copy** transfer straight from page cache to the network socket. If no data is available, the request waits in purgatory (up to `fetch.max.wait.ms`) rather than making the client busy-poll.

Key performance ideas: sequential I/O on the log, batching everywhere, page cache reliance, zero-copy reads, and a fully asynchronous request pipeline with no per-request thread.

*(Module 3 is a hands-on lab tuning producer settings — throughput vs latency via `batch.size`, `linger.ms`, compression, and `acks`.)*

---

## Module 4: Data Plane — the Replication Protocol

Each partition has one **leader** and N−1 **follower** replicas (replication factor, typically 3). All produces and (by default) consumes go through the leader; followers continuously fetch from the leader to stay in sync.

Core machinery:

- **ISR (In-Sync Replica set):** the leader plus followers that are caught up. Followers that lag or die are removed from the ISR; when they catch up, they rejoin.
- **High watermark:** the highest offset that has been replicated to *all* members of the ISR. Only messages below the high watermark are considered **committed** and visible to consumers. With `acks=all`, the producer gets its ack only once the record is committed in this sense.
- **Leader epoch:** every leadership change bumps an epoch number stamped into the records. Epochs let a recovering or ex-leader replica figure out exactly which of its log entries are valid and truncate any uncommitted divergent tail before rejoining — this is how Kafka reconciles logs after failures without losing committed data.
- **Leader failover:** the control plane picks a new leader *from the ISR*, which guarantees the new leader has every committed message. Followers then truncate/catch up to the new leader using epoch information.

The elegant trick: rather than a quorum-write protocol like Raft for data, Kafka uses leader-based replication with the ISR + high watermark, giving `f` failures tolerance with `f+1` in-sync replicas, tunable per topic.

## Module 5: The Control Plane — ZooKeeper vs KRaft

The control plane manages **cluster metadata**: which brokers exist, which replicas exist, who is leader of each partition, the ISR, configs.

- **Legacy (ZooKeeper) mode:** metadata lives in ZooKeeper; one broker is elected **controller** and pushes metadata changes to brokers via RPCs. Problems: two systems to operate, controller failover is slow (new controller must reload all metadata from ZooKeeper), and metadata propagation by RPC scales poorly with partition count.
- **KRaft mode (the replacement):** Kafka manages its own metadata using a **Raft-based quorum of controllers**. Metadata itself is stored as an internal, replicated **event log** (`__cluster_metadata` topic) — Kafka eating its own dog food: metadata changes are just events. The active controller is the Raft leader; standby controllers already have the full log replicated, so **failover is near-instant**. Brokers *pull* metadata deltas by replicating the metadata log instead of receiving RPC pushes, and can cache/snapshot it locally.
- Result: one system instead of two, and roughly an **order of magnitude more partitions** supported per cluster.

---

## Module 6: Consumer Group Protocol

How many consumer instances share the work of a topic:

- Consumers with the same `group.id` form a **consumer group**; partitions of the subscribed topics are divided among the members, each partition to exactly one member.
- A broker acts as the **group coordinator** (chosen by hashing the group ID onto a partition of the internal `__consumer_offsets` topic). Members find it, join the group, and send **heartbeats**.
- One member is made **group leader** and computes the partition assignment (client-side assignors: range, round-robin, sticky, cooperative-sticky) which the coordinator distributes.
- **Offsets are committed** to the `__consumer_offsets` compacted internal topic; on restart or reassignment, a consumer resumes from the last committed offset.
- **Rebalancing:** triggered when members join/leave/fail or the topic's partitions change. The classic ("eager") protocol is **stop-the-world** — everyone revokes all partitions, rejoins, and gets a new assignment. Improvements the module walks through:
  - **Sticky assignment** minimizes partition movement between generations.
  - **Cooperative (incremental) rebalancing** lets members keep the partitions that aren't moving and only revoke the ones actually being reassigned — processing continues during rebalance.
  - **Static membership** (`group.instance.id`) avoids a rebalance entirely when a known member briefly restarts.

*(Module 7 is a hands-on lab observing group behavior and rebalances.)*

## Module 8: Configuring Durability, Availability, and Ordering Guarantees

The "which knobs give which guarantees" module:

- **Durability:** `replication.factor` (usually 3), producer `acks` (`0` fire-and-forget, `1` leader-only, `all` full-ISR), and `min.insync.replicas` (e.g., RF=3 + min ISR=2 means a write must land on ≥2 replicas or the produce fails). Broker-side `unclean.leader.election.enable=false` prevents electing an out-of-sync replica as leader (choosing consistency over availability; setting it true chooses availability at the risk of losing committed data).
- **Availability trade-off:** stricter min-ISR and `acks=all` mean writes stall or fail when too many replicas are down; looser settings keep accepting writes but risk loss.
- **Ordering & duplicates:** order is guaranteed only *within a partition*. Retries can historically reorder or duplicate messages; the fix is the **idempotent producer** (`enable.idempotence=true`, now default): the broker tracks a producer ID + per-partition sequence numbers and discards duplicates, preserving exactly-once, in-order delivery per partition *within a producer session*, even with retries and `max.in.flight.requests` up to 5.

## Module 9 (+10): Transactions and Exactly-Once Semantics

Idempotence fixes duplicates from retries, but a typical stream-processing step is **consume → process → produce**, and a crash between "produce output" and "commit input offsets" yields duplicates or loss. Kafka **transactions** make the outputs and the offset commits **atomic**:

- The producer sets a stable **`transactional.id`**; a broker-side **transaction coordinator** (backed by the internal `__transaction_state` log) manages its state.
- Flow: `initTransactions()` (fences off any zombie producer with the same transactional ID via an epoch bump) → `beginTransaction()` → produce to output partitions + `sendOffsetsToTransaction()` → `commitTransaction()` or `abortTransaction()`.
- Commit is a **two-phase** process: the coordinator writes "prepare commit" to its log, then writes **control markers** (commit/abort markers) into every participating partition, then records completion.
- Consumers with `isolation.level=read_committed` only see messages from committed transactions (they read up to the **last stable offset** and skip aborted data).
- **Zombie fencing** is the crucial correctness piece: an old producer instance that comes back after a failover is rejected because its epoch is stale.

This is the foundation of Kafka Streams' `processing.guarantee=exactly_once_v2`.

## Module 11: Topic Compaction

An alternative retention policy for **keyed, changelog-like topics**: instead of deleting old segments by time/size, **compaction retains at least the latest value for every key**.

- The log is split into a **cleaned** portion and a **dirty** (not yet compacted) portion. Background **cleaner threads** pick the log with the highest dirty ratio, build an in-memory map of key → latest offset from the dirty section, then recopy segments, dropping records that have a newer value later in the log.
- A record with a **null value is a tombstone**: it marks the key as deleted and is itself removed after a retention delay (`delete.retention.ms`) so that consumers have time to see the deletion.
- Offsets never change — compaction removes records but preserves the offsets of the survivors, so consumer position logic still works (some offsets simply no longer exist).
- Timing knobs (`min.cleanable.dirty.ratio`, `min/max.compaction.lag.ms`) control when compaction kicks in.
- Use cases: `__consumer_offsets` itself, database change-data-capture topics, Kafka Streams state-store changelogs, any "latest value per entity" feed — the log becomes a rebuildable snapshot of a table.

## Module 12: Tiered Storage

Decouples storage from compute:

- Historically a partition had to fit on its broker's disks, and big retention made brokers expensive and slow to rebalance (moving a replica means copying all its data).
- With Tiered Storage, brokers keep only **recent ("hot") segments locally** and offload **older segments to cheap object storage** (e.g., S3), transparently to clients: consumers reading old offsets are served by the broker fetching from the remote tier.
- Benefits: effectively **infinite retention** at object-storage prices, much smaller local state so **rebalancing/scaling is far faster**, and better isolation between real-time consumers (page cache) and historical replays (network reads that don't thrash the cache).

## Module 13: Cluster Elasticity

Growing/shrinking a cluster means **moving partition replicas** between brokers:

- The primitive is the replica-reassignment mechanism (`kafka-reassign-partitions`), but choosing *what* to move is a hard bin-packing problem by hand.
- Tooling automates it: **Self-Balancing Clusters** (Confluent) / Cruise Control–style automation continuously monitor load and generate reassignments when brokers are added or removed or become unbalanced; **replication throttles** keep the data movement from starving production traffic.
- Tiered Storage compounds the benefit: less local data per broker means rebalances complete in minutes rather than hours.

## Module 14 (+15): Geo-Replication

Patterns for multi-region/multi-cluster Kafka:

- **Stretched cluster:** one cluster spanning nearby zones/regions — synchronous, offsets preserved automatically, but only viable with low inter-site latency.
- **Async cluster-to-cluster replication:**
  - **MirrorMaker 2** (Connect-based) copies topics between clusters; it must translate offsets between clusters (source and target offsets differ), which complicates failover.
  - **Confluent Replicator** — similar Connect-based approach with extra features.
  - **Cluster Linking** (Confluent): a broker-native protocol that creates **mirror topics** whose offsets are **byte-for-byte identical to the source** — no Connect cluster to run, no offset translation, making consumer failover between regions dramatically simpler.
- Use cases: disaster recovery (active-passive), regional locality/aggregation (active-active with prefixed topics to avoid cycles), data sharing between organizations, and hybrid cloud migration.

---

## Key Takeaways (TL;DR)

- A broker is an **async request pipeline** (network threads → request queue → I/O threads → page-cache log append → purgatory) built around sequential I/O, batching, and zero-copy — that's the speed story.
- Replication = **leader + ISR + high watermark + leader epochs**: committed means "on every in-sync replica," failover elects from the ISR, epochs let replicas truncate divergent tails safely.
- **KRaft** replaces ZooKeeper by storing cluster metadata as a Raft-replicated internal event log — instant controller failover and ~10x partition scalability, one system to run.
- **Consumer groups** = a coordinator broker + client-side assignment + committed offsets in `__consumer_offsets`; modern rebalancing is sticky, cooperative, and incremental rather than stop-the-world.
- Guarantees are **explicit knobs**: RF, `acks`, `min.insync.replicas`, unclean-election, idempotence — you choose your point on the durability/availability spectrum.
- **Transactions** make produce + offset-commit atomic via a transaction coordinator, control markers, `read_committed` consumers, and zombie fencing — enabling exactly-once stream processing.
- **Compaction** turns a topic into a bounded, latest-value-per-key snapshot (with tombstones for deletes); **Tiered Storage** gives infinite retention and fast elasticity; **Cluster Linking** gives offset-preserving cross-region mirrors.

## How this connects to "The Log" (Kreps)

This course is the engineering follow-through on Kreps's essay: the essay argues the log should be the central abstraction; this course shows the machinery that makes a *distributed, replicated, transactional* log practical at scale — ISR replication instead of naïve quorums, a metadata log (KRaft) to manage the data logs, compaction to make logs double as tables, and tiered storage to make "keep everything" affordable.