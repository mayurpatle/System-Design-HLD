# KIP-429: Kafka Consumer Incremental Rebalance Protocol — Detailed Summary

**Source:** Apache Kafka wiki (cwiki.apache.org), created by **Boyang Chen**, developed with **Guozhang Wang** and **A. Sophie Blee-Goldman**. **Status: Accepted, shipped in Kafka 2.4.0 (2019).** The consumer-client counterpart to KIP-415 (which did the same for Kafka Connect), both descended from the community design note *Incremental Cooperative Rebalancing: Support and Policies*. This is the KIP that ended **stop-the-world rebalancing** for consumer groups.

**Goals, in the KIP's own words:** reduce unnecessary downtime caused by unnecessary partition migration (partitions being revoked and immediately re-assigned to the same owner), and give better rebalance behavior for members falling out of the group.

---

## 1. Background: the stop-the-world problem

The old ("eager") protocol enforced one hard invariant — **a partition must be owned by exactly one consumer at any time** — using the crudest possible mechanism: *a partition cannot be reassigned before it is revoked*, so **every member revokes everything before joining the group**. The rebalance itself is the synchronization barrier.

For a Kafka Streams app, one membership change means every stream thread:

1. joins the group with **all assigned tasks revoked**,
2. waits for the whole group assignment to finish,
3. **replays/restores state** for its assigned tasks,
4. only then returns to running.

So one new consumer joining stalls the *entire* group — including the ~95% of partitions that will be handed straight back to their current owners. For **heavy-stateful consumers** (Streams apps with large RocksDB state stores), the restore step turns a membership blip into minutes of downtime. That asymmetry — global stall for a local change — is the entire motivation.

## 2. The core idea: two rebalances instead of one barrier

> Instead of relying on a **single rebalance's synchronization barrier** (everyone gives up everything before rejoining), use **consecutive rebalances**, where **the end of the first rebalance is the synchronization barrier**.

Concretely: consumers **keep their partitions** while joining. The leader computes the intended assignment, and *only* the partitions that must actually change hands are revoked — by their old owners, after the first rebalance — which triggers a **second, follow-up rebalance** that delivers them to their new owners. Everything else keeps processing throughout.

**Critical dependency (stated explicitly in the KIP):** this only pays off if the assignor is **sticky**. If the new assignment is totally different from the old one, cooperative rebalancing *degenerates into eager* — everyone revokes everything anyway, and you've paid for an extra rebalance for nothing. Stickiness is what makes the diff small.

## 3. Protocol changes

### 3.1 `ownedPartitions` in the Subscription

The consumer protocol's `Subscription` gains an **`AssignedPartitions` / `ownedPartitions`** field — each member now *declares what it currently holds* when it joins. This is the key new information: the leader can compute a diff instead of assuming a blank slate. (Compatibility trick: new fields are appended after the assignor-specific user-data bytes, so old consumers can still deserialize.)

Note the deliberate de-duplication insight: the sticky assignor used to encode owned partitions in its own opaque `userData`; now it's a **first-class protocol field** any assignor can read.

### 3.2 The leader's three-way partition diff (the algorithmic heart)

The leader collects `owned-partitions` from all subscriptions, runs the assignor to get `assigned-partitions`, and splits the universe into three sets:

| Set | Definition | Action |
|---|---|---|
| **maybe-revoking** | Intersection(owned, assigned) — still held by someone, possibly allocated to a different member now | If the owner changed: **withhold** it from the new owner this round. The old owner will notice it's gone, revoke it, and a follow-up rebalance delivers it. |
| **ready-to-migrate** | Minus(assigned, owned) — nobody currently owns it (previous owner already revoked, brand-new partition, or an old-protocol member) | **Assign immediately** — safe, no one holds it. |
| **unknown-but-owned** | Minus(owned, assigned) — claimed by a member but absent from the assignment (deleted topic, stale leader metadata, Streams-created topics not yet in the cluster) | **Give it back** to the claimant; a later rebalance sorts it out if metadata changes. |

This three-set decomposition **is** the protocol. It's the safe way to move ownership without ever having two owners: *a partition is only handed to its new owner after its old owner has provably let go.*

### 3.3 Consumer-side handling

After sync-group, each member computes `newly-added = assigned − owned` and `revoked = owned − assigned`; calls `onPartitionsRevoked` **only if the revoked set is non-empty** — and if so, **rejoins to trigger the follow-up rebalance**; then calls `onPartitionsAssigned` for the new partitions (always, even if empty).

**No broker changes required** — the whole thing lives in the consumer coordinator and protocol serialization. To the broker it looks like ordinary rebalances.

### 3.4 `onPartitionsLost` — the new third callback

A long-standing bug fixed here: when a consumer is **kicked out of the group** (UNKNOWN_MEMBER_ID / ILLEGAL_GENERATION), it no longer owns its partitions, so the `commit()` inside the user's `onPartitionsRevoked` is **doomed to fail**. The new `default void onPartitionsLost(Collection<TopicPartition>)` distinguishes *"you were fenced, these are gone, don't try to commit"* from *"you're handing these off cleanly, commit now."* Default implementation delegates to `onPartitionsRevoked` for backward compatibility (but implementations must be **recompiled**).

