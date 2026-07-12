# Kafka Consumer Configuration — `session.timeout.ms`, `max.poll.interval.ms`, `partition.assignment.strategy`

> **Source:** Confluent Platform docs — *Kafka Consumer* & *Consumer Configuration Reference* (current)
> **URLs:** https://docs.confluent.io/platform/current/clients/consumer.html · https://docs.confluent.io/platform/current/installation/configuration/consumer-configs.html
> **Scope:** The three requested configs, plus the two neighbours (`heartbeat.interval.ms`, `group.instance.id`) you can't reason about them without.

---

## 0. The one thing to internalize first

A consumer has **two independent liveness mechanisms**, and confusing them is the #1 source of "why does my group keep rebalancing" bugs:

| Mechanism | "Is the consumer…" | Driven by | Runs on |
|-----------|--------------------|-----------|---------|
| **Session / heartbeat** | …*alive and connected* to the coordinator? | `session.timeout.ms` + `heartbeat.interval.ms` | A **background heartbeat thread** |
| **Poll interval** | …*actually making progress* through records? | `max.poll.interval.ms` | The **application/poll thread** |

A consumer can be **heartbeating fine (process alive) but stuck processing** a batch — that's exactly what `max.poll.interval.ms` exists to catch. Heartbeats alone can't detect a wedged processing loop because they run on a *separate* thread.

---

## 1. `session.timeout.ms` — "is the process alive?"

**What it is.** The liveness timeout for detecting a failed consumer via **heartbeats**. Each member periodically heartbeats the group coordinator. If the coordinator receives **no heartbeat before this timeout expires**, it removes the member from the group and triggers a rebalance to hand its partitions to another member.

**Default.** 10 seconds (Java and C/C++ clients).

**Broker-side bounds.** The value you set must fall inside the broker's allowable range, configured by `group.min.session.timeout.ms` and `group.max.session.timeout.ms`. Set it outside that window and the broker rejects it.

**The core tradeoff:**
- **Larger timeout** → tolerates transient hiccups (poor network, long GC pauses) → **fewer spurious rebalances**. *But* the coordinator is **slower to detect a genuinely crashed consumer**, so failover (another consumer taking over the partitions) also takes longer → higher tail latency / lag after a real crash.
- **Smaller timeout** → fast crash detection and failover → *but* easily tripped by momentary GC/network blips, causing unnecessary rebalances.

**Normal shutdown is exempt.** On a clean shutdown the consumer sends an explicit **LeaveGroup** request, which triggers an *immediate* rebalance — you don't wait out the session timeout.

**Paired config — `heartbeat.interval.ms`:**
- How often the background thread sends heartbeats. **Default: 3 seconds.**
- **Rule:** must be **lower than** `session.timeout.ms`, and the docs recommend **no higher than ~1/3** of it. That gives the consumer roughly 3 heartbeat attempts before the session is declared dead — resilient to a single dropped heartbeat.
- Lower heartbeat interval → faster rebalance detection, but more coordinator traffic. For very large groups, consider raising it.

---

## 2. `max.poll.interval.ms` — "is it making progress?"

**What it is.** The **maximum allowed gap between successive calls to `poll()`** (the `Consume` method in .NET). It's an upper bound on how long a consumer may be busy/idle *between* fetches. If `poll()` isn't called again within this window, the consumer is **assumed failed**, it **leaves the group**, and its partitions are **reassigned**.

**Default.** 300 seconds (5 minutes). Safe to increase when per-batch processing legitimately takes longer.

**Why it exists.** It bounds **processing time**, not connectivity. Without it, a consumer whose handler hangs (or just runs very long) would keep heartbeating from the background thread and silently hold its partitions forever while lag piles up — the coordinator would never know anything was wrong.

**Companion lever — `max.poll.records`:** caps how many records a single `poll()` returns (Java). If each record is slow to process, **lower `max.poll.records`** so a batch finishes comfortably inside `max.poll.interval.ms`. (Note: it does **not** change the underlying fetch behavior — records are cached from fetches and handed out incrementally per poll.)

**Static-member nuance.** For a consumer with a non-null `group.instance.id` that blows past `max.poll.interval.ms`, partitions are **not** reassigned immediately. Instead the consumer stops heartbeating and reassignment waits until `session.timeout.ms` also expires — mirroring the behavior of a static member that shut down.

---

## 3. `partition.assignment.strategy` — "who gets which partitions?"

**What it is.** An **ordered, comma-separated list of assignor class names** (implementations of `ConsumerPartitionAssignor`) that decide how partition ownership is distributed across the consumers in a group when group management is used.

**Rules:**
- **All consumers in the same group must advertise a compatible list.** The coordinator picks the **first assignor present in *every* member's list**. This ordered-list design is what lets you migrate a running group: keep the old assignor first while some members still lag, then flip.
- **Classic protocol only.** This config applies when `group.protocol=classic` (the default). It is **not supported** under the newer `group.protocol=consumer` protocol, where assignment moves broker-side (`group.consumer.assignors`) with `group.remote.assignor` on the client. (See §4.)

### The four built-in assignors

**RangeAssignor** *(the classic default)*
Assigns **per topic**: sorts partitions and consumers, then hands each consumer a contiguous *range*. Simple and efficient, and co-locates same-numbered partitions of different topics on the same consumer (useful for joins). **Downside:** if partition count isn't a clean multiple of consumer count, the earlier consumers get the extra partitions → **uneven load**.

