# Tim Berglund — *Distributed Systems in One Lesson*

**Source:** [Devoxx Poland talk (YouTube)](https://www.youtube.com/watch?v=Y6Ev8GIlbxc) · ~50 min · O'Reilly extended course also available

---

## Core thesis

- Simple tasks (store data, compute, move data) are easy on **one machine** — they become **hard** on many machines.
- Tim’s real goal: talk you out of building a distributed system if you can avoid it. You probably won’t — but you should know what breaks.
- **Failure is normal**, not exceptional. Machines fail independently; networks partition; clocks are not synchronized.
- The talk uses a **coffee shop** metaphor throughout and walks three pillars: **storage**, **computation**, and **messaging**.

---

## What is a distributed system?

- **Definition (Andrew Tanenbaum):** A collection of independent computers that appear to users as **one** computer (e.g. amazon.com, a Cassandra cluster, a Kafka cluster).
- **Three characteristics:**
  - Many computers operating **concurrently**
  - Computers can **fail independently**
  - No **global shared clock** — this is the subtle one that makes everything interesting

---

## 1. Storage

### Single master → accidental distributed system

- Start with one relational DB. As read traffic grows, add **read replicas** (writes → master, reads → followers).
- You’ve already broken **strong consistency**: write to master, read from follower → you may not see what you just wrote → **eventual consistency** (often unintentionally).
- Read replication only scales **reads**; all writes still hit the master → bottleneck returns.

### Sharding (more pain)

- Split data by a key (e.g. username A–F, F–N, N–Z) into independent clusters.
- **Cross-shard joins are gone** — relational model breaks down.
- As scale increases you also give up:
  - **Indexes** (under write pressure)
  - **Normalization** → forced **denormalization** for read performance
- By the end, it barely behaves like the relational DB you started with.

### Build for distribution from day one: Cassandra

- Peer nodes on a **ring**, identified by numeric **tokens**.
- **Consistent hashing:** hash the key → find its place on the ring → read/write there.
- **Replication:** store copies on the next N nodes around the ring → tolerates node failure.
- **New problem:** three copies of mutable data → **consistency** problem again.
- **Tunable consistency (per request):**
  - Choose how many nodes must acknowledge a **write** (W) and a **read** (R) out of N replicas.
  - **Strong consistency rule:** `W + R > N` (e.g. W=2, R=2, N=3)
  - W=1, R=1 with N=3 → eventually consistent
- Cassandra is upfront about limitations instead of slowly taking features away as you scale.

---

## CAP theorem (coffee shop screenplay analogy)

- You want three properties in a distributed database:
  - **C — Consistency:** read returns the most recent write
  - **A — Availability:** every read/write gets a response
  - **P — Partition tolerance:** system keeps working when nodes can’t talk to each other
- **You can only pick two** when a network partition happens.
- Partition scenario: you and your co-writer split copies of a screenplay, phone dies → you must choose:
  - Refuse to answer (give up **availability**) to stay **consistent**, or
  - Answer anyway (keep **availability**) but the answer might be stale (**inconsistent**)

---

## 2. Computation

### MapReduce / Hadoop (legacy)

- Forces all computation through **map** and **reduce** functions — painful to program.
- **Map:** tokenize/split data → emit key-value pairs (naive per-chunk counting).
- **Shuffle:** move similar keys to the same machine.
- **Reduce:** aggregate (e.g. sum word counts).
- Works when data already lives in a distributed file system (e.g. **HDFS**).
- **HDFS** is durable/long-lived; new MapReduce systems are largely legacy.
- Hadoop spawned a huge ecosystem (Hive, etc.) partly because the core model was so unpleasant.

### Apache Spark (today’s batch/stream compute)

- Same scatter-gather idea, but developer-friendly: **RDDs / Datasets** — objects you call methods on.
- **No storage lock-in:** works on HDFS, Cassandra, Parquet, S3, etc.
- Assumes data at rest on many machines → go to the data and compute.
- Also supports **stream processing** (not just batch).

### Apache Kafka (streams as first-class)

- **Stream processing:** compute on data **in flight**, not only on data sitting in storage.
- Everything is a **stream** — different mental model from batch-over-HDFS.

---

## 3. Messaging

- Messaging **loosely couples** systems → natural fit for **microservices** (independent deploy/version).
- Early microservices anti-pattern: many services, **one shared database** — doesn’t work well.
- Basic terms: **producer**, **consumer/subscriber**, **topic**, **broker**.

### Single-server message queue (nice while it lasts)

- Can guarantee: **exactly-once delivery**, **global ordering**, reliable delivery.
- Can’t scale or survive failure well.

### Apache Kafka (distributed messaging)

- When a topic outgrows one machine → **partition** it across brokers.
- Hash part of the message (e.g. user ID) → `hash mod num_partitions` → pick partition.
- **Consistent hashing shows up again.**
- **Trade-off:** lose **topic-wide ordering**; keep **ordering within a partition**.
- Smart producers hash on a meaningful field so all messages for one user land in one partition → ordered per user.
- Consumers scale independently across partitions.
- **Replication** handles broker failure (same family of consistency trade-offs as storage).

### Lambda architecture → stream-first thinking

- **Lambda architecture (anti-pattern today):** same logic written twice — batch pipeline (slow, thorough) + stream pipeline (fast, approximate).
- **Better:** treat events as events — process them **in place** on the message bus via stream processing.
- Architecture shift: services produce/consume events on Kafka; each service can subscribe without coupling to producers or other consumers.
- **Streaming changes how you design systems** as much as moving from SQL to Cassandra or from single-thread to distributed compute.

---

## Open-source tools mentioned

| Problem area | Examples |
|---|---|
| Distributed storage | **Apache Cassandra** (consistent hashing, tunable consistency) |
| Distributed compute (batch) | **Hadoop/MapReduce** (legacy), **Apache Spark** |
| Distributed compute (stream) | **Kafka Streams**, Spark Streaming |
| Distributed messaging | **Apache Kafka** |
| Distributed filesystem | **HDFS** |

---

## Key takeaways

- Don’t build a distributed system unless you **have to** — complexity is the cost of scale.
- Every solution **creates new problems** (especially around **consistency**).
- **Consistent hashing** appears across storage, messaging, and more — worth understanding once.
- **CAP** is a real trade-off during partitions, not just theory.
- **Kafka** isn’t only a message queue — it enables event-driven, stream-oriented architectures that replace dual batch+stream pipelines.
- Extended deep dive: O'Reilly video course *Distributed Systems in One Lesson* (~3–4 hours, same title).

---

*Notes summarized from the Devoxx Poland talk. Watch the full video for demos, humor, and Q&A.*
