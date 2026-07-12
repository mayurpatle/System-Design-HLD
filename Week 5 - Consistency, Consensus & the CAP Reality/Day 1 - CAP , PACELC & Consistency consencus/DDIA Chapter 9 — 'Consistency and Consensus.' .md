# Consistency and Consensus — Engineering Notes

> Topic notes on the core ideas of distributed consistency: linearizability, ordering,
> total order broadcast, distributed transactions, and consensus.
> Written as study/vault notes, not as a reproduction of any single text.

---

## 0. Why this topic exists

In a distributed system, almost everything can go wrong: packets drop, get delayed,
duplicated, or reordered; clocks skew; nodes pause (GC), crash, or get partitioned away.

Two strategies exist:

1. **Embrace the mess** — build the app to tolerate weak guarantees (eventual consistency,
  CRDTs, last-write-wins). Cheap, fast, but pushes complexity onto the application.
2. **Build a strong abstraction** — pay a coordination cost once, in a general-purpose
  primitive, so applications above it can pretend the system doesn't fail.

This chapter is about strategy 2. The strongest such abstraction is **consensus**:
getting all nodes to agree on something, irrevocably.

An analogy: TCP gives you an ordered, reliable byte stream on top of an unreliable IP
network. It doesn't make packet loss disappear — it *hides* it. Consensus does the same
thing for agreement.

---



## 1. Linearizability (a.k.a. strong consistency, atomic consistency)



### 1.1 The idea

Make the system **behave as if there were only one copy of the data**, and every operation
on it were atomic.

Formally: every read must return the value of the **most recent completed write**, where
"recent" is defined by real (wall-clock) time. Once any client reads the new value,
*every* subsequent read — from any client — must also see the new value or newer. The
value can never "go backwards."

**Key mental model:** each operation takes effect at a single point in time somewhere
between its request being sent and its response being received. If you can draw a valid
sequential order of operations respecting real-time overlap, the history is linearizable.

### 1.2 Linearizability ≠ Serializability

These get confused constantly. Learn the distinction cold:


|                | Serializability                         | Linearizability                           |
| -------------- | --------------------------------------- | ----------------------------------------- |
| Domain         | **Transactions** (multiple objects)     | **Single object**, single operation       |
| Guarantee      | Result == *some* serial execution order | Result respects *real-time* order         |
| Concerned with | Isolation                               | Recency                                   |
| Example impl   | 2PL, SSI                                | Consensus / single-leader with sync reads |


**Strict serializability** = both together. That's what 2PL actually gives you (SSI does
not — it's serializable but not linearizable, since snapshot reads can be stale).

### 1.3 What it's actually needed for

Most applications *don't* need it. But some things are impossible without it:

- **Locking and leader election.** All nodes must agree who holds the lock. Registers that
can disagree = split brain. (This is why ZooKeeper/etcd exist.)
- **Uniqueness constraints.** A unique username, an account balance that can't go negative,
no double-booking of a seat. These are all "atomic compare-and-set" in disguise.
- **Cross-channel timing dependencies.** The classic bug: web server writes an image to
file storage, then puts a "resize this" job on a queue. The resizer picks it up and reads
from a stale storage replica — the file "isn't there yet." The queue is a second
communication channel that leaked recency expectations into the system.



### 1.4 Cost: the CAP theorem, stated properly

When a network partition happens, you must pick:

- **CP** — stay linearizable, refuse to serve requests on the minority side. (Availability lost.)
- **AP** — stay available, serve possibly-stale data. (Linearizability lost.)

CAP is often mis-taught as "pick 2 of 3." Partition tolerance is not optional — the network
*will* partition. The real statement is: **"if partitioned, choose consistency or
availability."** CAP is also narrow: it only considers network partitions, ignoring latency,
dead nodes, and GC pauses. PACELC extends it: *if Partitioned, choose A or C; Else, choose
Latency or Consistency.*

**The deeper cost is latency, not just availability.** Linearizability requires the read to
be aware of the latest write, which requires network round-trips. That's why most
multi-core CPUs are *not* linearizable across cores (store buffers!) and why most
distributed databases drop it. Performance, not fault tolerance, is the usual reason.

