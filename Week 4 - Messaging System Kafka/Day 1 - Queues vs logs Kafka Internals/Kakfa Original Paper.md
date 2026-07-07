# Kafka: a Distributed Messaging System for Log Processing (2011)

**Authors:** Jay Kreps, Neha Narkhede, Jun Rao (LinkedIn Corp.)
**Venue:** NetDB'11 Workshop — June 12, 2011, Athens, Greece
**Length:** 7 pages

> A detailed summary of the foundational Kafka paper. Note: this describes Kafka circa 2011, when it was a young LinkedIn project. Several features we now consider core (replication, exactly-once, the consumer-group protocol via a group coordinator instead of ZooKeeper) did not yet exist and are flagged throughout.

---

## 1. Motivation & Problem Statement

Consumer internet companies generate enormous volumes of **log data**, far larger than their "real" (transactional) data. The paper groups this data into two buckets:

- **User activity events** — logins, pageviews, clicks, likes, shares, comments, search queries.
- **Operational metrics** — service call stacks, call latency, errors, and machine metrics (CPU, memory, network, disk).

Historically this data fed offline analytics. The key shift the paper identifies: activity data had become part of the **production, real-time data pipeline**, directly driving site features such as search relevance, recommendations, ad targeting/reporting, security (spam / scraping defense), and newsfeeds.

This real-time usage created new demands because the data volume is **orders of magnitude larger** than transactional data. The paper cites contemporaries for scale context: China Mobile collecting 5–8 TB/day of call records and Facebook gathering ~6 TB/day of user activity events.

**The gap Kafka fills:** LinkedIn needed both traditional offline analytics *and* real-time consumption (delays of no more than a few seconds), and no existing system did both well. Kafka combines the benefits of **log aggregators** (high throughput, offline batch) and **messaging systems** (low-latency, real-time API) in a single piece of software.

---

## 2. Related Work — Why Existing Systems Didn't Fit

### Traditional enterprise messaging systems (IBM WebSphere MQ, JMS, Oracle, TIBCO)

Four reasons they were a poor fit for log processing:

1. **Feature mismatch.** They emphasize rich delivery guarantees (e.g., WebSphere MQ transactionally inserting into multiple queues atomically; JMS acknowledging individual messages out of order). These are overkill for logs — occasionally losing a few pageviews is acceptable — and the extra features bloat both the API and the implementation.
2. **Weak throughput focus.** JMS, for example, has no API to batch multiple messages into a single request, so every message needs a full TCP/IP round trip — infeasible at log volumes.
3. **Weak distributed support.** No easy way to partition and store messages across many machines.
4. **Assumption of near-immediate consumption.** Their performance degrades badly if unconsumed messages accumulate — which is exactly what happens with offline/batch consumers (e.g., data-warehouse loads).

### Log aggregators (Facebook Scribe, Yahoo Data Highway, Cloudera Flume)

Built primarily to **push** log data into HDFS/warehouses for **offline** consumption. Problems:

- Mostly offline-only.
- Often leak implementation details to consumers (e.g., Yahoo's "minute files").
- Use a **push** model, where the broker forwards data to consumers.

**Kafka's contrarian choice: a pull model.** Each consumer retrieves messages at the maximum rate it can sustain, avoiding being flooded. Pull also makes it easy to **rewind** a consumer — a theme the paper returns to repeatedly.

The paper also mentions Yahoo Research's **HedWig** (scalable, durable pub/sub) but notes it's aimed mainly at storing a data store's commit log.

---

## 3. Architecture & Design Principles

### Core concepts

- **Topic** — a stream of messages of a particular type.
- **Producer** — publishes messages to a topic.
- **Broker** — a server storing the published messages.
- **Consumer** — subscribes to one or more topics and consumes by **pulling** from brokers.
- **Partition** — a topic is divided into multiple partitions, each broker stores one or more; this is the unit of load balancing and parallelism.

**API design.** Deliberately minimal. A message is just a **payload of bytes** — the user picks any serialization. For efficiency, the producer can send a **set of messages** in a single publish request. Consumers create one or more **message streams** per topic; messages are distributed evenly across the sub-streams, and each stream exposes an **iterator that never terminates** — it blocks when no messages are available and resumes when new ones arrive.

Both delivery models are supported:
- **Point-to-point** — multiple consumers jointly consume a single copy of all messages (load balancing within a consumer group).
- **Publish/subscribe** — multiple consumers each get their own copy (fan-out across consumer groups).

### 3.1 Efficiency on a Single Partition

This is the technical heart of the paper — the "unconventional yet practical" choices.

**Simple storage (the log).** Each partition = a logical log, physically a set of **segment files of ~1 GB each**. Publishing appends the message to the last segment file. Segment files are flushed to disk only after a configurable number of messages or a time interval — and **a message is exposed to consumers only after it's flushed.**

**Offset-based addressing (no message IDs).** Unlike typical messaging systems, a Kafka message has **no explicit message ID**; it's addressed by its **logical offset** in the log. This avoids maintaining seek-intensive, random-access index structures mapping IDs → locations. Offsets are **increasing but not consecutive** — the next offset = current offset + current message length. Each broker keeps an **in-memory sorted list of offsets**, including the offset of the first message in every segment file, so it can locate the segment for a pull request by a simple search.

**Consumer pull mechanics.** A consumer consumes a partition **sequentially**. Acknowledging an offset implies all prior messages were received. Consumers issue **asynchronous pull requests**, each carrying (a) the starting offset and (b) an acceptable number of bytes (typically hundreds of KB). After receiving, the consumer computes the next offset for the subsequent pull.

**Reliance on the OS page cache (no in-app caching).** Kafka does **not** cache messages in the application layer; it relies on the **filesystem page cache**. Benefits:
- Avoids double-buffering (data cached only in the page cache).
- Keeps a **warm cache even across broker process restarts.**
- Almost no garbage-collection overhead → practical to implement in a VM/JVM language.
- Sequential access by both producer and consumer (consumer usually lagging slightly) makes OS heuristics — **write-through caching and read-ahead** — very effective.

Result: production and consumption performance is **linear to data size, up to many terabytes.**

**Zero-copy transfer via `sendfile`.** A naive file→socket send involves 4 copies and 2 system calls: (1) disk → page cache, (2) page cache → app buffer, (3) app buffer → kernel buffer, (4) kernel buffer → socket. Kafka uses the Linux/Unix **`sendfile` API** to transfer bytes directly from file channel to socket channel, eliminating 2 copies and 1 system call. This is especially valuable because Kafka is multi-subscriber — the same message may be sent to many consumers.

**Stateless broker.** The broker does **not** track how much each consumer has consumed — the **consumer** tracks its own offset. This dramatically reduces broker complexity and overhead. The tricky part — knowing when a message can be deleted — is solved with a **time-based retention SLA**: a message is auto-deleted after a retention period (typically **7 days**). Because performance doesn't degrade with data size, long retention is feasible.

**Rewind capability (a side benefit).** Because the broker is stateless and retains data by time, a consumer can **deliberately rewind to an old offset and re-consume**. This violates the usual queue contract but is essential in practice:
- Replaying messages after fixing a consumer application bug (important for ETL loads into the warehouse/Hadoop).
- Recovering from a crash where a consumer only periodically flushes to a persistent store (e.g., a full-text indexer) — checkpoint the smallest unflushed offset and re-consume from there.

Rewinding is much easier in the pull model than the push model.

### 3.2 Distributed Coordination

**Producer side.** A producer publishes to either a **randomly selected partition** or one determined by a **partitioning key + partitioning function**.

**Consumer groups.** A group is one or more consumers that **jointly** consume subscribed topics — each message goes to exactly **one** consumer in the group. Different groups independently consume the full set, with **no cross-group coordination**.

**Two key design decisions:**

1. **Partition = smallest unit of parallelism.** At any time, all messages from one partition are consumed by a **single consumer** within each group. Allowing multiple consumers per partition would require locking and state coordination. The cost: to balance load well, you need **many more partitions than consumers** in a group (achieved by **over-partitioning** topics). Consumers only coordinate during **rebalance**, an infrequent event.
2. **No central master.** Consumers coordinate in a **decentralized** fashion using **ZooKeeper**, avoiding the burden of handling master failures.

**ZooKeeper usage.** Kafka uses ZooKeeper to (1) detect addition/removal of brokers and consumers, (2) trigger rebalancing when those changes happen, and (3) maintain the consumption relationship and track consumed offsets. It relies on ZooKeeper features: watchers (get notified on path/child changes), **ephemeral** paths (auto-removed when the creating client disconnects), and replicated, highly available data.

**The four registries:**

| Registry | Persistence | Contents |
|----------|-------------|----------|
| **Broker registry** | Ephemeral | Broker host/port + the topics & partitions it stores |
| **Consumer registry** | Ephemeral | The consumer's group + the topics it subscribes to |
| **Ownership registry** | Ephemeral | One path per subscribed partition; value = ID of the consumer currently owning (consuming) it |
| **Offset registry** | Persistent | Per subscribed partition, the offset of the last consumed message |

If a broker fails, its partitions vanish from the broker registry. If a consumer fails, it loses its consumer-registry entry and all owned partitions. Each consumer watches both the broker and consumer registries.

**Rebalance process (Algorithm 1).** On startup or on a watched change, a consumer `Ci`:
1. Removes its owned partitions from the ownership registry.
2. Reads broker + consumer registries from ZooKeeper.
3. Computes `PT` (partitions available for topic T) and `CT` (consumers subscribing to T).
4. **Range-partitions** `PT` into `|CT|` chunks and **deterministically** picks the chunk at its index position.
5. Writes itself as owner of each picked partition, then starts a thread pulling from each, beginning at the offset from the offset registry.
6. Periodically updates the latest consumed offset back into the offset registry.

**Handling rebalance races.** Notifications may reach consumers at slightly different times, so a consumer may try to grab a partition another still owns. When that happens, it **releases everything, waits, and retries** — in practice, stabilizing after a few retries. For a brand-new group with no offsets, consumers start at the **smallest or largest** available offset (configurable).

### 3.3 Delivery Guarantees

- **At-least-once delivery** in general. Exactly-once needs two-phase commit and was deemed unnecessary. Most of the time messages arrive exactly once per group, but a consumer crashing without clean shutdown can cause the takeover consumer to see **duplicates** (messages after the last offset committed to ZooKeeper). Apps that care must **de-duplicate themselves** using offsets or a unique message key — usually cheaper than 2PC.
- **In-order within a partition** — guaranteed. **No ordering guarantee across partitions.**
- **CRC per message** — stored in the log to detect corruption; a recovery process removes messages with inconsistent CRCs, and the CRC also catches network errors on produce/consume.
- **No replication (in 2011).** If a broker goes down, its unconsumed messages become unavailable; permanent storage damage means they're **lost forever**. Built-in replication is explicitly listed as future work.

---

## 4. Kafka Usage at LinkedIn

- **One Kafka cluster co-located per datacenter** running user-facing services. Frontend services publish log data to local brokers in batches; a **hardware load balancer** distributes publish requests evenly. Online consumers run in the same datacenter.
- **A separate offline cluster** near the Hadoop/warehouse infrastructure runs embedded consumers that pull from the live-datacenter clusters. Data-load jobs then move data into Hadoop and the warehouse for reporting/analytics; this cluster is also used for prototyping and ad-hoc querying over raw event streams.
- **End-to-end latency** for the full pipeline: **~10 seconds on average**, sufficient for their needs.
- **Scale at the time:** hundreds of GB and close to **a billion messages per day** (expected to grow as legacy systems migrated).
- **Auditing / no-data-loss verification:** each message carries a timestamp and origin server name. Producers periodically emit monitoring events (count of messages published per topic per time window) to a separate topic; consumers reconcile their received counts against these to validate correctness.
- **Hadoop integration:** a custom Kafka input format lets MapReduce jobs read directly from Kafka. The **stateless broker + client-side offsets** let MapReduce's task-restart semantics handle loads naturally — data and offsets are committed to HDFS only on successful job completion, avoiding duplication or loss.
- **Serialization:** **Avro**, chosen for efficiency and **schema evolution**. Each message stores its Avro schema ID + serialized bytes; a lightweight **schema registry** maps IDs to schemas and enforces producer/consumer compatibility (lookup done once per schema since schemas are immutable).

---

## 5. Experimental Results

**Setup.** Compared against **Apache ActiveMQ v5.4** (JMS, using its default KahaDB store) and **RabbitMQ v2.4**. Two Linux machines, each with 8× 2 GHz cores, 16 GB RAM, 6 disks in RAID 10, connected by a 1 Gb link. One machine was the broker, the other the producer or consumer.

### Producer test

Single producer publishing **10 million messages of 200 bytes each**; brokers configured for asynchronous flush. Kafka tested with batch sizes of **1 and 50** (ActiveMQ/RabbitMQ assumed batch size 1, lacking easy batching).

| System | Throughput |
|--------|-----------|
| **Kafka (batch 1)** | ~50,000 msg/sec |
| **Kafka (batch 50)** | ~400,000 msg/sec |
| RabbitMQ | roughly half of Kafka's or less (Kafka ≥ 2× higher) |
| ActiveMQ | orders of magnitude lower |

Why Kafka won:
1. **No producer acks** — the producer sends as fast as the broker can handle. With batch 50 a single producer nearly **saturated the 1 Gb link**. (Trade-off: without acks, no guarantee every message reached the broker — an acceptable durability/throughput trade for logs.)
2. **More efficient storage format** — ~**9 bytes** overhead per message in Kafka vs. **~144 bytes** in ActiveMQ (ActiveMQ used ~70% more space for the same 10M messages). ActiveMQ's overhead came from heavy JMS headers and maintaining a B-tree of message metadata.
3. **Batching** — a batch of 50 improved throughput by nearly an order of magnitude by amortizing RPC overhead.

### Consumer test

Single consumer retrieving **10 million messages**; all systems prefetching ~1000 messages / ~200 KB per pull; ActiveMQ/RabbitMQ set to automatic ack. All data fit in memory (served from page cache).

| System | Throughput |
|--------|-----------|
| **Kafka** | ~22,000 msg/sec |
| ActiveMQ / RabbitMQ | ~4× lower |

Why Kafka won:
1. More efficient storage format → **fewer bytes transferred** per message.
2. ActiveMQ/RabbitMQ brokers had to **maintain per-message delivery state** (an ActiveMQ thread was busy writing KahaDB pages to disk); the **Kafka broker did no disk writes** during the test.
3. **`sendfile`** reduced transmission overhead.

**Fairness note.** The authors explicitly state the point isn't that ActiveMQ/RabbitMQ are inferior — both have **more features** than Kafka — but to show the performance gains achievable by a **specialized** system.

---

## 6. Conclusion & Future Work

**Contributions.** Kafka is a specialized system for high-volume log streams that combines:
- A **pull-based** model letting each app consume at its own rate and **rewind** when needed.
- Much **higher throughput** than conventional messaging systems (by focusing narrowly on log processing).
- Integrated **distributed support** and horizontal **scale-out**.
- Proven in production at LinkedIn for both offline and online use.

**Stated future work** (much of which became core Kafka later):
1. **Built-in replication** across brokers for durability/availability under unrecoverable failures — with both **async and sync** models to trade producer latency against guarantee strength.
2. **Stream processing** in Kafka — semantically partitioning by join key so all messages with a key land on the same partition/consumer, plus a library of windowing/join utilities. (This foreshadows **Kafka Streams**.)

---

## Key Takeaways / Interview-Relevant Points

- **The log is the abstraction.** Kafka is fundamentally an append-only, offset-addressed log split into ~1 GB segment files — not a queue with per-message state.
- **Offloading state to the client** (offsets tracked by consumers, not brokers) is what makes the broker stateless, simple, and cheap — and enables rewind and clean MapReduce restart semantics.
- **Throughput came from a stack of deliberate trade-offs:** batching, no producer acks by default, offset addressing (no random-access index), page-cache reliance (no app-level cache, minimal GC), and `sendfile` zero-copy.
- **Partition = unit of parallelism, ordering, and load balancing** — and you deliberately over-partition so consumer groups can rebalance cleanly.
- **What was NOT in the 2011 design:** replication, exactly-once semantics, a group-coordinator-based rebalance protocol (2011 used ZooKeeper registries + client-driven rebalance, with the known race/duplicate caveats), and the KRaft metadata layer. These are the biggest deltas between this paper and modern Kafka.

---

*Reference: Kreps, J., Narkhede, N., & Rao, J. (2011). "Kafka: a Distributed Messaging System for Log Processing." Proceedings of the NetDB Workshop, Athens, Greece.*