### 3.5 Changed callback semantics (a real trap for existing code)

Under eager, the timeline was fixed: `onPartitionsRevoked(everything)` → assign → `onPartitionsAssigned(everything)`. Under cooperative:

- `onPartitionsRevoked` may **not be called at all** in a typical rebalance (scale in/out, member restart) — so **you must not use it as a "rebalance is starting" signal**, which is exactly what a lot of pre-2.4 code did.
- `onPartitionsAssigned` is **always** called (even with an empty set) — this is the reliable "rebalance just completed" hook.
- Revoked and assigned sets are now **disjoint subsets**, not the full assignment.
- Callback error handling changed too: exceptions are no longer silently swallowed — the coordinator logs, completes the remaining callbacks, remembers the first exception, and **rethrows it up through `poll()`**. Callbacks are now "notifications": throwing does not undo the assignment.

### 3.6 CooperativeStickyAssignor and protocol negotiation

- New out-of-the-box **`CooperativeStickyAssignor`** using the built-in `ownedPartitions` field. A **new** class rather than upgrading `StickyAssignor` in place — deliberately, so existing StickyAssignor users can't blindly upgrade into an unsafe state.
- Assignors declare `supportedProtocols()` returning **EAGER** and/or **COOPERATIVE**. `RangeAssignor` and `RoundRobinAssignor` stay EAGER-only *because they aren't sticky* — cooperative would buy nothing.
- The coordinator picks the **highest-id protocol supported by all configured assignors**, throwing at startup if there's no common protocol.
- The old `PartitionAssignor` (mistakenly in an `internal` package) is deprecated in favor of the public `ConsumerPartitionAssignor`, and new **rebalance metrics** are added: `rebalance-rate-per-hour`, `rebalance-latency-avg/max`, `failed-rebalance-rate-per-hour`, `last-rebalance-seconds-ago`, and per-callback latencies (`partitions-revoked/assigned/lost-latency-*`).

### 3.7 The double rolling bounce (operationally the most important section)

The danger: an **old leader** can still deserialize new-protocol subscriptions (ignoring extra fields), conclude "everyone is EAGER," and reassign partitions that the new members **never revoked** — producing two owners of one partition. To make that impossible:

**Plain consumers — two rolling bounces:**
1. Swap the jars and set `partition.assignment.strategy = "cooperative-sticky, range"`. Everyone still negotiates EAGER (common protocol across both assignors) — safe.
2. Remove `range`, leaving only `cooperative-sticky`. Bounced members go COOPERATIVE, un-bounced stay EAGER; the cooperative assignor handles a **mixed group** because EAGER members arrive having already revoked everything (empty `ownedPartitions`), so their partitions fall into *ready-to-migrate* and move immediately.

**Kafka Streams — also two bounces**, but via the `UPGRADE_FROM` config (set to the old version in bounce 1, removed in bounce 2). Streams enables cooperative by default in 2.4.

**Downgrade is the same path reversed** (add the eager assignor back first, then remove cooperative and downgrade bytecode). Getting it wrong isn't undefined behavior — no consensus protocol can be formed, so members get **kicked out with a fatal error**, which is the KIP's explicit safety choice: fail loudly rather than risk double ownership.

### 3.8 Related refinements

