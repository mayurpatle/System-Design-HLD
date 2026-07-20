# The Log: What Every Software Engineer Should Know About Real-Time Data's Unifying Abstraction

**Author:** Jay Kreps (LinkedIn Engineering, December 2013) — co-creator of Apache Kafka, later co-founder/CEO of Confluent

**Core thesis:** The humble append-only log is the single most important abstraction in data systems. It quietly underlies databases, replication, consensus protocols, NoSQL stores, version control, and Hadoop — yet most engineers never think about it directly. Kreps argues that if you put the log at the *center* of your architecture, hard problems like data integration, real-time processing, and building distributed systems become dramatically simpler.

The essay has four parts.

---

## Part One: What Is a Log?

### Definition

A log is the simplest possible storage abstraction: an **append-only, totally-ordered sequence of records**. Writes only go to the end; reads scan left to right. Every entry gets a unique, monotonically increasing sequence number (the *log offset*).

Two consequences of this structure matter enormously:

1. **The log defines a notion of time.** "Earlier" and "later" are simply positions in the log. This is a logical clock that is decoupled from any physical wall clock — crucial in distributed systems, where physical clocks on different machines can't be trusted to agree.
2. **A reader's position (offset) fully describes its state.** A consumer can be characterized entirely by "I have processed up to entry N," which makes consumption resumable, replayable, and independent across consumers.

This is not the same thing as application logging (syslog, log4j text lines for humans). Kreps means the *machine-readable*, structured commit log — the kind buried inside databases. Application logs are really just a degenerate, lossy special case of the same idea.

### Logs in databases

Logs are as old as databases themselves. Databases use a **write-ahead log (WAL) / commit log** internally:

- Every mutation is written to the log *before* being applied to the data structures (tables, indexes), which is what makes crash recovery and atomicity (ACID) possible.
- Over time, databases realized the log is also a perfect **replication mechanism**: ship the log to replicas, have them apply the same changes in the same order, and they stay in sync. Oracle, MySQL, and PostgreSQL all do this.

So the log went from being an implementation detail of durability to being the *interface* between a database and its replicas — a record of "what changed and in what order."

### Logs in distributed systems: the State Machine Replication Principle

The log-centric view of distributed systems rests on one deceptively simple observation:

> **If two identical, deterministic processes start in the same state and receive the same inputs in the same order, they will produce the same outputs and end in the same state.**

Deterministic means the output depends only on the inputs (no wall-clock reads, no random numbers, no thread-timing dependence). "Same inputs in the same order" is *exactly* what a log provides. So the problem of making N machines agree ("do the same thing") reduces to the problem of implementing a **distributed, consistent, shared log** feeding them input. This is why consensus protocols (Paxos, ZAB, Raft, Viewstamped Replication) are, at heart, protocols for agreeing on the contents of a log.

Two classic styles fall out of this:

- **State machine model (active-active):** log the incoming *requests* (e.g., `+1`, `*2`), and every replica executes them in order.
- **Primary-backup model:** one leader executes requests and logs the resulting *state changes* (e.g., `x = 4`, `x = 8`); followers just apply them.

This mirrors the database distinction between **physical logging** (log the changed row contents) and **logical logging** (log the SQL commands).

### The duality of tables and logs (events and state)

Kreps highlights a deep duality, familiar from version control:

- A **log of changes**, applied in order, produces a **table** (current state).
- A **table**, if you record every update to it, produces a **changelog**.

The log is the more fundamental of the two: from the complete log you can reconstruct the table *at any point in history* — it's a kind of time-travel / versioning of state. Git works exactly this way: your checked-out working directory is just "current state," derived from a log of patches. The table is a cache of the latest row values; the log is the truth.

---

## Part Two: Data Integration

### The problem

Kreps calls data integration — *making all of an organization's data available in all the services and systems that need it* — one of the most valuable and most neglected problems. He sketches a Maslow-style hierarchy of data needs: first you must reliably **capture** complete data with uniform semantics; only then do reading, processing, modeling, and eventually fancy machine learning make sense. Most organizations, he argues, have gaping holes at the bottom of the pyramid while aspiring to the top.

