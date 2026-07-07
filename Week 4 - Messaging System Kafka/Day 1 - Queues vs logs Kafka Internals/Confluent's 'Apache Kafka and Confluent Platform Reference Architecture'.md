# Apache Kafka & Confluent Platform — Reference Architecture

**Publisher:** Confluent, Inc. (© 2014–2020)
**Original author (early editions):** Gwen Shapira
**Audience:** Data architects and system administrators planning **self-managed** production deployments of Apache Kafka + Confluent Platform, on-premises or in a public cloud.

> Summary based on the 2020 edition. Where the document reflects the pre-KRaft era (ZooKeeper mandatory, Auto Data Balancer, etc.), that's flagged in the "How this has aged" section at the end — worth knowing since several assumptions here have since changed.

---



## Purpose & Scope

The paper is a **deployment guide**, not an internals paper. It answers three practical questions for each piece of the platform: *what is this component, when do you need it, and how do you deploy it for HA and scale?* It then gives concrete **capacity-planning** rules (storage, memory, CPU, network) and **hardware/cloud-instance** recommendations for both large and small clusters.

It explicitly scopes itself to **self-managed** deployments and points readers wanting a fully-managed service to **Confluent Cloud**. It also stresses that a general paper can't capture workload-specific tuning (access patterns, SLAs) and recommends engaging Confluent Professional Services for tailored design.

**Framing of the platform:** Apache Kafka provides three fundamentals — **storage** (Kafka core), **integration** (Kafka Connect), and **processing** (Kafka Streams). Confluent Platform bundles Kafka plus the surrounding projects (multi-language clients, 100+ connectors, Schema Registry, REST Proxy, ksqlDB) into a "one-stop shop," and Confluent Enterprise adds Control Center, Replicator, Auto Data Balancer / Self-Balancing Clusters, Tiered Storage, Multi-Region Clusters, Schema Validation, the Kubernetes Operator, Ansible playbooks, and enterprise security (RBAC, audit logs, secret protection).

---



## Part 1 — Component Architecture (what, when, how)



### ZooKeeper

Centralized coordination service; **mandatory** in every Kafka cluster in this edition. Brokers use it for **cluster membership** and **controller election**. HA rules: use **3 nodes** (survives 1 failure) or **5 nodes** (survives 2 failures), and the count **must be odd**. All nodes are equivalent and run on identical hardware. *(The paper notes KIP-500 will eventually remove ZooKeeper — see the aging note below.)*

### Kafka Brokers

The main **storage + messaging** component. Topics are sharded into **partitions** (ordered, immutable logs), which are **replicated and distributed** for HA. In Confluent Platform a broker is branded **Confluent Server**.

- Run **at least 3 brokers** on separate servers → allows replication factor 3 and survival of **2 node failures without data loss**.
- Caveat: with exactly 3 brokers, if one is down you **can't create new topics with 3 replicas** until it recovers. If you frequently create topics, run **at least 4 brokers**.
- Low-load clusters *may* co-locate brokers with ZooKeeper (with separate disks for ZK), but high-throughput deployments should give brokers **dedicated nodes**.



### Kafka Connect Workers

Pluggable integration framework — source connectors pull data in, sink connectors push data out (e.g., MySQL→Kafka via JDBC, Kafka→Elasticsearch). 100+ connectors on **Confluent Hub**. Two deployment modes:

- **Standalone** — single process on the machine that has the files/app (like Flume/Logstash). Deployed *on* the integrated servers.
- **Cluster mode (recommended for production)** — multiple **workers** discover each other using **Kafka brokers as the coordination layer**, auto load-balance and failover. Managed via a **REST API** (start/stop/pause/resume/configure connectors from any worker). Workers are **stateless** → safe to containerize; usually run on their own machines.



### Kafka Clients

