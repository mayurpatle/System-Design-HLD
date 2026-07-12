# Kafka Consumer Rebalancing — Eager vs. Cooperative Sticky

> **Primary source:** Confluent Blog — *From Eager to Smarter in Apache Kafka Consumer Rebalances* (Sophie Blee-Goldman, Kafka PMC), May 2020
> **URL:** https://www.confluent.io/blog/cooperative-rebalancing-in-kafka-streams-consumer-ksqldb/
> **Supporting:** KIP-429 (incremental rebalance protocol), KIP-848 (next-gen broker-driven protocol)
> **Companion note:** see `kafka-consumer-config-summary.md` (`partition.assignment.strategy` lives here)

---

## TL;DR

A **rebalance** redistributes partitions across a consumer group whenever membership or topic metadata changes. The **eager** protocol handles this with a brutal "stop-the-world" rule: *every* consumer revokes *all* its partitions before rejoining, so the whole group goes idle for the entire rebalance. The **incremental cooperative** protocol (Kafka 2.4+) keeps the same safety guarantee but moves the synchronization barrier so that **only the partitions actually changing hands are revoked** — everyone else keeps processing. You opt in by plugging in the `CooperativeStickyAssignor`. In Confluent's own benchmark, this cut total pause time from **37,138 ms → 3,522 ms** (~10×).

---

## 1. What a rebalance actually is

The **group coordinator** (one nominated broker) tracks two things: the **members** of the group and the **partitions** of the subscribed topics. Any change to either — a consumer joining/leaving/crashing, or a topic's partition count changing — means partitions must be redistributed so that every partition is being consumed and every member is doing work. The coordinator's only tool for this is the **rebalance**.

Crucially, the *assignment logic* was long ago **pushed out of the broker and into the client**, abstracted behind the pluggable `ConsumerPartitionAssignor` interface. That's why different clients (and Kafka Streams) can run different rebalancing behavior — and why switching protocols is "just" swapping an assignor.

### The two-phase rebalance dance
Members never talk to each other directly — only through the coordinator. One member is elected **group leader** and does the actual assignment computation:

1. **JoinGroup phase** — every member sends a `JoinGroup` request encoding its subscription (topics + user data). The coordinator collects all subscriptions and forwards them to the **leader**.
2. **SyncGroup phase** — the leader computes the partition→consumer assignment and sends it back via `SyncGroup`. The coordinator relays each member its slice in the `SyncGroup` response.

### The one inviolable rule
> **No partition may be owned by more than one consumer in the group at the same time.**

Everything about rebalance protocol design is about upholding this rule while minimizing downtime.

---

## 2. Eager rebalancing (the "stop-the-world" default of old)

To satisfy the "no double ownership" rule as simply as possible, eager enforces a **synchronization barrier**: **every member must revoke *all* of its owned partitions *before* sending `JoinGroup`.** By the time the leader computes the assignment, all partitions are unowned, so it can distribute them freely with zero risk of overlap.

Safe — but the drawbacks are severe:

- **Total group downtime.** No member does *any* work from the moment it sends `JoinGroup` until it receives its `SyncGroup` response. Even partitions that end up reassigned to their *original* owner get needlessly revoked and resumed.
- **Duration scales with partition count.** Every member revokes and re-acquires every partition it holds. For stateful apps (e.g. Kafka Streams with local RocksDB state stores), "revoke" can mean committing offsets, flushing to disk, and tearing down resources — very expensive.

Net effect: a routine deploy or scale-out event causes the **entire pipeline to freeze** for the rebalance duration — lag spikes, downstream timeouts, retry storms, cold caches.

---

## 3. The ideal we're aiming for

Thought experiment: A and B consume a 3-partition topic (A has 2, B has 1). You add consumer C. The *optimal* rebalance moves **exactly one partition** from A to C:

- **B never stops** — it's untouched, so why should it revoke anything?
- **A's downtime = the time to revoke one partition**, not all of them.
- **Partition 3** is only unavailable for the brief handover from A → C.

The principle: don't wipe the slate clean and reassign from scratch. **Start from the current assignment and incrementally move only the partitions that must move.**

---

## 4. Incremental cooperative rebalancing (Kafka 2.4+)

The key realization: the synchronization barrier only needs to hold for partitions **transferring ownership**. Partitions reassigned to the same consumer trivially satisfy the rule. So instead of *dropping* the barrier (unsafe), cooperative rebalancing **moves it** — using a **second rebalance** to enforce it only where needed.

### The algorithm (two rebalances)

**Rebalance 1 — join without revoking:**
1. Every member sends `JoinGroup` but **holds onto all its partitions**, encoding the currently-owned set in its subscription.
2. The leader computes the new target assignment, but **removes from the emitted assignment any partition that is changing owners** (it can detect these using the owned-partition info in subscriptions).
3. Each member diffs new-vs-old: it **revokes** partitions no longer in its assignment, **keeps** partitions in both (do nothing — the common case), and **adds** genuinely new ones. Most rebalances touch few partitions, so most members do little or nothing.

