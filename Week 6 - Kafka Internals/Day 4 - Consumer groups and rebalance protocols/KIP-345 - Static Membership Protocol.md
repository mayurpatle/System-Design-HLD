# KIP-345: Introduce Static Membership Protocol to Reduce Consumer Rebalances — Detailed Summary

**Source:** Apache Kafka wiki (cwiki.apache.org), authored by **Boyang Chen** (with design input from Jason Gustafson, Guozhang Wang, Mayuresh Gharat). **Status: Accepted; shipped in Kafka 2.3/2.4.** Companion to KIP-429 — where KIP-429 makes each rebalance *cheaper*, KIP-345 makes rebalances *not happen at all* for the most common cause: a consumer restarting.

**The one-line trade:** static membership **prioritizes "state persistence" over "liveness"** — the exact inversion of the dynamic membership model Kafka had used since day one.

---

## 1. Motivation: rebalances as the state-shuffling tax

For stateful applications, the biggest performance bottleneck is **state shuffling**. When a partition moves from instance A to B, B must transfer/rebuild potentially huge amounts of state. If multiple rebalances fire in sequence, the whole service can take a very long time to recover.

Two use cases named in the KIP:

1. **Heavy-state applications** (Kafka Streams with large local stores) — rebalance is *the* performance killer when scaling, because of time lost to state shuffling.
2. **Rolling bounce performance** — the MirrorMaker example: rolling-bouncing a whole cluster is slow because **every single process restart triggers a rebalance**. With static membership you need only a **constant number** of rebalances (e.g. one for a leader restart) for the *entire* rolling bounce.

That second point is the everyday win: a 20-instance rolling deploy went from 20 rebalances to ~0.

## 2. Background: why a restart looks like a new member

The old (dynamic) model:

- On each rebalance, the broker assigns each member a **randomly generated `member.id`**.
- On restart, a consumer sends `JoinGroupRequest` with **UNKNOWN_MEMBER_ID** (empty string) — and the broker has no way to know this is the same process coming back, so it treats it as a **brand-new member** → PREPARE_REBALANCE → full group rebalance.
- Worse, the *leave* on shutdown triggers one rebalance and the *rejoin* triggers another. **Two rebalances per restart.**

The insight: identity was **broker-generated and ephemeral**. Nothing in the protocol tied "the process that just came back" to "the process that just left."

## 3. The mechanism: `group.instance.id`

A single new consumer config:

| Config | Meaning |
|---|---|
| `group.instance.id` | A **user-provided** unique identifier for this consumer instance. Non-null ⇒ **static member**; null (default) ⇒ dynamic member (legacy behavior). |

It is the user's responsibility to keep these unique — the KIP suggests service-discovery hostname, unique IP, or a StatefulSet ordinal.

**Protocol plumbing:** `group.instance.id` is added to Join / Sync / Heartbeat / OffsetCommit requests, to `JoinGroupResponse` members, and to `DescribeGroup` (for operator visibility). `LeaveGroupRequest` is redesigned from a single `member.id` into a **batch list of (group.instance.id, member.id) tuples**. Broker-side, the coordinator keeps an in-memory `{group.instance.id → member.id}` map, and — critically for fault tolerance — persists `group.instance.id` in **member metadata in `__consumer_offsets`**, so a coordinator failover reloads the static mapping instead of losing it.

**Core broker behavior:** when a known `group.instance.id` rejoins, the coordinator **returns its cached assignment without triggering any rebalance**. The member gets a fresh `member.id` but keeps its partitions.

### The four (and only four) rebalance triggers under static membership

1. A **new** member joins (unrecognized instance id).
2. The **leader** rejoins (possible subscription/topic change) — later refined: if the leader rejoins with UNKNOWN_MEMBER_ID, that signals a restart and **no rebalance is needed**.
3. An existing member's offline time exceeds the **session timeout**.
4. The broker receives a **LeaveGroupRequest** with a list of `group.instance.id`s (the admin-driven path).

Notably absent: ordinary restarts.

## 4. Key behavioral changes

- **Static members do NOT send LeaveGroupRequest on shutdown.** Deliberate: the whole point is that a bounce shouldn't churn the group. Liveness detection is delegated **to the session timeout** — and, philosophically, to the orchestration layer (Kubernetes) which is already managing process health.
- **The session timeout cap is raised to 30 minutes** (`GroupMaxSessionTimeoutMs = 1800000`) so operators can set a window comfortably longer than a restart.
- **Rebalance timeout no longer evicts members.** Previously, `max.poll.interval.ms` (the rebalance timeout) would drop non-responsive members; now it only ends the PREPARE_REBALANCE phase. **Members are removed only on session timeout.**
- **New error: `FENCED_INSTANCE_ID`.** If a join arrives with a known `group.instance.id` but a `member.id` that doesn't match the coordinator's record, it's rejected — meaning **another consumer has taken this identity**. The client should **fail itself immediately**, because this indicates a configuration bug producing duplicate identities. This is the fencing token pattern again (same family as producer epochs in KIP-98 and leader epochs in KIP-101): user-assigned identity needs a uniqueness guard.
- **New AdminClient API:** `removeMemberFromConsumerGroup(...)` — batch-removes static instances via the new LeaveGroupRequest and triggers a rebalance **immediately**, solving **scale-down**: without it you'd wait out a 30-minute session timeout after decommissioning instances.
- **Static and dynamic members can coexist** in the same group, which is what makes the upgrade a simple rolling bounce.