There's no way around it: any algorithm giving linearizability has response times at least
proportional to network delay uncertainty.

---



## 2. Ordering and Causality

If linearizability is too expensive, what's the next-best thing? **Causal consistency.**

### 2.1 Causality gives a partial order

Causality means: if event A *happened before* B (A could have influenced B), then everyone
must see A before B.

- **Total order** — any two elements comparable. (e.g., natural numbers; linearizable
system's single timeline.)
- **Partial order** — some pairs are incomparable = **concurrent**. (e.g., set inclusion;
causal ordering.)

So:

- Linearizability = a **total order** of operations. One timeline, no branches.
- Causality = a **partial order**. Concurrent ops form branches; the history is a DAG,
like a git commit graph.

**Causal consistency is the strongest consistency model that does not slow down due to
network delays, and remains available in the face of partitions.** This is a big deal — a
lot of research goes into building causal-but-available databases.

### 2.2 Capturing causal dependencies

To enforce causal order you must know which operation happened before which:

- **Version vectors / vector clocks** — track "what did this node know when it wrote?"
Detects concurrency correctly. Cost: metadata grows with number of nodes.
- **Snapshot isolation** already does a form of this — a snapshot is causally consistent.
- **SSI's SIREAD locks** track read→write dependencies to detect causal violations.



### 2.3 Lamport timestamps

A neat, compact total-ordering trick:

```
timestamp = (counter, nodeID)
```

- Every node keeps a counter, incremented per operation.
- Every request/response carries the max counter seen.
- On receipt, node sets `counter = max(local, received)`.
- Compare by counter first; break ties with nodeID.

This gives a **total order consistent with causality**. It's better than a plain physical
clock and better than a plain counter.

**But it is not enough.** Lamport timestamps tell you the order *after the fact*. For a
uniqueness constraint you need to know **right now** whether some other node is
concurrently claiming the same username — and you can't know that until you've heard from
every other node (one of which might be down). You need the total order to be **fixed and
known as operations happen**, not reconstructed later.

That requirement leads directly to the next primitive.

---



## 3. Total Order Broadcast (Atomic Broadcast)

A protocol for exchanging messages between nodes with two safety properties:

1. **Reliable delivery** — no messages lost; if delivered to one node, delivered to all.
2. **Totally ordered delivery** — messages delivered to *every* node in the *same order*.

Think of it as an **append-only log that all nodes agree on** — which is exactly what Kafka
partitions, ZooKeeper's zab, and etcd's Raft log are.

**Crucial property:** the order is fixed at delivery time. You cannot insert a message
retroactively into the past of the log. This is precisely what Lamport timestamps lacked.

### 3.1 Uses

- **State machine replication.** If every replica starts in the same state and applies the
same deterministic operations in the same order, they end in the same state. This is *the*
foundational replication technique.
- **Serializable transactions** — broadcast the transactions as deterministic stored
procedures, execute in log order on each node.
- **Fencing tokens for locks** — the monotonically increasing sequence number of each log
entry is a perfect fencing token (prevents a GC-paused old leader from corrupting state).
- **Replicated logs / changelogs / CDC.**



### 3.2 Equivalences (memorize this)

These are all reducible to one another — solving one solves all:

```
Total Order Broadcast  ⟺  Linearizable CAS register  ⟺  Consensus
```

**TOB → linearizable register:** to implement a linearizable compare-and-set (e.g., claim a
username), append your intended claim to the log, then *read the log until you see your own
message come back*. If someone else's conflicting claim appears first, you lose; abort.
Waiting for your own message = "sequencing then reading back."

**Linearizable register → TOB:** use a linearizable increment-and-get to allocate a sequence
number to each message, attach it, and have receivers deliver strictly in sequence-number
order (buffering gaps).

**Note:** a linearizable *read* also requires care. Options: (a) push the read through the
log too, (b) fetch the current log head and wait until you've applied it (etcd quorum
reads), (c) read from a leader that is guaranteed non-stale (ZooKeeper `sync()`).

---



## 4. Distributed Transactions and Atomic Commit

Different problem, same family: **all nodes must agree on whether a transaction commits or
aborts**, and that decision is **irrevocable**.