Producer/consumer libraries embedded in applications. The **Java** client ships with the Kafka packages. The **C, C++, Python, .NET, Go** clients are all built on **librdkafka** (Confluent's C/C++ client) for consistent APIs, semantics, and performance across languages. Install librdkafka on servers running those non-JVM clients.

### Kafka Streams API

A **library** (not a server) embedded in your app for scalable, fault-tolerant, **stateful** stream processing — with proper **event-time vs. processing-time** handling, late-data handling, and state management. Because it's a library:

- No dedicated servers, but the **apps using it** need resources; benefits from **higher core counts** (parallel tasks per partition/stage).
- Run **multiple app instances across servers** → the library handles load balancing and failover.
- Uses embedded **RocksDB** for local state → use **persistent SSDs**.



### Confluent ksqlDB Server

Streaming **SQL engine** for continuous queries over Kafka, with an **embedded Kafka Connect** instance. CLI acts as a client (runs anywhere with server access); the **server** runs the query engine. Queries operate on streams/tables backed by Kafka topics. Deployed as a **cluster** sized by concurrent-query count and complexity. Benefits from higher CPU, good network, and **SSD for RocksDB** state. Suggested starting point: **4 cores / 32 GB RAM / 100 GB SSD / 1 Gbit network**. **No multi-tenancy** — use one pool of ksqlDB servers per use case.

### Confluent REST Proxy

HTTP server exposing a **RESTful interface** to Kafka (produce/consume, view cluster state, admin actions) without native clients. **Optional** — skip it if apps only use native clients. Deploy on separate machines; for throughput/HA put **multiple instances behind a sticky load balancer**. Key detail: with the high-level consumer API, **all requests for a given consumer must hit the same proxy node** — use the sticky LB for everything *except* consuming, and for consuming use the IP/host returned by the create-consumer call. Stateless → containerizable. (Confluent Server has an **embedded REST Proxy** for admin ops only, not produce/consume.)

### Confluent Schema Registry

RESTful serving layer for metadata — stores and versions **Avro schemas**, enforces **compatibility settings**, enables schema evolution, and ships serializers that plug into clients. Deployment:

- Usually its own servers; in small installs can co-locate with REST Proxy + Connect.
- For HA run multiple instances in a **leader–follower** architecture: **only the leader writes** to the underlying Kafka log; **all nodes serve reads**; followers forward writes to the leader.
- Schemas are stored **in Kafka**, so registry nodes are **stateless / no storage** → containerizable.



### Confluent Replicator

Manages **multi-cluster / multi-datacenter** replication with centralized config. Unlike open-source **MirrorMaker**, it replicates **topic configuration in addition to messages**. Built on the Connect framework — install it on the **Connect nodes of the destination cluster** (on all of them if multiple), which gives it scale and built-in failover.

### Confluent Self-Balancing Clusters

Part of Confluent Server; auto-optimizes partition placement. Evaluates broker/partition/leader counts and partition sizes, decides a balanced placement, and reassigns replicas — e.g., when a broker is added, it **moves partitions to the new broker**. Throttles rebalance traffic to a fraction of network capacity to protect production. Enabled via config, runs continuously. **Recommended over the older Auto Data Balancer** — don't run both simultaneously.

### Confluent Control Center

Web-based management/monitoring tool. Three functions: **(1) end-to-end stream monitoring & alerting** (verify every message is received exactly once, measure end-to-end latency, alert on SLA misses); **(2) multi-cluster monitoring & replication management**; **(3) Kafka Connect config/monitoring**. Runs on a **single machine** — give it a **dedicated, well-resourced** box.

### Tiered Storage

Offloads older Kafka data to **cheap object storage** for **infinite retention** and elastic scaling. Operators define a **"hot set"** = how long data stays in Kafka before offload. Brokers transparently fetch offloaded data from object store on demand → **no producer/consumer changes** and no third-party archival tooling. Config-enabled in Confluent Server.

### Multi-Region Clusters

Runs a **single** Kafka cluster across multiple datacenters/AZs with automated client failover. Three mechanisms mitigate cross-DC network cost/latency:

- **Follower-Fetching** — consumers read from **local replica followers** instead of the leader, cutting cross-DC traffic.
- **Observers** — a new replica type that keeps up with the leader but by default **does not join the ISR** → enables **async replication**; clients can consume from observers via follower-fetching.
- **Replica Placement** — strategy for assigning replicas, relying on each broker's `broker.rack` property.

Distinct from Replicator-based HA; migrating between the two needs care (metadata like topic names / broker IDs can collide).

### Schema Validation

Enforces schemas **at the broker**, with **per-topic** granularity. Previously the broker accepted any data from an authorized client; now an operator can require a schema and **reject** messages lacking one. Enabled per topic via the Confluent CLI.

### Enterprise Security: RBAC, Audit Logs, Secret Protection

- **RBAC** — centralized, fine-grained access control via role bindings (role + resource + principal), managed through the **Metadata Service**.
- **Structured Audit Logs** — record authorization decisions (ACL/RBAC permission checks) into dedicated Kafka topics via the Confluent Server Authorizer, in chronological order.
- **Secret Protection** — encrypts sensitive config (e.g., `ssl.key.password`) so secrets aren't stored as clear text; the CLI rewrites config to pull from a secret provider plus an encrypted secrets file.



### Deployment Tooling

- **Confluent Operator** — Kubernetes Operator API for automated deploy + lifecycle ops (rolling upgrades, recovery) on **containerized** environments.
- **Ansible Playbooks & Templates** — same automation goals for **non-containerized** bare-metal / VM deployments.

---



## Part 2 — Reference Cluster Topologies



### Large Cluster (high-throughput, long-term scale)

**Every component on its own servers**, so each layer scales independently. Example: if REST Proxy becomes the bottleneck while brokers still have headroom, add REST Proxy nodes without touching the rest.

### Small Cluster (early adoption)

Fewer servers with **multiple components co-located**, but still HA-capable. Recommendation: keep **resource-intensive components dedicated** (notably **Control Center** and **ksqlDB**). As bottlenecks appear, peel components off onto their own servers, then keep scaling — evolving naturally toward the large-cluster topology.

---



## Part 3 — Capacity Planning

**Golden rule:** every component is scalable and most are stateless, so monitor each node and add capacity as needed. The exception is the **Kafka broker** (the stateful storage layer) — **add capacity and rebalance before any resource hits 60–70%**, because rebalancing itself consumes resources (more headroom = faster rebalance).

### Storage

- Mostly a concern for **ZooKeeper** and **brokers**.
- **ZooKeeper:** prioritize **low-latency writes** to the transaction log → **dedicated disk** for the ZK txn log even when co-located.
- **Brokers:** main storage layer; typical deployments use **6–12 disks of ~1 TB each**. Actual need depends on topic/partition counts, write rate, and retention. **SSD vs. spinning disk** trade-off matters because most deployments store multiple partitions per disk (seek time affects throughput). Filesystems: **ext4 or XFS**. **Avoid SAN/NAS** shared storage (untested, hard to tune).
- **Tiered Storage** solves indefinite-retention needs via object storage + hot set.
- **Control Center:** ≥ **300 GB**, preferably SSD (RocksDB local state).
- **Kafka Streams / ksqlDB:** RocksDB local state; size depends on partitions, key cardinality, key/value size, window retention. Start with **100–300 GB**, SSD for ksqlDB. **Raise file descriptors to 64K+** (Streams uses many for RocksDB).



### Memory

- **ZooKeeper:** JVM heap, **4 GB** typically enough. Too small → frequent GC/high CPU; too large → long GC pauses risking connectivity loss.
- **Brokers:** use **both JVM heap and OS page cache**. Heap is for **replication** (default 1 MB/partition via `replica.max.fetch.size`; capped at 10 MB total by `replica.fetch.response.max.bytes` since Kafka 0.10.1 / CP 3.1) and **log compaction**. **4 GB heap** for small–medium deployments. **Critically:** consumers should **read from page cache**, not disk — size the cache from write rate × expected consumer lag. *Example: 20 GB/hour/broker written, consumers allowed to lag 3 hours → reserve **60 GB** for OS page cache.*
- **Kafka Connect:** low memory itself, but buffering connectors → bump heap to **1 GB+**.
- **Control Center:** **≥ 32 GB RAM** (heap can stay small ~3 GB; the rest is RocksDB indexes/caches + page cache).
- **Producers:** more heap → better batching + retry buffering during network/leader issues.
- **Kafka Streams / ksqlDB:** many memory areas — Streams buffer cache (`cache.max.bytes.buffering`, default 10 MB; higher = better), per-partition RocksDB stores, plus a consumer per thread (**1 MB/partition or 50 MB/broker, whichever is lower**). Allocate generously — **32 GB+**.
- **REST Proxy:** ~2 MB/consumer (up to 64 MB on bursty large responses), 64 MB/producer. Start at **1 GB + 64 MB/producer + 16 MB/consumer**.
- **Across the board:** use the JVM **G1 garbage collector**.



### CPU

Most components are **not CPU-bound** — high CPU usually means misconfig, insufficient memory, or a bug. Exceptions:

- **Compression** — recommended (better network + disk), but costs client CPU. Pre-0.10.0 brokers re-compressed on write, adding broker CPU.
- **Encryption (SSL)** — small overhead everywhere; **worse for consumers** because SSL **breaks the zero-copy optimization**, forcing the broker to encrypt before sending → notably higher broker CPU. Large deployments often keep consumers on the **same LAN** as brokers to skip encryption.
- **High request rates** (many clients, or `max.fetch.wait=0`) can saturate a broker → fix with client-side **batching**.
- Many components are **multi-threaded** → favor **more cores over faster cores**.



### Network

- 1GbE clusters typically become **network-bound**; scaling past **100 MB/s** requires a higher-bandwidth network.
- Budget for **replication traffic** plus headroom for rebalancing and bursty clients. Network is the hardest resource to provision (eventual switch limits).
- **Compression** and **larger producer batches** improve effective network utilization (bigger batches → better compression ratio).

---



## Part 4 — Hardware Recommendations (on-prem)



### Large Cluster


| Component           | Nodes                                | Storage                                                                | Memory                                 | CPU                             |
| ------------------- | ------------------------------------ | ---------------------------------------------------------------------- | -------------------------------------- | ------------------------------- |
| **ZooKeeper**       | 5 (fault tolerance)                  | Txn log: 512 GB SSD; data: 2×1 TB SATA RAID 10                         | 32 GB                                  | 2–4 cores (rarely a bottleneck) |
| **Kafka Broker**    | Min 3 (more for storage/RAM/network) | 12×1 TB disks (RAID 10 optional); OS disks separate from Kafka storage | 64 GB+                                 | Usually dual 12-core sockets    |
| **Kafka Connect**   | Min 2 (HA)                           | Install only                                                           | 0.5–4 GB heap (connector-dependent)    | More cores > faster cores       |
| **Schema Registry** | Min 2 (HA)                           | Install only                                                           | 1 GB heap                              | More cores > faster cores       |
| **REST Proxy**      | Min 2 (HA; more for throughput)      | Install only                                                           | 1 GB + 64 MB/producer + 16 MB/consumer | Min 16 cores                    |
| **ksqlDB**          | Min 2 (HA; more for throughput)      | SSD (size by query load/aggregation)                                   | Min 20 GB (Confluent tests use 30 GB)  | Min 4 cores                     |
| **Control Center**  | 1                                    | Min 300 GB (SSD preferred)                                             | 32 GB+                                 | Min 8 cores (ideally more)      |




### Small Cluster


| Component                                  | Nodes | Storage                                                    | Memory                                                                       | CPU                  |
| ------------------------------------------ | ----- | ---------------------------------------------------------- | ---------------------------------------------------------------------------- | -------------------- |
| **ZooKeeper + Broker**                     | Min 3 | 12×1 TB: 1 disk for ZK txn log, 1–2 for OS, rest for Kafka | 64 GB+                                                                       | Dual 12-core sockets |
| **Connect + Schema Registry + REST Proxy** | Min 2 | Install only                                               | 1 GB (Connect) + 1 GB (SR) + [1 GB + 64 MB/producer + 16 MB/consumer] (REST) | Min 16 cores         |
| **ksqlDB**                                 | Min 2 | SSD (size by load)                                         | Min 20 GB (tests use 30 GB)                                                  | Min 4 cores          |
| **Control Center**                         | 1     | Min 300 GB (SSD preferred)                                 | 32 GB+                                                                       | Min 8 cores          |


---



## Part 5 — Public Cloud Deployment

On-prem sizing still applies, with two adjustments: cloud **"virtual cores" are weaker** than datacenter cores (may need more of them), and most providers cap at **10GbE** on top-tier nodes (account for replication traffic). You can standardize on one instance type for automation simplicity (at the cost of over-provisioning) or co-locate services when each instance has enough resources. Node-count recommendations from the hardware section still hold.

**Example instance types (illustrative — cloud offerings change constantly):**


| Component       | AWS EC2                   | Azure         | GCP           |
| --------------- | ------------------------- | ------------- | ------------- |
| ZooKeeper       | m5.large                  | DS3v2         | n1-standard-2 |
| Kafka Broker    | r5.xlarge (EBS-optimized) | DS4v2 / DS5v2 | n1-highmem-4  |
| Kafka Connect   | c5.xlarge                 | A4v2          | n1-standard-4 |
| REST Proxy      | c5.xlarge                 | A4v2          | n1-standard-4 |
| Schema Registry | m5.large                  | A2v2          | n1-standard-2 |
| ksqlDB          | i3.xlarge / r5.xlarge     | —             | n1-highmem-4  |
| Control Center  | m5.2xlarge                | DS4v2         | n1-highmem-8  |


**AWS note:** earlier editions recommended local-SSD "storage-optimized" instances for brokers over EBS; by 2020 Confluent considers **EBS stable enough** for broker latency/throughput, and recommends EBS-Optimized instances for consistent performance. **GCP note:** persistent disks scale to 64 TB (more than Confluent recommends) — follow the on-prem storage sizing anyway.

---



## Key Takeaways

1. **Three-node minimum everywhere HA matters** — 3 (or 5) ZooKeeper for odd-quorum fault tolerance, 3+ brokers for RF=3, 2+ of each stateless service (Connect, Schema Registry, REST Proxy, ksqlDB) behind that.
2. **Statelessness is the scaling lever.** Connect workers, REST Proxy, and Schema Registry hold no local state (SR stores schemas in Kafka) → trivially containerized and horizontally scaled. Brokers are the stateful exception and the one to watch (rebalance at 60–70%).
3. **The broker's real memory story is the OS page cache, not the heap.** Heap stays modest (~4 GB); the page cache — sized by write-rate × consumer-lag — is what keeps consumers reading from memory. SSL breaks zero-copy, which is the main hidden CPU cost.
4. **Separate concerns onto separate tiers** so each scales independently (the whole point of the large-cluster topology); start co-located (small cluster) and peel off bottlenecks over time.
5. **Confluent's value-add over vanilla Kafka** clusters around ops and governance: Control Center (monitoring), Replicator + Multi-Region Clusters (DR/geo), Self-Balancing + Tiered Storage (elasticity/retention), Schema Registry/Validation (governance), and RBAC/audit/secrets (enterprise security).

---



## How This Has Aged (post-2020 context)

Because this is the 2020 edition, a few load-bearing assumptions have since shifted — useful to keep straight if you're referencing it today:

- **ZooKeeper is no longer mandatory.** KIP-500 landed as **KRaft** (Kafka Raft metadata mode), which moves controller/metadata management into Kafka itself. Modern Confluent Platform runs ZooKeeper-less; the "must be odd, 3-or-5 nodes" ZK guidance applies only to legacy clusters.
- **Auto Data Balancer → Self-Balancing Clusters** was already the recommended direction here and is now the default answer for rebalancing.
- **Cluster Linking** later joined/overlapped with Replicator for multi-cluster replication.
- **Cloud instance types** in the tables (m5/r5/c5, DSv2, n1) are dated — newer generations (Graviton-based r7g, etc.) are common now; treat the tables as sizing *shapes* (RAM/CPU/network ratios), not literal picks.
- The document is explicitly about **self-managed** Confluent Platform; **Confluent Cloud** (the fully-managed Kora-engine service) has since become the recommended path for many of these operational concerns.

---

*Reference: "Confluent Platform Reference Architecture," Confluent, Inc., © 2014–2020. Companion doc: "Confluent Platform Reference Architecture for Kubernetes" (Confluent Operator).*