- **KAFKA-8421 (Kafka 2.5):** consumers can return records for owned partitions *during* a rebalance; `commitSync` may throw `RebalanceInProgressException`. Combined with cooperative, Streams keeps processing active tasks mid-rebalance.
- **Streams user-visible effect:** far less time in the `REBALANCING` state — state stores stay **open for Interactive Queries** during a rebalance, and restore/standby work continues.
- **No `scheduledDelay`** (unlike KIP-415's Connect version): rebalances trigger immediately, because **KIP-345 static membership** was being built in parallel to handle rolling-bounce/scale-out churn.
- **Future directions noted:** heartbeat-communicated protocol negotiation and assignor-version-based leader election — both of which foreshadow **KIP-848**, the broker-side next-gen rebalance protocol (Kafka 4.x) that eventually replaced this client-driven design.

---

## 4. Important concepts (highlight reel)

- **Stop-the-world vs incremental:** the old barrier was "everyone drops everything"; the new one is "the *first rebalance's completion* is the barrier." Same safety invariant, far less disruption.
- **The exactly-one-owner invariant** is what all of this protects. Every design choice (withholding contested partitions, two rolling bounces, fatal errors on protocol mismatch) exists to prevent two consumers owning one partition.
- **`ownedPartitions` as declared state:** members telling the leader what they hold turns assignment from *allocation* into *diffing* — the same shift that makes any reconciliation loop (Kubernetes controllers, config management) incremental instead of destructive.
- **The three-set diff** (maybe-revoking / ready-to-migrate / unknown-but-owned) — worth being able to reproduce from memory; it's the reusable algorithm.
- **Stickiness is a precondition, not a bonus:** without it, cooperative ≡ eager + one wasted rebalance. Hence Range/RoundRobin remain EAGER-only.
- **`onPartitionsLost` vs `onPartitionsRevoked`:** fenced-and-gone vs clean-handoff. Committing offsets in the former is guaranteed to fail.
- **`onPartitionsAssigned` is the only reliable rebalance signal** under cooperative — a genuine migration gotcha.
- **Protocol negotiation via assignor capability lists**, plus **fail-loud on no common protocol** — a clean pattern for rolling out incompatible protocol changes in a distributed client fleet.
- **Client-only change:** a substantial protocol upgrade shipped with **zero broker changes**, by keeping the new semantics inside metadata the brokers just pass through.

## 5. Real-world use cases

**Where it directly saves you:**

- **Kafka Streams / ksqlDB apps with large state stores.** The headline case: pre-2.4, adding one instance meant every instance closed its stores and restored from changelogs — minutes of unavailability, and Interactive Queries returning errors the whole time. Cooperative keeps unaffected tasks running and stores open. If you run stateful aggregations or joins, this KIP is why scaling is no longer an outage.
- **Autoscaling and Kubernetes/spot-instance environments.** Pod churn, HPA scale events, and rolling deploys each triggered a full group stall under eager. With `cooperative-sticky`, a single pod restart disturbs only its own partitions. For consumers running on EKS with frequent rescheduling, this is the difference between "rebalance storms" and normal operation.
- **Large consumer groups (dozens–hundreds of members).** Eager rebalance cost scales with total partitions; cooperative scales with *changed* partitions. Big fleets are where the gap is widest.
- **Low-latency consumers with tight SLAs** — payment authorization, fraud scoring, order routing — where a multi-second group-wide pause on every deploy is unacceptable. In an event-driven order/payments pipeline, `cooperative-sticky` plus static membership (KIP-345) is the standard config pair for surviving rolling restarts without lag spikes.
- **Consumers with expensive per-partition setup:** DB connections/prepared statements per partition, warmed caches, open file handles, external checkpoint state. Any "partition ownership costs money to acquire" workload benefits proportionally.

**Operational applications:**

- **Config to adopt:** `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor` (default in newer versions), reached via the **two rolling bounces** — a migration you should be able to describe precisely, since doing it wrong is the classic production incident.
- **Rebalance monitoring:** the metrics added here (`rebalance-rate-per-hour`, `rebalance-latency-avg/max`, `failed-rebalance-rate-per-hour`, `last-rebalance-seconds-ago`) are the ones to graph when diagnosing rebalance storms — high failed-rebalance rate usually means `max.poll.interval.ms` violations or assignor misconfiguration.
- **Code review checklist for upgrades:** any `ConsumerRebalanceListener` that treats `onPartitionsRevoked` as "rebalance starting," commits everything there, or assumes the callback receives the full assignment **must be rewritten** — and `onPartitionsLost` should be implemented separately (don't commit; drop in-flight work).

**Transferable design patterns:**

- **Incremental reconciliation over stop-the-world:** declare current state, diff against desired state, move only the delta, and use a second pass for anything contested. This is the same shape as Kubernetes controller loops, Cruise Control's partition reassignment, and rolling config rollouts — a strong HLD-interview answer to "how do you rebalance work across a cluster without pausing everything?"
- **Safe protocol migration in a fleet:** capability negotiation (each node advertises supported protocols), a documented multi-phase rollout where each phase is independently safe, and **failing loudly** instead of degrading into undefined behavior — a reusable playbook for any versioned distributed protocol you own.
- **Ownership transfer requires explicit release:** never grant a resource until the prior holder has demonstrably released it. Same principle as lease/fencing designs.

## 6. TL;DR

Before Kafka 2.4, any consumer-group membership change triggered an **eager, stop-the-world rebalance**: every member revoked *all* partitions, waited for the global assignment, and stateful apps (Kafka Streams) then restored state from changelogs — a full outage for what was usually a tiny reshuffle. KIP-429 replaces the single synchronization barrier with **two consecutive rebalances**: members now declare their **`ownedPartitions`** when joining, the leader diffs owned vs. assigned into **maybe-revoking / ready-to-migrate / unknown-but-owned**, assigns only the uncontested partitions immediately, and lets old owners revoke contested ones — triggering a **follow-up rebalance** that hands them over, so the exactly-one-owner invariant survives while everything unaffected keeps processing. It ships as the **CooperativeStickyAssignor** (stickiness is mandatory for any benefit), adds **`onPartitionsLost`** for fenced members, changes callback semantics (`onPartitionsRevoked` may never fire; `onPartitionsAssigned` always does), adds rebalance metrics, requires **no broker changes**, and demands a careful **double rolling bounce** to upgrade — failing loudly rather than risking two owners of one partition. It's the reason modern Kafka consumers scale and redeploy without rebalance storms, and its client-side complexity is exactly what KIP-848 later moved to the broker.