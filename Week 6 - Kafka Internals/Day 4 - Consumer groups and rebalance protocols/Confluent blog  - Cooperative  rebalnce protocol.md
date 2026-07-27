# Incremental Cooperative Rebalancing in Apache Kafka: Why Stop the World When You Can Change It? — Detailed Summary

**Source:** Confluent blog, **Konstantine Karantasis** (Kafka committer, author of the Connect S3 and Replicator connectors and of Incremental Cooperative Rebalancing itself), September 24, 2019. Companion to his Kafka Summit SF 2019 talk. Opens with a Franz Kafka epigraph about coming and going — apt for a post about group membership.

**Why this post matters in the reading sequence:** KIP-429 and KIP-345 are the *specifications*. This post is the **conceptual framing plus the benchmark evidence** — it explains the embedded-protocol architecture that makes client-side rebalancing possible at all, names the four production scenarios that broke stop-the-world rebalancing, and publishes real numbers from a 900-task Connect cluster showing the improvement.

---

## 1. The architecture that enables all of this: the embedded protocol

Kafka clients (consumer, Connect, Streams) form groups via the broker's **group management API**, with a broker acting as **coordinator**. But — and this is the key architectural fact — **group membership is all the broker knows about the group.**

The actual load distribution happens **among the clients themselves**: a leader client is elected, and the members speak a **protocol only the clients understand**, piggybacked inside the group management protocol. This is called the **embedded protocol**. Rebalance protocols are just one use of it; it's a general mechanism for *any* distributed processes to coordinate custom logic without the broker knowing they exist.

Three advantages the post credits to this design:

- **Autonomy** — clients can upgrade or customize their load-balancing algorithms **independently of brokers**. (This is exactly why KIP-429 shipped with zero broker changes.)
- **Isolation of concerns** — brokers offer a generic membership API; balancing details stay in clients, keeping broker code simple.
- **Easier multi-tenancy** — for Connect, which balances *heterogeneous* resources (connectors and tasks) potentially owned by different users, multi-tenancy is handled at the client layer rather than becoming "yet another feature" the broker must understand.

And the simplifying assumption they all shared: **a new rebalance round starts whenever load must be redistributed, and every process releases all its resources first** — the **stop-the-world** model, a term borrowed straight from garbage-collection literature.

## 2. Four scenarios where stop-the-world breaks down

The post is precise about *when* the simple model fails — worth memorizing as a checklist:

1. **Scaling up and down.** The cost of a rebalance is proportional to the **number of resources currently balanced**, not to the size of the change. Starting 10 Connect tasks in an empty cluster is nothing like starting 10 in a cluster already running 100.
2. **Multi-tenancy under heterogeneous loads.** In Connect, adding *someone else's* connector stops *your* connector's tasks. Disruptive at scale, and deeply unintuitive to a user who doesn't own the whole cluster.
3. **Kubernetes process death.** Node failures are routine and orchestrators replace nodes quickly. Ideally the group absorbs a temporary loss without a full rebalance, and hands resources straight back when the node returns.
4. **Rolling bounce.** Planned upgrades cause the same churn deliberately — and a full redistribution is wasted work because the scale-down is only temporary.

Existing workarounds — **splitting clients into smaller groups**, or **inflating rebalance timeouts** — are called out as inflexible. The conclusion: stop-the-world had to be replaced.

## 3. The two principles (the actual definition)

The proposal rests on exactly two ideas:

1. **Complete, global load balancing does not need to finish in a single rebalance round.** It's enough that clients **converge to balance after a few consecutive rebalances**.
2. **The world should not be stopped.** Resources that don't need to change hands should never stop being utilized.

Hence the name:

- **Incremental** — the final balanced state is reached **in stages**; a globally balanced state need not exist at the end of each round. Additionally, a configurable **grace period** lets a departing member return and **regain its previously assigned resources**.
- **Cooperative** — each process is **asked to voluntarily release** the resources that must be redistributed; those resources become available for rescheduling **provided the client releases them on time**.

That second word is doing real work: revocation is a *request*, not a seizure — which is precisely why the protocol needs a follow-up round to complete the handoff, and why it degrades gracefully if a member is slow.

## 4. First implementation: Kafka Connect (KIP-415, Kafka 2.3)

Connect was first because its pain was worst. Its balanced resources are **connectors** (coordination/bookkeeping with the external system) and **tasks** (the actual data transfer).

The insight the post highlights: even though Connect tasks are nearly stateless and restart quickly, stop-the-world still caused **rebalance storms** — consecutive rebalances leaving a cluster minutes from stability. The consequence was cultural as much as technical: clusters were **capped below their real capacity**, giving "the wrong impression that Connect tasks are out-of-the-box heavyweight entities." With ICR, a task becomes what it was always meant to be — **a lightweight thread of execution schedulable anywhere in the cluster** ("Connect tasks are the new threads"). Worker provisioning stays the orchestrator's job (Kubernetes); task scheduling stays Connect's.