**RoundRobinAssignor**
Distributes *all* partitions one-by-one across all consumers in round-robin order. **More even** distribution, good when partition counts/sizes vary. **Downside:** under the eager protocol it can cause more reshuffling on membership changes.

**StickyAssignor**
Balances partitions **while preserving as many existing assignments as possible** across rebalances → fewer reassignments → less duplicate/missed processing. **Downside:** may sacrifice some balance if the group changes often.

**CooperativeStickyAssignor**
Sticky logic **plus cooperative (incremental) rebalancing** (KIP-429): members keep processing partitions they're retaining and only revoke the ones actually moving, instead of everyone stop-the-world releasing everything. **Reduces rebalance *impact*, not rebalance *frequency*.** Requires **client ≥ 2.4.0**. *(For groups where all members subscribe to the identical topic set, `ConstrainedCooperativeStickyAssignor` is an optimized variant.)*

> **Apache default** is the list `[RangeAssignor, CooperativeStickyAssignor]` — uses Range by default but lets you upgrade to cooperative with a single rolling bounce that drops Range from the list. **Modern practical recommendation: prefer `CooperativeStickyAssignor`** unless you specifically need range's cross-topic locality or must stay compatible with pre-2.4 clients.

### Safe upgrade to CooperativeSticky (two-step rolling bounce)

1. **Prepare:** set the list to `<current>,CooperativeStickyAssignor` (current first). Rolling-restart every member. No behavior change yet — the coordinator still picks the current assignor since it's the first commonly supported one.
2. **Switch:** once *all* members support cooperative, set the list to `CooperativeStickyAssignor` only and roll again. The group now rebalances incrementally.

Do **not** jump straight to *only* `CooperativeStickyAssignor` before every member runs ≥ 2.4.0 — the group would fail to find a common assignor.

---

## 4. Classic vs. Consumer rebalance protocol (context you'll be asked about)

Kafka is transitioning from the **classic** client-driven rebalance protocol to the newer **consumer** protocol (`group.protocol=consumer`, KIP-848), which pushes coordination to the broker. The three configs get **replaced** under the new protocol:

| Classic (client-side) | Consumer protocol (server-side) |
|-----------------------|----------------------------------|
| `session.timeout.ms` | `group.consumer.session.timeout.ms` (broker) |
| `heartbeat.interval.ms` | `group.consumer.heartbeat.interval.ms` (broker) |
| `partition.assignment.strategy` | `group.consumer.assignors` (broker) + `group.remote.assignor` (client) |

Legacy properties/methods are **ignored or error** under `group.protocol=consumer`. `max.poll.interval.ms` remains a client concern (it's about application processing, not group coordination).

---

## 5. Static membership — the rebalance-avoidance combo

Set a unique **`group.instance.id`** per consumer to make it a **static member**: only one instance with that ID may be in the group at a time. Combined with a **larger `session.timeout.ms`**, this lets a consumer **restart (rolling deploy, crash-and-recover) without triggering a rebalance**, as long as it rejoins within the session window — Kafka preserves its previous partition assignment. Especially valuable in **Kubernetes**: use **StatefulSets** so pods get stable identities to serve as static member IDs. Pairs naturally with cooperative rebalancing.

---

## 6. Tuning cheat sheet

| Symptom | Likely cause | Lever |
|---------|--------------|-------|
| Frequent rebalances during GC/network blips | `session.timeout.ms` too tight | ↑ `session.timeout.ms` (keep `heartbeat.interval.ms` ≤ 1/3) |
| Consumer kicked out mid-batch ("rebalance storm" on slow processing) | Processing > `max.poll.interval.ms` | ↑ `max.poll.interval.ms` and/or ↓ `max.poll.records` |
| Slow failover after real crashes | `session.timeout.ms` too large | ↓ it (accept slightly more spurious-rebalance risk) |
| Rolling restarts cause full rebalances | Dynamic membership + eager assignor | `group.instance.id` (static) + `CooperativeStickyAssignor` |
| Uneven partition load | `RangeAssignor` with awkward partition:consumer ratio | switch to RoundRobin / (Cooperative)Sticky |

**Ordering invariant to remember:** `heartbeat.interval.ms` < `session.timeout.ms` ≤ (implicitly) processing that fits inside `max.poll.interval.ms`.

---

## 7. Interview one-liners

- *"Session timeout vs. poll interval?"* → Session timeout + heartbeats detect a **dead/disconnected process** (background thread); `max.poll.interval.ms` detects a **live process that stopped making progress** (poll thread). Different threads, different failures.
- *"Why is my group constantly rebalancing?"* → Processing overrunning `max.poll.interval.ms` is the usual culprit; fix by raising it or lowering `max.poll.records`, not by touching the session timeout.
- *"How do I avoid rebalances on deploys?"* → static membership (`group.instance.id`) + generous session timeout + cooperative sticky assignor.
- *"Default assignor?"* → `[RangeAssignor, CooperativeStickyAssignor]` in current Apache Kafka (Range wins until you drop it from the list); Range is per-topic and can imbalance; cooperative sticky is the modern go-to.
- *"What changes with the new consumer protocol (KIP-848)?"* → session/heartbeat/assignment move **broker-side**; the old client configs are ignored.