Why irrevocable? Because a commit makes effects visible to others (read-committed), and you
can't un-send an email or un-show a value. This is why commit must be *decided once*.

### 4.1 Two-Phase Commit (2PC)

A **coordinator** (transaction manager) drives it:

1. **Phase 1 — prepare.** Coordinator sends `prepare` to all participants. Each participant
  writes all data to disk and replies `yes` (a *promise* it can commit under any
   circumstances — no going back) or `no`.
2. **Point of no return.** Coordinator writes its decision to its own log. This log write is
  the *commit point*.
3. **Phase 2 — commit/abort.** Coordinator sends the decision. It must **retry forever**
  until each participant acknowledges. Participants may not refuse.

Two irrevocable promises make it work: participants can't renege on a "yes," and the
coordinator can't change its logged decision.

### 4.2 The fatal flaw: coordinator failure

If the coordinator crashes **after** participants voted `yes` but **before** sending the
decision, participants are **in doubt / uncertain**. They hold locks and *cannot
unilaterally decide*. They must wait for the coordinator to come back and read its log.

These are **stuck / orphaned transactions**. They hold row locks, blocking other traffic,
potentially forever. Manual DBA intervention (heuristic decisions = "break atomicity,
guess") is the escape hatch, and it may silently violate consistency.

2PC is therefore a **blocking** atomic commit protocol. (3PC assumes bounded network delay
and bounded response times, which real networks don't provide — so it isn't a practical fix.
Nonblocking commit really requires a *fault-tolerant* coordinator, i.e., consensus.)

### 4.3 XA transactions and the practical pain

XA is the standard C API for 2PC across heterogeneous systems (a DB + a message broker).
The coordinator is often just a **library in the application process** — so if that app
server dies, its log (on its local disk) is the only thing that can resolve in-doubt
transactions. The coordinator becomes a stateful, unreplicated single point of failure that
must be restarted to unblock the database. This is why heavyweight XA distributed
transactions have largely fallen out of fashion.

**Rough rule for interviews:** prefer sagas / outbox pattern / idempotent consumers over XA.

---



## 5. Consensus



### 5.1 The goal

One or more nodes propose values; the algorithm **decides** on one. Formal properties:

- **Uniform agreement** — no two nodes decide differently. *(safety)*
- **Integrity** — no node decides twice. *(safety)*
- **Validity** — if a node decides `v`, then `v` was proposed by some node. *(safety, rules
out trivial "always decide 0")*
- **Termination** — every non-crashed node eventually decides. *(liveness)*

Termination is the hard one. It's what makes consensus **fault-tolerant** — it forbids the
2PC-style "just wait for the coordinator forever" answer.

Bound: consensus requires a **majority quorum** to make progress. You can tolerate `f`
failures with `2f + 1` nodes. Note this assumes no Byzantine faults (nodes that lie);
Byzantine-tolerant consensus needs `3f + 1`.

**FLP impossibility:** no *deterministic* algorithm can guarantee consensus in a fully
asynchronous system where even one node may crash. Real systems escape FLP by using
timeouts / randomness — timeouts don't make the system synchronous, they just give a
probabilistic liveness escape hatch. FLP constrains liveness, never safety.

### 5.2 How real algorithms work

Viewstamped Replication, Paxos/Multi-Paxos, Raft, and Zab all implement **total order
broadcast**, not just single-value consensus — they decide a *sequence* of values (a log).

The shape is always the same:

1. **Elect a leader** (unique per **epoch / term / ballot number** — a monotonically
  increasing integer).
2. **Leader proposes**; followers vote. Before accepting, a follower checks: *is there a
  leader with a higher epoch?* If so, reject.
3. **Two rounds of voting per decision** — once to elect the leader, once on the leader's
  proposal. A proposal is committed once a **quorum** has accepted it.

The critical insight: **the quorums must overlap.** The quorum that elected the leader and
the quorum that accepted the proposal share at least one node. That node can reveal a
higher-epoch leader, so a stale leader (e.g., one that GC-paused and woke up) cannot commit
anything. This is what makes the protocol safe without relying on clocks.

Compared to 2PC: the coordinator isn't *fixed* — it's *elected*, and it only needs a
majority, not unanimity. That's the whole difference between blocking and fault-tolerant.

### 5.3 Limitations of consensus

- **Cost of the round-trips.** Synchronous replication + majority acks = latency floor.
Many databases choose async replication and accept possible durable-write loss on failover.
- **Fixed membership.** Adding/removing nodes requires *dynamic membership* extensions,
which are notoriously tricky to get right.
- **Timeout tuning.** Too aggressive → spurious leader elections in a temporarily slow
network → thrashing and *worse* availability. Too lax → long unavailability on real failure.
- **Network sensitivity.** Notably, unreliable networks (or, in geo-distributed setups, WAN
latency) can make Raft leaders flap.

---



## 6. Membership and Coordination Services (ZooKeeper / etcd)

You usually don't implement consensus yourself. You use an **outsourced consensus
service**, which holds a small amount of data (fits in memory) replicated via total order
broadcast.

What they give you:

- **Linearizable atomic operations** — a CAS to implement a **distributed lock** / lease.
- **Total ordering with fencing tokens** — every op gets a monotonic `zxid` / version. This
is the *fix for the GC-pause split-brain bug*: the storage service rejects any write
carrying a stale token.
- **Failure detection** — sessions + heartbeats; on session expiry, held locks/ephemeral
nodes are automatically released.
- **Change notifications (watches)** — subscribe and be told when a node joins/leaves or a
key changes, instead of polling.

Typical uses:

- **Leader election / partition assignment** — decide which node owns which shard.
(Rebalancing partitions on scale-out, e.g., in Kafka or HBase.)
- **Service discovery** — though note *reads* of a service registry often tolerate staleness,
so DNS-style eventual consistency is fine there; the *writes* need consensus.
- **Membership service** — an agreed-upon answer to "who is alive?" It may be *wrong* (a
node declared dead was just slow), but everyone *agrees* on the answer, which is what
matters for correctness.

**Design note:** ZooKeeper coordinates *metadata* at a slow-changing rate (seconds/minutes),
not application data at high throughput. Don't put your workload in it.

---



## 7. Interview / GATE Cheat-Sheet

**One-liners:**

- Linearizability = recency guarantee on a single object; pretend there's one copy.
- Serializability = isolation guarantee across transactions; some serial order.
- Causal consistency = strongest model that survives partitions without blocking.
- Lamport timestamps give total order consistent with causality, but only *retroactively*.
- Total order broadcast = a replicated append-only log = state machine replication.
- TOB ⟺ linearizable CAS ⟺ consensus. All the same problem.
- 2PC is blocking (fixed coordinator, unanimous vote); consensus is fault-tolerant
(elected leader, majority vote).
- Consensus needs `2f+1` nodes for `f` crash faults; `3f+1` for Byzantine.
- FLP: no deterministic async consensus with one crash → we use timeouts/randomness for
liveness. Safety is never sacrificed.
- Fencing tokens prevent a paused-then-resumed leader from corrupting shared storage.

**Common trap questions:**

- *"Is a single-leader DB linearizable?"* Only if reads go to the leader (or a
synchronously-updated quorum) **and** the leader is genuinely the only leader — which
requires consensus to guarantee. Otherwise: split brain.
- *"Is quorum reading/writing (w + r > n) linearizable?"* Naively **no** — concurrent
writes and partial failures break it. You need read-repair-on-read plus a synchronous
write path; Dynamo-style quorums generally are not linearizable.
- *"Is snapshot isolation linearizable?"* No — reads come from a consistent-but-stale
snapshot by design.
- *"Why can't multi-leader be linearizable?"* Two leaders accept conflicting writes
concurrently; the single-copy illusion breaks immediately.

---



## 8. Where to go next

- Raft paper ("In Search of an Understandable Consensus Algorithm") — read this before Paxos.
- Paxos Made Simple / Paxos Made Live (the Chubby paper — great on real-world gaps).
- Jepsen analyses (jepsen.io) — empirical proof of how often vendors get this wrong.
- Kafka's KRaft — Raft-based controller quorum replacing ZooKeeper. Directly relevant to
the Kafka work; the controller log *is* total order broadcast.