Two trends make integration harder:

1. **Event data** (clicks, impressions, metrics, operational logs) is exploding — it's orders of magnitude larger than transactional data.
2. **Specialized systems** are proliferating (OLAP, search, key-value stores, batch systems, graph stores) — data must now flow to many destinations, not one warehouse.

### The O(N²) pipeline problem → the log as a universal data hub

Point-to-point integration is quadratic: with M producers and N consumers you end up building and operating something close to M×N bespoke pipelines, each with its own format, semantics, and failure modes. Each new system requires integration work proportional to the number of systems that already exist.

The fix is a **central, unified log**:

- Every data-producing system writes its events/changes to the log ("publish").
- Every consuming system reads from the log at its own pace ("subscribe"), tracking its own offset.
- Integration cost drops from O(N²) to O(N): each system connects once, to the log.

The log also acts as a **buffer** that decouples producers from consumers: a slow or crashed consumer (say, a nightly batch job, or Hadoop down for maintenance) doesn't block producers or other consumers; it just falls behind in the log and catches up later. Consumers can be batch or real-time — they're all just readers at different offsets of the same log.

Kreps recounts LinkedIn's own journey: fragile point-to-point pipelines into the data warehouse and Hadoop, repeated per destination system, taught them the quadratic pain firsthand. The lesson: the warehouse should be *a* consumer of clean data, not the place where cleanup logic is buried; data cleanliness and canonical formats should be pushed **upstream to the producer**, published once in a well-defined schema for everyone. This led directly to building **Apache Kafka** — a distributed, partitioned, replicated commit log offered as a service.

### Making a log scale

A log that carries an entire company's data firehose must be cheap and fast. Kafka's tricks:

- **Partitioning:** a topic is split into many logs (partitions); each partition is totally ordered, but there is no global order across partitions. Ordering is preserved where it matters (e.g., per user key) while writes scale linearly with partitions.
- **Batching:** small reads/writes are grouped together at every layer — client, network, disk flush — trading tiny latency for large throughput gains.
- **Avoiding copies:** the same binary message format is used in memory, on disk, and over the network (zero-copy transfer), and the log relies on linear, sequential disk I/O, which is extremely fast.

Result: a log can be so cheap that you can afford to keep *everything* flowing through it.

---

## Part Three: Logs and Real-Time Stream Processing

### What stream processing really is

Kreps rejects the idea that stream processing is a niche about lossy, approximate, transient computation. His definition: **stream processing is continuous data processing that models data as it flows, with results computed at a frequency the use case demands** — a generalization that includes batch (a batch job is just processing a big chunk of a stream at low frequency). The gap between "daily batch" and "real-time" is really about how data is *collected*: if data arrives continuously as a log, processing it continuously is natural; the once-a-day dump is an artifact of old collection methods (he draws an analogy to the US census — a periodic full crawl — versus continuously recorded births and deaths).

### Why stream processing needs logs

The log gives stream processing three essential things:

1. **Multi-subscriber ordered data:** each processing stage needs deterministic, ordered, replayable input to be restartable and to give reproducible results.
2. **Decoupling of the processing graph:** real pipelines are multi-stage DAGs — job A's output feeds jobs B and C. If each stage writes its output to a log and downstream stages read from it, stages are isolated from one another's speed and failures. Adding a consumer never disturbs the producer.
3. **Buffering:** stages can fall behind and catch up without backpressure cascading through the whole graph.

