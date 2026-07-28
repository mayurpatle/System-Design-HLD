# KIP-500: Replace ZooKeeper with a Self-Managed Metadata Quorum — Detailed Summary

**Source:** Apache Kafka wiki (cwiki.apache.org), authored by **Colin McCabe** (Aug 2019, last modified Jul 2020). **Status: Accepted.** This is the *vision* KIP — deliberately light on RPC formats and on-disk layouts, modeled on KIP-4's approach of painting the big picture and letting follow-on KIPs fill in details. Early access in Kafka 2.8 (2021), production-ready in 3.3, ZooKeeper removed entirely in **Kafka 4.0 (2025)**. The result is universally called **KRaft** (Kafka Raft).

**The two stated goals:** manage metadata in a **more scalable and robust** way (supporting far more partitions), and **simplify deployment and configuration**.

---

## 1. Motivation I: "Metadata as an Event Log" — the philosophical core

The most quotable framing in the whole KIP:

> We often talk about the benefits of managing state as a stream of events… **However, although our users enjoy these benefits, Kafka itself has been left out.**

Kafka told the world to model state as an ordered log, while internally treating metadata changes as **isolated, unrelated events**. That inconsistency produced concrete bugs:

- **Push-based propagation is lossy.** The controller pushes `LeaderAndIsrRequest` / `UpdateMetadata` to brokers. A broker can receive **some changes but not others**; the controller retries a few times and eventually **gives up**, leaving brokers in a **divergent state**.
- **ZooKeeper is the store of record, but the controller's in-memory state often disagrees with it.** When a partition leader changes its ISR in ZK, the controller typically doesn't learn about it **for many seconds**.
- **There's no generic way to follow the ZooKeeper event log.** ZK offers only **one-shot watches**, limited in number for performance reasons. When a watch fires it says *"something changed"* — not *what* it changed to. By the time the controller re-reads the znode and re-arms the watch, the state may have moved again. With no watch set, the controller **may never learn of the change at all**.
- The blunt admission: **in some cases, restarting the controller is the only way to resolve the discrepancy.**

The fix is conceptually simple and architecturally profound: **store metadata in Kafka itself**. Brokers **consume metadata events from a log** instead of receiving pushes, so changes always arrive **in the same order**, brokers can **persist metadata locally in a file**, and on startup they need only read **what changed** rather than the full state — supporting **more partitions with less CPU**.

*(This is the Kreps "The Log" thesis turned inward: the system that taught everyone to use logs finally used one for its own control plane.)*

## 2. Motivation II: Simpler deployment and configuration