### The three worked scenarios

**1. A new worker joins.** Rebalance #1: the leader computes a new global assignment that **revokes one task from each existing worker**. Because that round included revocations, a **second rebalance follows immediately**, assigning the revoked tasks to the new worker. Throughout both rounds, **unaffected tasks keep running**.

**2. An existing worker bounces (temporary).** Worker2 leaves; the leader notices missing connectors/tasks and activates the **scheduled rebalance delay** (`scheduled.rebalance.max.delay.ms`, **default 5 minutes**). While the delay is active the lost tasks stay **deliberately unassigned** — holding a slot open for the returning worker. When Worker2 returns, another rebalance occurs but tasks stay unassigned until the delay expires; then a final rebalance gives Worker2 **its original tasks back**. Temporary absence costs nothing.

**3. An existing worker leaves permanently.** Identical until the delay expires without the worker returning — then the leader **redistributes the orphaned tasks among the remaining workers**.

Note the design shape: **deliberate, bounded procrastination.** The system *chooses to stay temporarily imbalanced* because rebalancing is more expensive than a brief imbalance. (This scheduled delay is the piece KIP-429 deliberately **omitted** for consumers — the consumer equivalent is KIP-345 static membership, whose session timeout plays the same role.)

## 5. The benchmarks (the part that makes the argument concrete)

**Setup:** 3 Connect workers on AWS m4.2xlarge, **90 S3 sink connectors × 10 tasks = 900 tasks**, consuming from Kafka in the same region.

**Rebalance cost and time to stabilize:**

- **Eager:** cost is **proportional to the number of tasks currently running**; the cluster took roughly **14 minutes** to stabilize on startup and **12 minutes** on shutdown.
- **Incremental Cooperative:** balanced all 900 tasks **within about a minute**, and the cost of each individual rebalance was **evidently independent of the number of tasks in the cluster**.

That shift — **O(total resources) → O(changed resources)** — is the headline result.

**Throughput impact** (measured end-to-end, using **committed consumer offsets** rather than raw bytes, so it reflects actual progress under rebalancing; run against a self-managed 5-broker cluster):

| Metric (900 tasks / 3 workers) | Eager | Incremental Cooperative | Improvement |
|---|---|---|---|
| Aggregate throughput (MB/s) | 252.68 | 537.81 | **+113%** |
| Minimum task throughput (MB/s) | 0.23 | 0.42 | +83% |
| Maximum task throughput (MB/s) | 0.41 | 3.82 | **+833%** |
| Median task throughput (MB/s) | 0.27 | 0.54 | +101% |

Throughput improved in **every** category — roughly **doubled** in aggregate and median — and the best-performing task ran **more than 9× faster** than the best under eager. The measurement choice matters: counting only transferred bytes would have hidden the cost, since progress isn't real until offsets commit.

The closing caveat is the honest and important one: throughput converges between protocols **once a cluster stabilizes** — but at large scale, **long rebalance-free periods aren't guaranteed**, so this gap is *typical*, not an artifact of the test window.

## 6. Conclusion and lineage

ICR lets Connect **scale beyond previous limits**, enabling **centralized, manageable deployments** instead of the fragmentation-into-small-clusters workaround. For consumers and Streams, the post points forward to **KIP-429** (consumer incremental rebalancing) and **KIP-441** (smooth scaling out for Streams via warm-up replicas). The full family: **KIP-415** (Connect, 2.3) → **KIP-429** (consumer, 2.4) → **KIP-441** (Streams) → **KIP-345** (static membership) → eventually **KIP-848** (broker-side next-gen protocol).

---

## 7. Important concepts (highlight reel)

- **Embedded protocol:** brokers know only membership; clients own load-balancing logic in a protocol piggybacked on group management. This is why client-side rebalancing evolves without broker upgrades — and it's a genuinely reusable pattern for building coordinated distributed processes on Kafka.
- **Stop-the-world (from GC literature):** cost proportional to *total* resources, not to the *delta*. Naming it after GC is the right intuition — same pathology, same fix (incremental/concurrent collection).
- **Incremental** = converge over a few rounds; a globally balanced state isn't required at the end of every round.
- **Cooperative** = revocation is a **voluntary, time-bounded request**, not a seizure.
- **Scheduled rebalance delay** (`scheduled.rebalance.max.delay.ms`, default 5 min): **deliberate temporary imbalance** to let a bouncing member reclaim its resources. Rebalancing costs more than short-term imbalance.
- **Cost independence:** the crucial measurable property — rebalance cost decoupled from cluster size is what unlocks large-scale deployment.
- **"Connect tasks are the new threads":** when scheduling is cheap, the unit of work can be lightweight — protocol cost was silently shaping how people architected their clusters.
- **Measure progress, not activity:** benchmarking via committed offsets rather than transferred bytes. A transferable lesson for evaluating any pipeline under disruption.
- **Anti-patterns it retires:** splitting into many small groups/clusters, and inflating timeouts to dodge rebalances.