**Rebalance 2 — claim the orphans:**
4. Any member that revoked a partition rejoins, triggering a follow-up rebalance. The just-revoked partitions are now provably unowned (absent from everyone's encoded owned-set), so the leader safely assigns them to their new owners.

> The barrier was never dropped — it was **relocated** to apply only to the migrating partitions. (Mental model: a Venn diagram — revoke only the partitions *not* in the intersection of old and new assignments.)

---

## 5. Why it *must* be sticky, not just cooperative

You enable cooperative rebalancing simply by plugging in the **`CooperativeStickyAssignor`** — no separate config flag. It's the existing `StickyAssignor` made **both sticky *and* cooperative**. The stickiness is not a nice-to-have; it's **load-bearing**:

- The incremental protocol works by changing the assignment *partition by partition* from old to new.
- A **non-sticky** assignor like `RoundRobinAssignor` recomputes a fresh assignment on every membership/metadata change, making no attempt to return partitions to previous owners.
- If the new assignment is entirely different from the old one, the "incremental" change *is the whole assignment* — you'd revoke everything anyway and end up **back at eager, but now with an extra rebalance** (strictly worse).

So a cooperative assignor **must** (a) be sticky (preserve existing ownership) and (b) strip to-be-revoked partitions from the emitted assignment. Any custom cooperative assignor has to honor that contract.

---

## 6. The safe upgrade path — two rolling bounces (KIP-429)

You **cannot** live-swap eager → cooperative in one shot: mid-upgrade you'd have half the group revoking everything (eager) and half holding on (cooperative), risking double ownership. The rule: **the selected rebalance protocol must be supported by *every* assignor in a member's list**, and the coordinator picks the first commonly-supported assignor.

**Bounce 1 — introduce cooperative alongside the old assignor:**
`partition.assignment.strategy = cooperative-sticky, range` (old assignor still present).
New bytecode advertises *both* assignors, but because `range` is still in the list, the group **stays on the EAGER protocol** and `range` does the assigning. Safe.

**Bounce 2 — remove the old assignor:**
`partition.assignment.strategy = cooperative-sticky` (only).
Now bounced members choose the **COOPERATIVE** protocol and stop revoking everything; not-yet-bounced members are still eager — which is fine because the transition is coordinated per the config.

> Why two bounces: it prevents an **old-bytecode leader** (which only understands eager) from computing an assignment while newer members have *not* revoked their partitions — the exact double-ownership hazard. For Kafka Streams the equivalent guard is the `UPGRADE_FROM` config.

---

## 7. Kafka Streams gets it for free

Streams users can't pick the assignor directly — it's baked into the `StreamsPartitionAssignor`, and **cooperative rebalancing is on by default** (2.4+). Concrete wins for a stateful, high-partition Streams app scaling out:

- Only the **few partitions landing on the new instance** are revoked, instead of every store closing and reopening.
- **Interactive Queries (IQ)** stay available on running instances during the rebalance.
- **Restoring instances** keep restoring; **standby replicas** keep consuming their changelog to stay warm.
- On the newest versions, even **active tasks keep processing** through the rebalance — no more "scale vs. downtime" tradeoff.

---

## 8. The benchmark (the number to quote)

Setup: a 10-instance stateful Streams app, driven to steady state, then a **rolling bounce** to trigger rebalances; throughput measured as records/sec aggregated across instances.

| Protocol | Total pause time |
|----------|------------------|
| **Eager** | **37,138 ms** |
| **Cooperative** | **3,522 ms** |

≈ **10× less pause**. A subtle finding: cooperative + **RocksDB** state stores showed only a slight dip, but cooperative + **in-memory** stores still took a real hit (though it recovered faster than eager) — because in-memory stores must restore all ephemeral state from the changelog on restart. Backing-store choice is its own availability tradeoff, independent of the rebalance protocol. *(Numbers are workload-dependent — benchmark your own.)*

---

## 9. Version timeline & what's next

- **Kafka 2.4** — basic incremental cooperative rebalancing introduced (KIP-429); `CooperativeStickyAssignor` added.
- **Kafka 2.5** — ability to poll new data *during* a rebalance; unlocks the full benefit.
- **KIP-441** — warms up tasks on new instances *before* switching them over, closing the state-restoration availability gap; complements cooperative rebalancing.
- **KIP-848 (Kafka 4.0)** — the **next-gen protocol** moves coordination **broker-side** (`group.protocol=consumer`). Even cooperative rebalancing under the *classic* protocol still relied on the client-driven JoinGroup/SyncGroup barrier; KIP-848 replaces that model, speeds up rebalances further, and simplifies coordination. Under it, assignment config moves to `group.consumer.assignors` (broker) + `group.remote.assignor` (client).

---

## 10. Cheat sheet

| | Eager | Cooperative Sticky |
|---|---|---|
| Barrier | Revoke **all** before JoinGroup | Revoke **only migrating** partitions |
| Rebalances per change | 1 | up to 2 (extra one for orphans) |
| Group downtime | Whole group, whole rebalance | Only affected consumers, briefly |
| Scales with | Partition count (bad) | Number of partitions **moving** (good) |
| Enable via | `RangeAssignor`/`RoundRobinAssignor` | `CooperativeStickyAssignor` |
| Upgrade | n/a | two rolling bounces (never one) |

**One-liners for interviews**
- Cooperative reduces rebalance **impact**, not rebalance **frequency**.
- Stickiness isn't cosmetic — a non-sticky cooperative assignor degenerates back to eager *plus an extra rebalance*.
- The barrier is **moved, not removed** — safety (no double ownership) is preserved via a second rebalance.
- Never single-bounce the upgrade: an eager leader + non-revoking members = double-ownership hazard.
- Eager's cost scales with *total* partitions held; cooperative's with *partitions that actually move*.