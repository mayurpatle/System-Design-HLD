# PACELC — Abadi's Extension of CAP

> Notes on Daniel Abadi's argument that CAP is only half the story.
> Origin: Abadi's 2010 blog post ("Problems with CAP, and Yahoo's little known NoSQL
> system"), formalized in *"Consistency Tradeoffs in Modern Distributed Database System
> Design: CAP is Only Part of the Story"*, IEEE Computer, 2012.

---

## 1. The complaint: CAP doesn't explain what systems actually do

CAP says: during a network **partition**, choose **availability** or **consistency**.

Abadi's observation is an empirical puzzle:

> Systems like Dynamo, Cassandra, and Riak give up consistency **even when there is no
> partition at all.**

If CAP were the whole story, these systems would be strongly consistent during normal
operation (the overwhelmingly common case) and only degrade during a partition. They
aren't. They're eventually consistent *all the time*, by default.

So CAP cannot be the reason they're designed that way. **Something else is driving the
design decision, and CAP hides it.**

### 1.1 Abadi's specific criticisms of CAP

1. **Partitions are rare.** Within a single datacenter with redundant networking, real
   partitions are uncommon. Designing your entire system around a rare event, and using
   that to justify permanent consistency loss, is a non-sequitur.
2. **"Pick 2 of 3" is a misleading framing.** You don't get to choose P. The network
   partitions whether you like it or not; P is a *fault you must tolerate*, not a property
   you select. CAP is really a conditional statement: *if P, then not both A and C.*
3. **The asymmetry is unexplained.** CAP is silent about the *normal-case* behavior, which
   is where systems spend >99.9% of their time.
4. **C in CAP ≠ C in ACID.** CAP's C is linearizability (atomic consistency / recency).
   ACID's C is application-level invariant preservation. Constant source of confusion.
5. **A in CAP is absolute.** CAP's availability means *every request to a non-failed node
   returns a response*. In practice, a response that takes 10 seconds is functionally
   unavailable. Availability and latency are **not** different phenomena — they're points
   on the same continuum. **Unavailability is just latency above your timeout threshold.**

That last point is the hinge of the whole argument.

---

## 2. The formulation

> **PACELC:**
> **if (P)** artition, trade off **A** vailability vs **C** onsistency;
> **E** lse (normal operation), trade off **L** atency vs **C** onsistency.

Pronounced "pass-elk."

You describe a system with **two** letters, one per branch:

```
   PA/EL     PA/EC     PC/EL     PC/EC
```

The point is that the **ELC tradeoff is the one that governs your system almost all of the
time**, and CAP never mentions it.

---

## 3. Why latency and consistency are intrinsically linked

This is the technical core, and the part worth actually understanding. The tradeoff isn't
arbitrary — it falls out of the mechanics of **replication**. Any system that replicates
data must choose *how* updates propagate, and every option costs either latency or
consistency.

Abadi enumerates the replication design space:

### 3.1 Updates sent to all replicas simultaneously

Two sub-cases:

- **Without a preprocessing/agreement layer:** replicas may receive updates in different
  orders → they diverge → consistency lost. (Unless operations are commutative — the CRDT
  escape hatch.)
- **With a preprocessing/agreement layer** that assigns a global order first: consistency
  preserved, but you've added a round-trip to the sequencer *and* you're bounded by the
  slowest replica if you wait for all. **Latency cost.**

### 3.2 Updates sent to a designated master node first

The master serializes writes for that data item. Then it propagates. Three choices:

| Propagation | Consistency | Latency |
|---|---|---|
| **Synchronous** — master waits for *all* replicas to ack | Strong | Bounded by the **slowest** replica |
| **Asynchronous** — master acks the client immediately | Lost (reads at replicas are stale; on master failure, writes lost) | Low |
| **Quorum / majority** — wait for a majority to ack | Strong-ish (needs `w + r > n`) | Bounded by the **median** replica — a middle point, but still > 0 |

Note the subtlety: even *reading* is affected. If reads may go to any replica, a
consistent read requires either routing to the master (latency: possibly a cross-region
hop) or a quorum read (latency: multiple round-trips).

### 3.3 Updates sent to an arbitrary node first (no master)

Dynamo-style. Different nodes can be the "coordinator" for the same key concurrently. Now
even *two writes to the same item* can be ordered differently at different replicas.
Consistency is even harder; you need vector clocks + read-repair + sibling resolution. Same
sync/async/quorum options apply, with the same latency implications.

### 3.4 The conclusion

> **In every replication scheme, consistency costs latency. There is no free branch.**

And this cost is paid on **every single request**, not just during a partition. Hence
Abadi's claim: *the latency–consistency tradeoff is arguably the more important one,
because it's always active.*

Corollary: a system that chose **PC** (consistency during partition) but **EL** (latency
over consistency normally) is not a contradiction — it's a perfectly coherent design.
CAP alone can't even express it.

---

## 4. The four quadrants, with real systems

> Caveat: classifications are configuration- and version-dependent. Most modern systems are
> *tunable*, so treat these as "default posture," not immutable properties.