## 8. Real-world use cases

**Where the four failure scenarios show up in practice:**

- **Large Connect clusters doing CDC or sink work.** A team running dozens of Debezium/S3/JDBC connectors on a shared cluster: pre-2.3, deploying one new connector stopped every other connector's tasks — so teams split into many small single-tenant clusters purely to contain the blast radius, multiplying operational overhead. ICR is what makes a **single centralized Connect cluster** viable, and the 900-task benchmark is the direct evidence.
- **Multi-tenant platform teams.** If you operate Connect (or consumer groups) as a shared internal platform, "your deploy paused my pipeline" is a trust-destroying property. Cooperative rebalancing makes tenant isolation real at the protocol level.
- **Kubernetes/EKS deployments with pod churn.** Node failure, eviction, or rescheduling used to trigger full redistribution twice (leave + return). The **scheduled rebalance delay** is designed exactly for the orchestrator-replaces-the-pod case — hold the assignment open, hand it straight back.
- **Rolling upgrades of large fleets.** The temporary-scale-down insight: don't rebalance for a departure you expect to reverse in seconds.
- **Streaming pipelines where throughput under churn is the SLA** — the benchmark's point is that in a large cluster you're rarely in a rebalance-free steady state, so the disrupted-state throughput *is* your real throughput.

**Configuration and operational takeaways:**

- **Connect:** tune `scheduled.rebalance.max.delay.ms` to your worker restart time — long enough to cover a rolling bounce or pod reschedule, short enough that a permanent loss doesn't strand tasks. It's the same knob-shaped decision as `session.timeout.ms` in static membership.
- **Consumers/Streams:** the analogous stack is `cooperative-sticky` (KIP-429) plus `group.instance.id` (KIP-345), with Streams adding warm-up replicas (KIP-441).
- **Benchmark your own rebalances the way this post does** — measure committed-offset progress across a deploy, not steady-state throughput, if you want a number that reflects user-visible behavior.
- **Retire the old workarounds:** if your architecture splits into small clusters or uses inflated timeouts *purely* to avoid rebalance pain, that's technical debt from the eager era.

**Transferable design patterns:**

- **Incremental convergence over atomic reassignment:** don't require a globally correct state at the end of every reconciliation round; converge over a few. This is the shape of Kubernetes controllers, Cruise Control, and any well-behaved shard manager — and a strong HLD answer to "how do you rebalance work without pausing the system?"
- **Cooperative (voluntary, time-bounded) release** rather than forcible preemption, so ownership handoff stays safe without a global lock.
- **Grace periods as a first-class design element:** deliberately tolerating temporary imbalance because the reconciliation is more expensive than the imbalance. Applies to connection pools, shard rebalancers, cache warm-up, and autoscaler cooldowns alike.
- **Delegate the right layer to the right system:** the orchestrator provisions workers; the application schedules work onto them. Overlapping those responsibilities is a common architectural mistake.

## 9. TL;DR

Kafka clients balance load among **themselves** via an **embedded protocol** — the broker only tracks group membership — which buys autonomy, simple broker code, and client-level multi-tenancy. But every rebalance protocol shared one crude assumption: **everyone releases everything, every time** (stop-the-world), so rebalance cost scaled with **total** resources rather than the size of the change. That broke down for scaling, Connect multi-tenancy, Kubernetes pod death, and rolling bounces — with only bad workarounds (many small clusters, inflated timeouts). **Incremental Cooperative Rebalancing** replaces it with two principles: converge to balance over **a few consecutive rounds** rather than one, and **never stop resources that don't need to move** — with revocation as a **voluntary, time-bounded request** and a **scheduled rebalance delay** (default 5 min) that deliberately holds a bouncing member's resources open for its return. First shipped in **Kafka Connect (KIP-415, Kafka 2.3)**, where a 900-task benchmark showed stabilization dropping from **~14 minutes to ~1 minute**, rebalance cost becoming **independent of cluster size**, and end-to-end throughput (measured by committed offsets) **roughly doubling** in aggregate with the top task running **9× faster**. The same design then reached consumers (**KIP-429**) and Streams (**KIP-441**), with static membership (**KIP-345**) attacking the complementary problem — and its core idea, *incremental convergence with cooperative release instead of a global barrier*, generalizes to essentially any distributed work-assignment system.