## 5. The instance-id-as-sort-key insight (Jason Gustafson's example, worth reproducing)

Even with cached assignments, there's a subtlety: **the leader doesn't know about instance ids** — it only sees `member.id`s, which change on restart. So a *later, unrelated* rebalance would reshuffle everything anyway. The KIP's worked example:

Three static instances A, B, C with member ids 1, 2, 3 and a 9-partition topic. Under RangeAssignor (which sorts members):

```
memberId 1 → {0,1,2}      memberId 2 → {3,4,5}      memberId 3 → {6,7,8}
```

A restarts and gets **member.id 4**. Membership is now {2, 3, 4}, and sorting by member id produces:

```
memberId 2 → {0,1,2}      memberId 3 → {3,4,5}      memberId 4 → {6,7,8}
```

**Every assignment changed** — for the same three physical instances. The fix: include `group.instance.id` in the JoinGroupResponse members and let the assignor **sort by instance id first**:

```
A → {0,1,2}      B → {3,4,5}      C → {6,7,8}       (before restart)
A → {0,1,2}      B → {3,4,5}      C → {6,7,8}       (after A's restart, new member.id 4)
```

Stable across restarts, with no fancy metadata — just a stable sort key. The general lesson: **stickiness requires a stable identity to be sticky *about*.** KIP-429's cooperative protocol and KIP-345's static ids are complementary halves of the same idea.

## 6. Kafka Streams integration

A Streams instance with `num.stream.threads=16` runs 16 consumers. The final design has the user configure `group.instance.id` at the instance level, and Streams appends `-thread-<id>` per thread so each embedded consumer gets a unique static id — while avoiding "surprise" activation of static membership (an earlier draft derived it from `client.id` automatically, which was rejected as too implicit).

## 7. Upgrade / downgrade

**Upgrade:** (1) upgrade brokers, (2) upgrade clients, (3) set a unique `group.instance.id` per instance and a sensible `session.timeout.ms`, (4) rolling bounce. That's it — no double-bounce dance, because static and dynamic members interoperate (contrast with KIP-429's mandatory two-phase rollout).

**Downgrade:** unset `group.instance.id`, lower the session timeout, rolling bounce. Stale static metadata expires on session timeout.

## 8. Rejected alternative and future work

**Rejected:** an earlier prototype **materialized the broker-generated member id to local disk**. It worked, but `group.instance.id` won because it gives users control (better debugging), is **cloud/Kubernetes-friendly** (move an instance between containers by copying a config value — no local disk state), doesn't require local-disk access (think remote-mounted EBS), and propagates naturally to Connect and Streams.

