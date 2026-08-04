# How Discord Indexes Billions of Messages — Study Notes

**Source:** Jake Heinz (Lead Software Engineer, Discord), March 16, 2017
https://discord.com/blog/how-discord-indexes-billions-of-messages

**One-line thesis:** Discord built message search on Elasticsearch, but deliberately **moved sharding and routing out of Elasticsearch and into their own application layer**, so they could run many small clusters instead of one giant one. Almost every interesting decision in the post follows from that inversion.

---

## 1. The requirements — and why each one shaped the design

Search was one of the most requested features, but it was explicitly framed as an *accessory* to the product, not the core. That framing drove everything.

| Requirement | What it actually forced |
|---|---|
| **Cost effective** | Search infra shouldn't cost more than storing the messages themselves. Text and voice chat are the product; search is a add-on and had to be priced like one. |
| **Fast & intuitive** | Latency and UX quality had to match the rest of the product. |
| **Self-healing** | **No dedicated devops team.** Failures had to be tolerated with little or no operator intervention. This is the constraint people usually skip when copying this design. |
| **Linearly scalable** | Capacity growth = add nodes, same as their message store. |
| **Lazily indexed** | Most users never search. **Don't index a server until someone searches it at least once.** Also means a failed index can simply be rebuilt on demand. |

The lazy-indexing requirement is the cost lever. It means the indexed corpus is a fraction of the total message corpus, and it makes index loss a recoverable event rather than a data-loss event.

---

## 2. Build vs. buy

**Managed SaaS search?** Rejected on two grounds. Every option evaluated would have blown the budget by an enormous margin. Separately, the team was uncomfortable shipping user messages outside their own datacenter — they wanted to own the security posture rather than trust a third party with it.

**Open source: Elasticsearch vs. Solr.** Both were viable. Elasticsearch won on four points:

1. **Discovery** — Solr's node discovery needs ZooKeeper. Discord already ran **etcd** and didn't want to stand up a second coordination system just for Solr. Elasticsearch's Zen Discovery is self-contained.
2. **Automatic shard rebalancing** — satisfies the linear-scalability requirement out of the box.
3. **Structured query DSL** built in, versus programmatically assembling query strings for Solr via a third-party library.
4. **Existing team experience** with Elasticsearch.

Note how much weight the *operational* arguments carry relative to the search-quality arguments. With no devops team, "one less system to run" is a first-class criterion.

---

## 3. The core architectural bet: application-level sharding

The team had heard the horror stories about managing large Elasticsearch clusters, and their only prior ES experience was their logging infrastructure. So rather than build one big cluster, they inverted the usual arrangement:

> **Do the sharding and routing yourself, in the application, and index into a pool of many small Elasticsearch clusters.**

Two payoffs, both about blast radius:

- **A cluster outage only takes down the servers whose messages live on that cluster.** Search degrades for a subset, not globally.
- **An unrecoverable cluster can simply be thrown away.** Because indexing is lazy, the affected Discord servers get re-indexed the next time someone searches them. The index is a *cache*, not a system of record — and treating it that way is what makes the self-healing requirement achievable without an ops team.

### Terminology (this trips people up)

Discord overloads the word "shard." In the post:

- **Shard** (capital S, Discord's abstraction) = a **(cluster, index) pair**. This is what a Discord server gets assigned to.
- **shard** (Elasticsearch's native concept) = a Lucene index inside an ES index.

Because Discord does its own sharding, they configure each ES index to have exactly **one** native shard. Elasticsearch's own distribution logic is deliberately switched off.

---

## 4. The components

```
   message posted
        │
        ▼
  ┌─────────────┐        ┌──────────────────┐
  │   Message   │───────▶│  Index Workers   │──┐
  │    Queue    │        │  (Celery)        │  │  bulk insert
  └─────────────┘        └──────────────────┘  │
                                 │             │
                          lookup │             ▼
                                 ▼        ┌──────────────────────┐
   ┌──────────────┐      ┌──────────────┐ │  ES cluster pool     │
   │  Cassandra   │◀────▶│    Redis     │ │  ┌────────┐┌───────┐ │
   │ (Shard map:  │ cache│ - shard cache│ │  │cluster1││cluster2│ │
   │  source of   │      │ - allocator  │ │  │ idx... ││ idx...│ │
   │  truth)      │      │   sorted set │ │  └────────┘└───────┘ │
   └──────────────┘      │ - dirty map  │ └──────────────────────┘
                         └──────────────┘            ▲
                                                     │
   ┌──────────────┐                          ┌──────────────┐
   │  Historical  │─────────────────────────▶│  Search API  │
   │ Index Workers│                          │ (perm checks)│
   └──────────────┘                          └──────────────┘
                                                     ▲
                                                     │  results (IDs)
                                              fetch bodies from Cassandra
```

### Ingest side

**Message queue.** Elasticsearch strongly prefers **bulk** indexing, so real-time per-message indexing was off the table. Messages go into a queue; a worker pulls a batch and does one bulk operation.

The accepted tradeoff: a small delay between a message being posted and it becoming searchable. Justification — **people search history, not the message someone just sent.** This is a good example of using product behavior to relax a technical constraint.

**Index workers.** Consume the queue, do routing, execute bulk inserts. Built on Celery, which Discord already ran as a task queue.

**Historical index workers.** Walk a server's message history and insert it into the index — this is what runs when a server is indexed for the first time.

### Shard mapping — two layers

- **Persistent shard mapping** lives in **Cassandra**, their primary persistent store. Source of truth.
- **Shard mapping cache** in **Redis**. Hitting Cassandra per message during ingest is too slow; Redis lets workers `MGET` many mappings at once to figure out routing quickly.

### Shard allocator — load-aware, via a Redis sorted set

When a server is indexed for the first time, something has to pick which Shard holds it. Because Shards are Discord's own abstraction, they can be clever:

- A Redis **sorted set** holds all Shards, scored by load.
- **Lowest score wins** — that's the next allocation.
- The score is **incremented on each new allocation**, and **each indexed message has a probability of incrementing its Shard's score** too.
- Net effect: as a Shard accumulates data, it becomes progressively less likely to receive new servers.

The probabilistic increment is the elegant bit — it's a cheap sampled approximation of "how much data is actually in this Shard," without maintaining exact counters on the hot path. It also naturally causes very chatty servers to push their own Shard out of the allocation pool, which is why large servers tend to end up with their own Shard.

### Service discovery

**etcd**, already used elsewhere in their stack. ES nodes announce themselves; nothing about cluster topology is hardcoded in the application.

### Search API

An endpoint clients query, which **enforces permission checks** so users only search messages they can actually access. Worth noting because it's easy to forget that in a permissions-heavy product, search is an authorization surface as much as a retrieval one.

---

## 5. Index configuration and the data model

Elasticsearch's own index template creates each index with:

- **One shard** — no ES-level sharding; Discord already did it.
- **Replication factor 1** — one replica, so a single node failure is survivable. (Replicas also serve queries, so they add read throughput.)
- **Refresh interval: 60 minutes** — wildly non-default, explained in §6.
- **A single document type:** `message`.

So Elasticsearch is being used for exactly two of its capabilities — **replication/balancing between nodes**, and **Lucene indexing** — and nothing else.

### The document model: index everything, store almost nothing

Raw message JSON isn't searchable in a useful form, so each message is transformed into a set of metadata fields.

Two decisions worth internalizing:

**1. No timestamp field.** Discord IDs are **Snowflakes**, which embed a timestamp. Before/on/after queries are implemented as **min/max ID range queries**. One less field to index, and range queries on the ID come free.

**2. Fields are indexed but not stored.** Most fields exist only in the **inverted index**. The only fields actually stored and returned are the **message ID, channel ID, and server ID**.

Consequences of (2):

| Gain | Cost |
|---|---|
| **Message data is never duplicated into Elasticsearch** — no paying twice for disk. Directly serves the "shouldn't cost more than storing messages" requirement. | Search results must be **hydrated from Cassandra**. |
| | **Elasticsearch can't do result highlighting**, since it doesn't hold the text. |

Both costs turned out cheap. The hydration round-trip was free in practice because the UI already needed to pull message *context* (two messages before and after) from Cassandra anyway. And highlighting was reimplemented by building the tokenizers and language analyzers into the **client**, which they describe as easy.

**This is the sharpest idea in the post:** Elasticsearch holds only pointers. It's a *reverse index over* the data, not a *copy of* the data.

---

## 6. The war story: refresh interval

This is the most-cited part of the article, and the most transferable.

### The symptom

They deployed a 3-node ES cluster to production and queued **the 1,000 largest Discord servers** for indexing. Two alarming metrics:

1. **CPU usage far higher than expected.**
2. **Disk usage growing far too fast** for the volume of messages being indexed.

They killed the jobs and went home confused.

### The overnight mystery

Next morning: **disk usage had shrunk dramatically**. Had ES thrown data away? They ran a search against an indexed server — results came back correctly, and fast.

### The diagnosis

Elasticsearch's default **refresh interval is 1 second** — that's what provides near-real-time search. Every second, **across a thousand indices**, ES was flushing its in-memory buffer to a new Lucene segment and opening it for search.

The result was an enormous pile of tiny segments — expensive in CPU to create and to search, and space-inefficient on disk. Overnight, while idle, ES **merged** those tiny segments into far fewer, far larger, far more compact ones. That's why disk usage collapsed on its own.

### The fix, and why it wasn't enough

Confirming it was easy: drop all indices, set the refresh interval to an arbitrarily large value, re-run the same indexing jobs. CPU dropped to nearly nothing during ingest and disk growth returned to normal.

**But simply disabling refresh doesn't work in production.** A server might go hours without a single search — and then when someone does search, they'd get stale results. Near-real-time-on-a-timer is the wrong model when query arrival is bursty and unpredictable.

### The real solution: application-controlled refresh via a Redis dirty-map

They moved refresh control into the application, using an **expiring hashmap in Redis**:

- Key: `prefix + shard_key`
- Value: hashmap of `guild_id` → sentinel meaning "needs refresh"
- TTL: **1 hour**

*(The author notes in passing that in retrospect this could probably have been a set rather than a hashmap.)*

**Indexing lifecycle:**
1. Take N messages off the queue.
2. Route them by `guild_id`.
3. Bulk-insert into the relevant clusters.
4. Mark the Shard and the affected `guild_id`s **dirty** in Redis; expire the key after one hour.

**Search lifecycle:**
1. Look up the Shard for the `guild_id`.
2. Check Redis: is this Shard + guild dirty?
3. If dirty → **refresh the Shard's ES index on demand**, then mark the whole Shard clean.
4. Execute the query and return results.

**Refresh becomes lazy and demand-driven** — it happens exactly when someone is about to search, and never otherwise.

### Why keep the 60-minute auto-refresh at all?

Belt and braces. Redis is a cache and can lose data. If the dirty-map is lost, the underlying auto-refresh guarantees the system **self-corrects within at most one hour** with no operator involvement. This is the self-healing requirement showing up in a small design detail.

---

## 7. Historical indexing: two-phase, for perceived latency

Indexing a large server's entire history takes a long time, and the user is waiting. So it's split:

- **"Initial" phase** — index the **last 7 days** of messages, then make the index available to the user immediately.
- **"Deep" phase** — index the full history afterward, at **lower priority**.

Mechanically, a historical index job is a **cursor into the server's message history plus a fixed unit of work** (default **500 messages**). Each job returns the cursor for the next batch, or `None` when finished. Jobs run in a pool of Celery workers, so they're scheduled alongside the workers' other tasks rather than monopolizing them.

The self-rescheduling-cursor pattern is worth noting: it makes the job resumable, interruptible, and naturally rate-limited, with no long-running process to babysit.

---

## 8. Packaging: a library, not a microservice

They decided **a search microservice wasn't warranted**. Instead they shipped a **library** wrapping the routing and query logic. The only new long-running service is the index workers, which import that same library.

Rationale: the API surface exposed to the rest of the team was small enough that if it ever *did* need to become its own service, it could be wrapped in an RPC layer with little effort. The API workers import the same library to execute searches and serve results over HTTP.

A restrained call, and a good default: don't pay the distributed-system tax until the interface has proven it needs a network boundary.

---

## 9. Production numbers (as of the post, ~3 months after launch)

| Metric | Value |
|---|---|
| Nodes | **14**, across **2 clusters** |
| Instance type | GCP `n1-standard-8` |
| Storage | **1 TB provisioned SSD** per node |
| Documents indexed | **~26 billion** |
| Indices | **~16,000** |
| Peak indexing rate | **~30,000 messages/second** |
| Cluster CPU during rollout | **5–15%** |

Elasticsearch handled it, in their words, without breaking a sweat, and remained stable and consistent from zero to 26 billion documents across millions of Discord servers.

### The four metrics they watch to decide when to grow

1. **`heap_free`** (`heap_committed − heap_used`) — the most important one. When free heap runs out, the JVM is forced into full stop-the-world GCs. If those fail to reclaim enough, the node dies. Before that, it enters a death spiral of constant full GCs reclaiming too little each time. They track this **together with GC stats** — time spent collecting per second.
2. **`disk_free`** — running out means adding nodes *or* disk. On GCP they can grow a disk without rebooting the instance, so the choice between "add node" and "grow disk" depends on how the other three metrics look. High disk with everything else healthy → just grow the disk.
3. **`cpu_usage`** — measured at **peak hours**, not average.
4. **`io_wait`** — I/O getting too slow.

The article shows heap-free and GC-time graphs for an unhealthy (heap-exhausted) cluster versus a healthy one — the GC-time signature is the tell.

### Stated future work

- Spin up **more clusters** so newly indexed servers land on them, which the weighted shard allocator handles automatically.
- **Cap master-eligible nodes** as data nodes are added to existing clusters.
- Possibly write **index migration between clusters**, to shed load or to give an exceptionally chatty server its own index — though the weighted allocator already tends to give large servers their own Shard.

---

## 10. Transferable lessons

1. **Push sharding up into your application when blast radius matters more than convenience.** Many small clusters beat one large cluster if you can tolerate the routing complexity — and it converts "cluster down" from an outage into a degradation.
2. **Make your index disposable.** Lazy indexing plus rebuild-on-demand turns index corruption from an incident into a background job. This is what makes "self-healing with no devops team" credible.
3. **Store pointers, not payloads.** Index for retrieval, hydrate from the system of record. You avoid duplicating storage cost, and you were probably fetching from the store anyway for surrounding context.
4. **Derive fields you don't need to store.** Snowflake IDs made a timestamp field unnecessary and turned time ranges into ID ranges.
5. **Default refresh interval is a scaling trap.** Near-real-time is a *cost*, paid in segments, CPU, and disk. If your workload is bulk ingest with sparse queries, invert it: **refresh on read, not on a timer.**
6. **Let product behavior relax technical constraints.** "Users search history, not the message just sent" is what made bulk indexing acceptable in the first place.
7. **Two-phase indexing buys perceived speed.** Recent-first, then backfill at lower priority.
8. **Cursor-based jobs that return their own successor** are resumable, schedulable, and don't need supervision.
9. **Library before microservice.** Keep the surface small enough that the RPC boundary stays a future option rather than a present obligation.
10. **Watch heap-free plus GC time**, not just CPU. JVM heap exhaustion is the failure mode that kills Elasticsearch nodes.

---

## 11. Caveats when reading this in 2026

- **This is a 2017 snapshot** of an early-stage system, written when Discord had no dedicated devops team. Several choices — a shared library instead of a service, hand-rolled shard allocation — are explicitly sized to that stage of company.
- **The numbers are from three months post-launch.** 26 billion documents and 14 nodes were the state *then*; Discord's scale today is orders of magnitude larger.
- **The storage layer underneath has since changed.** Discord's later engineering writing describes migrating their message store from Cassandra to ScyllaDB. The search design here assumes Cassandra as the hydration source; the pattern is unchanged, the backing store isn't.
- **Elasticsearch itself has moved on.** Zen Discovery was replaced by a new coordination subsystem in ES 7; mapping types were removed; ILM, data streams, and searchable snapshots now exist; and per-request/per-index refresh control has more built-in options than it did in 2017. The `refresh_interval` lesson still applies exactly as written.
- **The application-level sharding tradeoff is real.** You are opting out of ES's rebalancing, cross-index relevance, and cluster-wide aggregation in exchange for isolation and disposability. That's the right trade for chat search partitioned by server; it would be the wrong trade for a corpus where queries routinely span the whole dataset.