- **Two distributed systems to learn.** ZooKeeper has its own config syntax, management tools, and deployment patterns — a daunting requirement, especially for admins not fluent in operating Java services. Unifying massively improves the **"day one" experience** and broadens adoption.
- **Split configuration invites security mistakes.** The named example: an admin sets up **SASL on Kafka** and believes all network traffic is secured — but ZooKeeper must be **separately secured too**. One system means **one uniform security model**.
- **Enables a future single-node Kafka mode** for quick testing without starting multiple daemons — impossible while ZK is mandatory. (This became real: `kafka-storage format` + a single combined-mode process, the basis of today's one-container Kafka.)

## 3. Architecture

### 3.1 The picture

**Before:** N broker nodes + an external ZooKeeper ensemble (typically 3). A controller broker loads state from ZK after election and **pushes** updates outward. The KIP notes the diagram understates the mess: **every** broker talks to ZK, and **external CLI tools can modify ZK state without the controller's involvement** — which is precisely why the controller can't trust its own memory.

**After:** three **controller nodes** replace the three ZooKeeper nodes, running in separate JVMs from brokers. They elect a leader among themselves; **brokers pull metadata from that leader**. In the KIP's words, the arrows now point *toward* the controller rather than away. Controllers may be **co-located with brokers** (combined mode) or fully separate — a deployment choice, not an architectural one.

### 3.2 The controller quorum

- The controller nodes form a **Raft quorum managing the metadata log**, which contains **every change to cluster metadata** — topics, partitions, ISRs, configs: everything ZooKeeper held.
- Raft elects a leader **without any external system**; that leader is the **active controller**, which handles all broker RPCs. Followers replicate the log and act as **hot standbys**.
- **The failover payoff:** because followers already track the latest state, **controller failover no longer requires a lengthy state-reloading period**. (Under ZK, a new controller had to read all metadata out of ZooKeeper before it could act — the dominant term in failover time on large clusters.)
- Availability math is unchanged from ZK: a majority must be up — **3 controllers survive 1 failure, 5 survive 2**.
- Controllers periodically **snapshot metadata to disk**. Conceptually like compaction, but the code path differs: they can write from **memory** instead of re-reading the log. (Detailed in KIP-630, Kafka Raft Snapshot.)

### 3.3 Broker metadata management: `MetadataFetch`

Brokers **fetch** updates from the active controller via a new **MetadataFetch API** that behaves like an ordinary fetch request: the broker tracks the **offset** of its last update and asks only for newer ones.

- Brokers **persist fetched metadata to disk**, enabling very fast startup **even with hundreds of thousands or millions of partitions**. (The KIP flags this as an optimization droppable from v1.)
- Normally only **deltas** move over the wire; if a broker is too far behind or has no cache, the controller sends a **full metadata image** instead.
- **MetadataFetch doubles as the heartbeat** — the same request that keeps the broker current also proves it's alive. Elegant: liveness and freshness become the same signal.
- Forward-looking note: the same incremental-update infrastructure should eventually serve **clients**, which vastly outnumber brokers and increasingly subscribe to many partitions. (This became **KIP-1102 / KIP-951**-era client metadata work.)

### 3.4 The broker state machine — and a real bug it fixes

Brokers now **register with the controller quorum** instead of ZooKeeper, and the active controller removes a broker that hasn't sent a MetadataFetch heartbeat recently.

**Four states:**

| State | Meaning |
|---|---|
| **Offline** | Not running, or doing single-node startup work (JVM init, log recovery) |
| **Fenced** | Running but **not responding to client RPCs** — entered at startup while catching up on metadata, and re-entered whenever it **can't contact the active controller**. Fenced brokers are **omitted from the metadata sent to clients**. |
| **Online** | Ready to serve clients |
| **Stopping** | Received SIGINT; still running while partition leaders migrate off. The controller signals final shutdown via a result code in `MetadataFetchResponse`, or the broker exits after a timeout. |

**The bug this closes (worth memorizing):** under ZooKeeper, a broker that could reach ZK but was **partitioned from the controller** kept serving clients while receiving **no metadata updates**. So a producer using `acks=1` could **keep producing to a broker that was no longer the leader** — it simply never received the `LeaderAndIsrRequest` moving leadership. In KRaft, **cluster membership is integrated with metadata updates**: a broker that can't receive metadata **cannot remain a member**, so it fences itself out rather than silently serving stale leadership.

### 3.5 Killing the write-anywhere problem

- **Operations formerly done by direct ZK writes become controller operations** — config changes, ACL alterations under the default Authorizer, and so on. New clients send them to the active controller; older clients hitting a random broker have their requests **forwarded**, preserving compatibility (KIP-590).
- **New controller APIs replace remaining direct-ZK writes** — most notably, a partition leader wanting to change the ISR now **makes an RPC to the controller** instead of editing ZooKeeper (KIP-497). This is the change that finally makes the controller's in-memory state authoritative.
- **Tools stop touching ZooKeeper**; KIP-4 began this work years earlier and it was nearly complete by then (finished via KIP-555).

The unifying theme: **one writer, one ordered log, one truth.** Every "someone else can mutate the state behind the controller's back" path is closed.

## 4. Compatibility and the "bridge release"

- **Client compatibility preserved** — some old clients just take a less efficient path (broker-forwarded requests).
- **The bridge release** is the migration linchpin: a Kafka version where ZooKeeper still exists but is a **well-isolated dependency**, with essentially all ZK access confined to the controller. You can upgrade **any version → bridge release → post-ZK release**, but not skip the middle step.
- **Rolling upgrade sequence:** (1) reach the bridge release; (2) start controller quorum nodes configured with the ZK address — the active controller **writes itself into `/brokers/ids` and overwrites the `/controller` znode** so no un-upgraded broker can ever become controller, then **loads all of ZooKeeper's state into the metadata log**, after which **the quorum is the store of record**; (3) roll the brokers (new ones ignore any leftover ZK config; the controller still speaks legacy push to un-rolled brokers during the transition); (4) roll the controllers with ZK removed from their config. No concurrent-modification worries, because in the bridge release neither tools nor non-controller brokers write to ZK. *(The productionized version of this is **KIP-866**, which writes metadata to both stores during migration so you can roll back to ZooKeeper until the final step.)*

## 5. Rejected alternative: pluggable consensus

Why not make the metadata store pluggable (etcd, Consul, …)? Four reasons, all sharp:

1. It **addresses neither goal** — ZK-like APIs don't let you treat metadata as an **event log**, and an external system keeps deployment complex.
2. **Test-matrix explosion** — either run system tests against every backend (resource blowout) or leave some users under-tested.
3. **Least-common-denominator APIs** — you could only use features every backend supports, making optimization difficult.
4. Implicitly: the integration *is* the point; the win comes from metadata being **Kafka data**.

## 6. Follow-on KIPs and intellectual lineage

The KIP is explicitly a roadmap; the implementation arrived as **KIP-455** (replica reassignment API), **KIP-497** (inter-broker AlterISR), **KIP-543/555** (de-ZooKeeper the tools), **KIP-589** (replica state API), **KIP-590** (redirect ZK mutation protocols to the controller), **KIP-595** (the Raft protocol for the metadata quorum), **KIP-630** (Raft snapshots), **KIP-631** (the quorum-based controller), and later **KIP-866** (ZK→KRaft migration).

**References cited** — a good reading map in themselves: Ongaro & Ousterhout's **Raft** paper; **HDFS** (metadata via write-ahead logging — the NameNode edit log is the direct ancestor); and Microsoft Research's **Tango: Distributed Data Structures over a Shared Log**, which is the academic statement of "build your metadata structures as views over a shared log."

---

## 7. Important concepts (highlight reel)

- **Metadata as an event log** — the central idea; ordered, replayable, offset-addressable metadata replaces ad-hoc push notifications. Kafka finally applying its own thesis to itself.
- **Push vs pull inversion** — brokers *pull* deltas (MetadataFetch) instead of the controller *pushing* full state. Pull gives ordering, resumability from an offset, natural backpressure, and eliminates the "missed one update, diverged forever" class of bug.
- **Heartbeat = metadata fetch** — liveness and metadata freshness collapse into one mechanism, which is what makes membership and metadata *consistent by construction*.
- **Fenced state** — a broker that can't see the controller **removes itself from service** rather than serving possibly-stale leadership; closes the `acks=1`-to-a-deposed-leader hole.
- **Hot-standby controllers** — followers already have the log, so failover skips the reload phase that dominated ZK-era controller failover on big clusters.
- **Snapshots from memory** — metadata log compaction that reads state from RAM rather than replaying disk.
- **Single writer, single truth** — every direct-ZK mutation path (tools, ISR updates by leaders, config changes) is rerouted through the controller. The root cause of ZK-era discrepancies was *multiple writers to the store of record*.
- **Bridge release** — the general pattern for removing a deep dependency from a distributed system: first isolate all touch points behind one component, then swap that component. Worth stealing for your own migrations.
- **Rejecting pluggability** — a rare, well-argued case for *less* abstraction: pluggability would have forced least-common-denominator APIs and an untestable matrix.
- **Deployment topology is a choice, not architecture** — controllers can be separate nodes or co-located (combined mode); the protocol doesn't care.

## 8. Real-world use cases

**Where KRaft changes your operational life:**

- **Running Kafka anywhere at all — the "day one" case.** Local dev, CI pipelines, and Docker Compose used to need a ZooKeeper container beside every Kafka container. Post-KRaft, a **single-process Kafka** (`KAFKA_PROCESS_ROLES=broker,controller`) is the default for Testcontainers-based integration tests and local development. If you spin up Kafka for a Spring Boot service's integration tests, this KIP is why that's now one container and a few seconds.
- **Kubernetes/EKS deployments.** One StatefulSet instead of two, one set of health probes, one security configuration, one upgrade path. Operators like Strimzi moved to KRaft precisely because the two-system topology was the dominant source of deployment complexity.
- **Large-partition clusters.** The scalability ceiling was metadata propagation: ZK-era controller failover on a cluster with hundreds of thousands of partitions could take **minutes** of unavailability while the new controller reloaded state; KRaft makes it near-instant, and delta-based MetadataFetch plus local caching makes **broker startup** fast at high partition counts. Practical effect: the supported partition count per cluster rose by roughly an order of magnitude, so you stop sharding into multiple clusters purely to dodge metadata limits.
- **Security/compliance work.** "We enabled SASL/TLS on Kafka" is now a complete statement. Under ZK you had to separately secure ZooKeeper (and its ACL model differed), which was a genuine and common audit finding.
- **Incident classes that disappear:** brokers with divergent metadata after a missed controller push; a network-partitioned broker still accepting `acks=1` writes as a deposed leader; controller/ZK state disagreement resolvable only by restarting the controller. If you've ever seen "restart the controller to fix it" in a Kafka runbook, that instruction is a KRaft-era relic.
- **Migration planning (very real work through 2024–25).** Any cluster still on ZooKeeper must reach a bridge release and then migrate via **KIP-866** (dual-write with rollback until the final cutover) before moving to **Kafka 4.0**, which has **no ZooKeeper mode at all**. Knowing the sequence — upgrade to bridge → start controllers in migration mode → roll brokers → roll controllers with ZK removed — is directly employable knowledge and a common interview/ops question right now.

**Transferable design patterns (the reason this is "required reading"):**

- **Eat your own abstraction.** The single most instructive point: Kafka's control plane had the exact problems Kafka's product solves. Ask of any system you build — *are we managing our own internal state with the discipline we sell to users?*
- **Log-based state propagation beats push notifications.** Push means "did everyone get it?" with retries and give-up semantics; a log means "everyone is at some offset," which is checkable, resumable, and ordered. Applies to config distribution, feature flags, service discovery, cache invalidation — any fan-out of state changes.
- **Watches are a weak primitive.** One-shot, count-limited, and they tell you *that* something changed, not *what* — a known-lossy design that any etcd/ZK/Consul-based system inherits. Prefer an ordered change feed with offsets.
- **Membership should be derived from the same channel as state.** Tying liveness to the metadata-fetch heartbeat is what makes "in the cluster" and "up to date" the same predicate — the general fix for split-brain-ish behavior where a node is alive but blind.
- **Bridge releases for dependency removal**, and **fencing over trusting** for nodes that lose contact with the control plane.
- **Sometimes reject the abstraction layer.** The pluggable-consensus rejection is a strong, citable argument that generality has costs — test matrix, least-common-denominator APIs, lost optimizations.

## 9. TL;DR

Kafka stored its own metadata in **ZooKeeper** and propagated changes by having the controller **push** `LeaderAndIsr`/`UpdateMetadata` RPCs to brokers — a scheme where brokers could receive **some updates but not others** and diverge, where the controller's memory routinely disagreed with ZK (ISR changes could take **many seconds** to surface), where ZK's **one-shot, count-limited watches** signal only *that* something changed, and where **restarting the controller** was sometimes the only cure. Meanwhile operators had to run, configure, and secure **two distributed systems**. KIP-500 replaces ZooKeeper with a **self-managed Raft quorum of controller nodes** that own a **metadata log** containing every cluster-metadata change; the leader is the **active controller**, followers are **hot standbys** (so failover skips state reloading), and **brokers pull deltas** via a new **MetadataFetch** API that **doubles as their heartbeat** — so cluster membership and metadata freshness become the same thing, and a broker cut off from the controller **fences itself** instead of serving stale leadership to `acks=1` producers. Direct-ZooKeeper writes from tools, config changes, and even leader ISR updates are all rerouted through the controller, giving **one writer and one ordered source of truth**; migration runs through a **bridge release** that isolates ZK before removing it. Pluggable consensus (etcd/Consul) was rejected because it would fix neither goal while exploding the test matrix and forcing least-common-denominator APIs. The result — **KRaft** — is one system instead of two, uniform security config, single-node Kafka, near-instant controller failover, fast broker startup from locally cached metadata, and roughly an order of magnitude more supported partitions; ZooKeeper was removed outright in **Kafka 4.0**. The deepest lesson is the one the KIP states about itself: Kafka told everyone to manage state as an event log while **leaving itself out** — and fixed it.