### PA/EL — give up consistency in both branches
Prioritize availability and low latency; accept eventual consistency.
- **Dynamo, Cassandra, Riak** (with default low `R`/`W`), Voldemort.
- Rationale: shopping cart / session data. Availability and p99 latency are revenue;
  a stale read is a nuisance.

### PC/EC — give up availability and latency; always consistent
Refuse to compromise consistency, ever.
- **VoltDB / H-Store, Megastore, BigTable/HBase**, traditional single-master ACID RDBMSs,
  **Spanner** (approximately — it uses TrueTime to keep the latency cost bounded rather
  than eliminated).
- Rationale: financial ledgers, inventory, anything with a hard invariant.

### PC/EL — consistent under partition, but trades consistency for latency normally
The interesting, counterintuitive quadrant, and the one Abadi wrote the original post to
highlight.
- **Yahoo PNUTS.** Each record has a master, typically placed in the region where that
  record is most written. Reads in remote regions hit a local, asynchronously-updated
  replica → **fast but potentially stale** (that's the EL). But it maintains per-record
  timeline consistency and, on partition, prefers consistency over availability (the PC).
- Why do this? Because in a **geo-distributed** deployment, the normal-case latency of a
  cross-continent round trip (~100ms+) is a bigger practical problem than a rare partition.

### PA/EC — available under partition, consistent normally
- **MongoDB** is Abadi's usual example (single master per shard = consistent reads from
  primary in normal operation; but failover/partition behavior historically could admit
  divergence and rollbacks).
- Somewhat awkward quadrant; less common as a deliberate design.

---

## 5. What PACELC actually buys you

1. **It exposes the tradeoff you're actually making.** Most engineers "choose AP" thinking
   they're guarding against partitions, when in reality they're buying latency.
2. **It explains eventual-consistency systems honestly.** Dynamo isn't AP *because of
   partitions*; it's EL *because of p99 latency*, and the AP falls out for free.
3. **It maps onto knobs you actually turn.** Every consistency knob in every system you use
   is an ELC knob:

| Knob | EC (consistency) | EL (latency) |
|---|---|---|
| Kafka producer | `acks=all` + `min.insync.replicas=2` | `acks=1` / `acks=0` |
| PostgreSQL | `synchronous_commit=remote_apply` | `synchronous_commit=off` / async replica |
| DynamoDB | `ConsistentRead=true` | eventually consistent read (default, ½ the cost) |
| Cassandra | `QUORUM` / `ALL` | `ONE` / `LOCAL_ONE` |
| Redis | `WAIT` command / Redis Enterprise sync | default async replication |
| MongoDB | `writeConcern: majority`, `readConcern: linearizable` | `w:1`, read from secondary |
| Read replicas generally | route reads to primary | route reads to replica |

When someone asks *"should we read from the replica?"* — that is a PACELC question. You are
choosing E**L** over E**C** for that endpoint.

---

## 6. Criticisms and limits of PACELC

- **Still binary.** Consistency is a spectrum (linearizable → sequential → causal →
  read-your-writes → eventual), and PACELC collapses it to a single bit. Real systems sit at
  many points.
- **Per-operation, not per-system.** Any tunable system (Cassandra, Cosmos DB, DynamoDB) is
  in *different quadrants for different queries*. Classifying "the system" is a bit of a
  category error; classify the *workload path* instead.
- **No quantification.** It tells you a tradeoff exists, not what it costs. Spanner's whole
  contribution is that with TrueTime + good networking, EC's latency cost can be pushed low
  enough that you'd rather just pay it. PACELC can't express "EC, but cheap."
- **Throughput is missing.** Coordination costs throughput too, not just latency.
- **Doesn't cover the "why."** It's a taxonomy, not a design methodology.

Its value is pedagogical and rhetorical: it's the cleanest available correction to a decade
of "we're AP, bro" hand-waving.

---

## 7. How this connects to consistency-and-consensus theory

If you've worked through linearizability and consensus, PACELC is essentially the systems-
design vocabulary for one result you already know:

> **Any algorithm providing linearizability has response times at least proportional to the
> uncertainty of network delay.**

That's a *latency* theorem, not an availability theorem. It holds in a perfectly healthy
network. PACELC is that theorem, restated for architects.

Similarly:
- Causal consistency is the strongest model that costs *neither* availability *nor* unbounded
  latency — which is exactly why it's the most interesting target in the "EL but not
  eventually-consistent-garbage" space.
- Consensus (Raft/Paxos) is a PC/EC engine by construction: majority quorums mean every
  committed write pays a round-trip, always.

---

## 8. Quick recall

- **CAP:** if partitioned → A or C. Silent about normal operation. Partitions are rare.
- **PACELC:** if **P** → **A** or **C**; **E**lse → **L** or **C**. The ELC branch is active
  ~100% of the time.
- **Root cause of ELC:** replication. Sync = latency; async = staleness; quorum = a middle
  latency. No free option.
- **Unavailability is just latency past your timeout** — the two CAP/PACELC branches are the
  same tradeoff at different severities.
- **PNUTS is the poster child:** PC/EL — the quadrant CAP can't even describe.
- **Practical translation:** "should this read hit the primary or the replica?" *is* PACELC.