In this view, a stream processing framework (LinkedIn's **Samza** is his example; the same applies to Storm and successors) is mostly *the code between logs*: read from input logs, transform, write to output logs. The log is the plumbing; the framework handles scheduling, scaling, and fault tolerance of the transformation code.

### Stateful stream processing and the changelog

Real streaming jobs need state (counts, joins, sessions). Storing that state in a remote database is slow and operationally awkward. The log-centric answer:

- Keep state **local** to the processor (in memory or an embedded store like LevelDB/RocksDB) for speed.
- Make it fault-tolerant by writing every state mutation out to a **changelog**. On failure, a new instance replays the changelog to rebuild the state.

This is the tables↔streams duality again, used in the opposite direction: turn a stream into a local table, and back the table with a stream.

### Log compaction

Keeping all history forever is impossible, so retention policy matters:

- For pure **event data**, keep a time/size window (e.g., a few days or weeks).
- For **keyed data** (changelogs of database tables, current-state feeds), Kafka supports **log compaction**: instead of discarding old segments wholesale, delete only records that have been *superseded by a newer record with the same key*. The compacted log stays bounded (proportional to the key space, not to total history) yet always contains at least the latest value of every key — a full snapshot plus recent changes. This makes the log usable as a durable, restorable representation of an entire dataset, not just a message queue.

---

## Part Four: System Building — Unbundling the Database

### The log as the heart of distributed system design

The final section flips perspective: instead of the log serving existing systems, view a data system *as* a log plus derived materializations. Kreps observes that the zoo of specialized distributed systems (search indexes, K/V stores, OLAP stores, stream processors, graph stores) looks like the traditional monolithic database **unbundled** into separate components — each roughly an index structure or a query engine on its own.

His proposed architecture pattern:

1. **The log is the system of record.** All writes go to the log first. The log serializes them (defines the authoritative order) and makes them durable.
2. **Serving layers subscribe to the log.** Each serving node (a search index, a K/V store, a cache, an analytics store) consumes the log and applies changes to its own local index/storage in log order, and answers queries from that local state.
3. Because everything is versioned by log offset, you get for free: **replication** (many nodes consuming the same log), **consistency reasoning** (a node can report "I'm caught up to offset X"; clients wanting read-your-writes can ask for at-least-offset-X reads), **recovery and rebuild** (rebuild any index by replaying the log), and the ability to add entirely **new kinds of derived views** later without touching the write path.

The log does the hard distributed-systems work — ordering, durability, replication, subscription — once; the serving systems become comparatively simple, even single-machine, components. Kreps notes you can start dumb (fully materialize the log into each system) and optimize later; and that many successful real systems (databases' own replication internals, HDFS's edit log/journal, consensus services like ZooKeeper) already look like this internally — the proposal is to make the log a *first-class, external* piece of infrastructure rather than a hidden implementation detail.

### The forward-looking claim

Kreps closes with a prediction: the messy collection of data systems in a modern company can be understood as **one big distributed database**, in which the log is the commit log and every specialized system is just an index or view. Open source makes this plausible — instead of every system rebuilding ordering/replication/durability internally, organizations can assemble their data infrastructure from reliable building blocks around a shared log (Kafka being his bet for that shared log). Whether the future is a few mega-systems or many composable pieces, he argues the log will remain the unifying abstraction either way.

---

## Key Takeaways (TL;DR)

- A **log = append-only, totally ordered sequence of records**; the offset is a logical clock and a complete description of a consumer's progress.
- **State Machine Replication Principle:** deterministic processes fed the same log converge to the same state — this reduces distributed agreement to agreeing on a log (which is what Paxos/Raft/ZAB actually do).
- **Tables and logs are dual:** a log of changes builds a table; a table plus its updates yields a changelog; the log is primary and enables time-travel/rebuilds.
- For **data integration**, a central log turns O(N²) point-to-point pipelines into O(N) connections, buffers fast producers from slow consumers, and unifies batch and real-time consumers — the idea behind Kafka.
- For **stream processing**, logs provide ordered, replayable, multi-subscriber inputs and outputs between stages; local state is made durable via **changelogs**; **log compaction** keeps keyed logs bounded while retaining the latest value per key.
- For **system building**, put the log at the center: writes hit the log, and every store/index/cache is a derived, rebuildable materialization consuming the log — "the database unbundled."

## Why this essay matters

Written in 2013, it is effectively the design philosophy behind Apache Kafka and the intellectual ancestor of event sourcing at scale, CDC (change data capture) pipelines (e.g., Debezium), stream-table duality in Kafka Streams/Flink, the Kappa architecture, and "turning the database inside out" (Martin Kleppmann's later talk, and much of *Designing Data-Intensive Applications*' framing of derived data). If you work with Kafka, CDC, or event-driven microservices, this essay explains *why* those systems are shaped the way they are.