**Future work sketched:** *pre-registration* (declare N instance ids so the server waits for the whole scale-up before rebalancing once), *hot standby via `target.group.size`* (idle spare consumers swap in on failure without waiting out a session timeout), a `kafka-remove-member-from-group.sh` CLI, and using instance ids as the generic sticky-assignment key. (Later reality: **KIP-814** fixed a leader-rejoin gap, and **KIP-848**'s next-gen broker-side protocol still supports static membership — a bouncing member heartbeats with member epoch **-2** to signal "leaving temporarily," and rejoining with the same instance id restores its assignment.)

---

## 9. Important concepts (highlight reel)

- **Static vs dynamic membership** = "state persistence over liveness" vs "liveness over state persistence." One sentence that captures the whole KIP.
- **User-assigned stable identity** (`group.instance.id`) replaces broker-generated ephemeral identity (`member.id`) as the thing the group reasons about across restarts.
- **No LeaveGroupRequest on shutdown** — the behavioral change that eliminates the "leave + rejoin = two rebalances" cost, at the price of relying on session timeout.
- **Session timeout becomes the single liveness knob** (cap raised to 30 min), and the rebalance timeout stops evicting members.
- **`FENCED_INSTANCE_ID`** — fencing for user-assigned identities; duplicate instance ids are a config bug that must fail loudly, not silently double-consume.
- **Instance id as assignment sort key** — stickiness needs stable identity; without it, cached assignments still get shuffled by the next unrelated rebalance.
- **Batch LeaveGroupRequest + AdminClient removal** — makes scale-down fast despite long session timeouts. Long timeouts need an explicit "I really am gone" escape hatch.
- **Persisted in `__consumer_offsets`** — the static map survives coordinator failover, using Kafka's own log as the durable store (the recurring architectural motif across all these KIPs).
- **Complementarity with KIP-429:** static membership *prevents* rebalances from restarts; cooperative rebalancing makes the ones that do happen *incremental*. Production configs use both.

## 10. Real-world examples and use cases

**The canonical example — rolling deploy of a stateful Streams app:**

You run 20 instances of a Kafka Streams fraud-scoring app, each with several GB of RocksDB state, deployed on Kubernetes. Pre-KIP-345, a rolling restart means each pod's shutdown triggers a rebalance and its startup triggers another — up to ~40 rebalances, each reshuffling tasks and forcing changelog restores that can run for minutes. The deploy takes hours and lag balloons throughout. With `group.instance.id` set per pod (StatefulSet ordinal is the natural fit) and `session.timeout.ms` comfortably above pod restart time (say 2–5 min), each pod leaves and returns **within its session window**, gets its **cached assignment back**, and the group never rebalances. The deploy becomes a rolling restart in the ordinary sense.

**Other concrete situations:**

- **MirrorMaker / replication fleets** (the KIP's own example): large process counts where a full-cluster bounce previously meant one rebalance per process; now a constant number.
- **Kubernetes StatefulSets generally:** `group.instance.id = ${POD_NAME}` (or the ordinal) is the standard pattern — stable, unique, and survives rescheduling. This is precisely the cloud-friendliness that beat the local-disk alternative.
- **Spot/preemptible instances and node autoscaling:** brief pod migrations no longer cascade into group-wide state shuffles.
- **Consumers with expensive per-partition setup** — warmed caches, per-partition DB connections/prepared statements, open file handles, in-memory dedup windows: every avoided reassignment is avoided rebuild cost.
- **Low-latency event pipelines** (payments, order routing, fraud): the config pair to remember is **`group.instance.id` (KIP-345) + `cooperative-sticky` assignor (KIP-429)** — one removes restart-triggered rebalances, the other makes genuine membership changes incremental. In an event-driven order/payments service, this pair is what makes a routine deploy invisible to end-to-end latency.
- **Scale-down operations:** decommissioning 5 of 20 consumers with a 5-minute session timeout would leave their partitions stranded until timeout. Use the **AdminClient batch removal** (`removeMemberFromConsumerGroup`) to trigger the rebalance immediately.

**The tradeoff to state honestly (interview-grade nuance):** a genuinely crashed static member's partitions are **stranded for the full session timeout** — with a 5-minute timeout, that's 5 minutes of unconsumed lag on those partitions. Static membership is the right call when **restarts are frequent and crashes are rare and orchestrator-healed** (i.e. Kubernetes), and the wrong call when you need fast automatic failover on hard crashes. Tuning `session.timeout.ms` *is* choosing your point on that curve: long enough to cover a deploy, short enough that a real crash isn't an outage — plus the admin API as the manual escape hatch.

**Transferable design patterns:**

- **Stable identity as the enabler of stickiness** — any system that reassigns work (shard managers, job schedulers, connection routers) gets dramatically better behavior when workers have durable identities rather than session-scoped ones. This is why Kubernetes has StatefulSets at all.
- **Fencing user-supplied identity** — the moment you let users name things, you need a `FENCED_INSTANCE_ID` equivalent; duplicates must fail loudly.
- **Long timeouts need a manual override** — pairing a generous automatic timeout with an explicit admin "remove now" API is the general pattern for tuning failure detection without sacrificing operational agility.

## 11. TL;DR

Under dynamic membership, a consumer's identity (`member.id`) is broker-generated and ephemeral, so a **restart looks like a departure plus a brand-new arrival — two rebalances** — and for stateful apps each rebalance means expensive state shuffling; rolling-bouncing N instances costs ~N rebalances. KIP-345 (Kafka 2.3/2.4) introduces **static membership** via a user-supplied **`group.instance.id`**: the coordinator maps instance id → member id (persisted in `__consumer_offsets` for failover), returns a returning member's **cached assignment with no rebalance**, and static members **don't send LeaveGroupRequest** on shutdown — so liveness rests entirely on `session.timeout.ms` (cap raised to 30 minutes), with `FENCED_INSTANCE_ID` guarding against duplicate identities and a **batch AdminClient removal API** for fast scale-down. Including instance ids in the JoinGroupResponse also lets assignors **sort by a stable key**, so assignments stay put across restarts even when a later rebalance occurs. Rebalances now fire only for genuinely new members, leader subscription changes, session-timeout expiry, or explicit removal. The trade is explicit — **state persistence over liveness**: a real crash strands partitions for the session timeout. Paired with KIP-429's cooperative rebalancing, it's the reason a modern Kafka consumer fleet can roll a deploy on Kubernetes without a rebalance storm.