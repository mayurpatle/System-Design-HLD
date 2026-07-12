# Distributed Transactions & Two-Phase Commit

> Engineering notes on atomic commit across nodes: 2PC, its fatal flaw, XA in practice,
> why the industry walked away from it, and what replaced it.
> Companion to `consistency-and-consensus.md` — this is the "agreement on *commit*" half.

---

## 1. The problem: atomic commit

A transaction touching **multiple nodes** (multiple shards, or a database *and* a message broker)
must be **all-or-nothing across all of them.** Either every node commits, or every node aborts.

**Why it's hard:** each node might independently succeed or fail. Node A commits, node B crashes
before committing → you have a partial transaction, and your invariant is broken.

### Why commit is irrevocable

The single most important fact in this whole area:

> **Once a transaction commits, it CANNOT be undone.**

Because:
- **Its effects become visible to other transactions** (read-committed). Other transactions read
  the data and make decisions based on it. You can't retroactively unread it.
- **Its side effects escape the database.** An email was sent. A message was published. A
  notification fired. There is no `UNSEND`.
- A later "compensating transaction" is **a new transaction**, not an undo. The world saw the
  intermediate state.

**Therefore: a node must not commit until it is certain every other node will commit too.**
That certainty is what 2PC buys, and its cost is what the rest of this note is about.

### Single-node atomicity, for contrast

On one node, atomicity is easy: the storage engine writes the data to disk (WAL), and then writes
**a single commit record**. That one disk write is **the moment of commit** — the transaction is
atomic because that write is atomic. Everything hinges on **one device performing one write.**

**Distributed atomic commit is the problem of recreating that single moment across many machines.**

---

## 2. Two-Phase Commit (2PC)

An algorithm for atomic commit across nodes. It introduces a **coordinator** (a.k.a. *transaction
manager*), often a library inside the application process.

### The protocol

```
                COORDINATOR                      PARTICIPANTS (A, B, C)

 (app reads/writes on each node, using a global transaction ID)

 PHASE 1  ──── prepare(txid) ──────────────►    each participant:
                                                  • writes ALL data to disk (durably!)
                                                  • checks constraints
                                                  • decides: can I commit under
                                                    ANY circumstance, including a crash?
          ◄──── yes / no ─────────────────

 ── COMMIT POINT ────────────────────────────────────────────────────────
    Coordinator writes its DECISION to its own log on disk.
    THIS write is the moment of commit. It is irrevocable.
 ─────────────────────────────────────────────────────────────────────────

 PHASE 2  ──── commit / abort ─────────────►    participant MUST comply.
              (retry FOREVER until acked)       It has no right to refuse.
```

### The two irrevocable promises

**This is the whole design, and it's worth stating precisely:**

1. **A participant that votes "yes" gives up its right to abort.** It is promising: *"I can commit
   this, no matter what — even if I crash and restart right now."* That's why it must write all the
   data to disk **before** voting yes. It hasn't committed, but it has made commit **inevitable**.
2. **A coordinator that logs its decision gives up its right to change its mind.** The decision is
   durable. On restart, it reads the log and finishes the job.

**Together, these two promises manufacture the "single atomic moment" that a single-node commit gets
for free.** The coordinator's log write *is* the commit record.

---

## 3. The fatal flaw: coordinator failure

**The scenario:**

```
1. All participants vote YES.
2. The coordinator crashes ── BEFORE writing/sending its decision.
3. ...
```

Now every participant is **in doubt** (a.k.a. **uncertain**):

- It **cannot commit unilaterally** — maybe someone else voted no, and the decision was abort.
- It **cannot abort unilaterally** — maybe the coordinator already logged *commit*, and told some
  other participant to commit. Aborting would break atomicity.
- **It already promised.** It gave up the right to decide.

> **So it must WAIT. Holding its locks. Indefinitely.**

These are **stuck / orphaned / in-doubt transactions.** And they don't just sit there quietly:

- **They hold row locks** (typically exclusive locks on every row they touched).
- **Every other transaction touching those rows blocks behind them.**
- A handful of orphaned transactions can **take a production database down.**

**And nothing can rescue them except the coordinator coming back and reading its log.**
Not a timeout. Not a majority vote. **Only that specific machine's disk.**

> **2PC is a BLOCKING atomic commit protocol.**

### The escape hatch: heuristic decisions

Most XA implementations let a DBA **manually force** an in-doubt transaction to commit or abort.
This is called a **heuristic decision**, and the terminology is a euphemism:

> **A heuristic decision is a guess that may silently violate atomicity.**

You are unilaterally deciding for a node that promised not to decide. If you guess wrong, you've
committed on one node and aborted on another. **It exists only to get you out of an emergency**,
and it exchanges a liveness problem for a (silent) safety problem.

### Why 3PC doesn't save you

Three-phase commit adds a pre-commit phase intended to make commit **non-blocking**. But it assumes:

- **bounded network delay**, and
- **bounded process response times**.

Real networks provide neither (unbounded delays, and GC pauses that look exactly like crashes). **A
perfect failure detector is impossible in an asynchronous system**, so 3PC is not a practical fix.

> **Non-blocking atomic commit requires a *fault-tolerant* coordinator — i.e., consensus.**

---

## 4. 2PC vs Consensus — the comparison that clarifies everything

| | **2PC** | **Consensus (Raft/Paxos)** |
|---|---|---|
| Coordinator | **Fixed.** Chosen once, cannot be replaced. | **Elected.** Replaceable at any time. |
| Vote required | **UNANIMOUS** — every participant must say yes | **MAJORITY** — a minority may be down |
| Coordinator failure | **Blocks forever.** Participants stuck in doubt. | **Elect a new leader** and continue. |
| Fault tolerance | **None** (for the coordinator) | Tolerates `f` failures with `2f+1` nodes |
| What's being decided | Commit or abort *this* transaction | The next value in a log |

**They look similar and are fundamentally different.** The difference is exactly two things:
**unanimous vs majority**, and **fixed vs elected coordinator**. Those two choices are the
difference between *blocking* and *fault-tolerant*.

**Why 2PC needs unanimity:** because a participant can veto for *local* reasons — a constraint
violation, a deadlock, a disk-full. Consensus doesn't care what the value *is*; 2PC does. That's
why you can't just "run Raft over the commit decision" and call it a day — though you *can* make
the **coordinator itself** a Raft group, which is exactly what modern systems (Spanner,
CockroachDB) do. **Spanner = 2PC across shards, where each participant and the coordinator is
itself a Paxos group.** That's how you get non-blocking distributed transactions: *make every
single-point-of-failure a quorum.*

---

## 5. Distributed transactions in practice

Two very different things get called "distributed transactions," and conflating them is the source
of most confusion:

### 5.1 Database-internal distributed transactions

All participants run **the same database software**. Examples: **VoltDB, MySQL NDB Cluster,
Spanner, CockroachDB, YugabyteDB.**

- ✅ Can use **optimized, proprietary protocols.**
- ✅ Can rely on shared assumptions (same data format, same locking, same failure detection).
- ✅ **These generally work fine** — often performing well.

### 5.2 Heterogeneous distributed transactions — **XA**

Participants are **different technologies** — e.g. a Postgres database *and* a Kafka/ActiveMQ
broker, or two different vendors' databases.

**XA (eXtended Architecture)** is the standard: **a C API for interfacing with a transaction
coordinator**, supported by many DBs and message brokers, exposed in Java as **JTA**, used by
**JDBC** drivers and **JMS**.

**And here's the thing that kills it:**

> **The XA coordinator is often just a LIBRARY inside your application process.**
> **Its transaction log lives on that application server's LOCAL DISK.**

Consequences, and they're brutal:

- **Your stateless application server is now stateful.** It holds the only copy of the state needed
  to resolve in-doubt transactions.
- **If that app server dies, the log dies with it** — and **the databases stay locked** until you
  bring **that exact machine, with that exact disk**, back up.
- **You cannot just spin up a replacement container.** In a Kubernetes world, this is fatal. The
  coordinator log must be as durably replicated as the databases it coordinates — and in practice,
  it isn't.
- **Restarting the app server does not clear the locks** unless the log is intact and replayed.

---

## 6. The other limitations of XA

Beyond the coordinator problem:

**Lowest common denominator.** XA can't see inside the participating systems, so it can only do
what *every* participant supports. Concretely:

- **It cannot detect deadlocks across systems.** Each database detects its own deadlocks; nobody
  sees the cycle spanning both.
- **It doesn't work with SSI (serializable snapshot isolation)**, since SSI needs a protocol for
  identifying conflicts across systems — which doesn't exist.
- So in practice XA transactions run under **2PL**, holding locks for the duration.

**It amplifies failures.** This is the deepest objection:

> **A distributed transaction makes the system as fragile as its *least* available component,
> instead of as robust as its most available one.**

Normally, a fault in one service is contained. Under 2PC, a fault in **any** participant (or the
coordinator) escalates into **stuck locks and cascading blocking** everywhere else. The whole point
of building separate services was **fault isolation** — and XA deliberately undoes it. That's why
"distributed transactions across microservices" is usually an anti-pattern.

**Performance.** The cost is severe: extra disk forces (fsync) for crash recovery in the
coordinator, plus multiple network round-trips, plus locks held across all of them. Reported
overheads in the literature are large (often cited around **10× vs single-node transactions**).

---

## 7. What replaced it

### 7.1 Sagas

Break the distributed transaction into a **sequence of local transactions**, each with a
**compensating action**.

```
  Order Service    → create order (local txn)     ⟲ cancel order
  Payment Service  → charge card  (local txn)     ⟲ refund card
  Inventory        → reserve item (local txn)     ⟲ release item
```

If step 3 fails, run the compensations for 2 and 1, in reverse.

- ✅ **No blocking. No global locks. No coordinator SPOF.**
- ❌ **No isolation.** Intermediate states are visible — someone *can* see an order that's about to
  be cancelled. You give up the "A" and the "I" of ACID and get **eventual atomicity**.
- ❌ **Compensations must be written by you, and must be idempotent, and must always succeed.**
- Two flavors: **choreography** (services react to each other's events) and **orchestration** (a
  saga coordinator drives the steps — much easier to reason about at any real scale).

### 7.2 The Outbox pattern — *the one you'll actually use*

**The classic problem:** you need to write to the DB **and** publish an event to Kafka, atomically.
Do it naively and you get one of two bugs:

```
save to DB → publish to Kafka        ← crash in between = DB updated, no event   (lost event)
publish to Kafka → save to DB        ← crash in between = event sent, no DB row   (phantom event)
```

**XA was the "correct" answer. Nobody uses it. Instead:**

```sql
BEGIN;
  INSERT INTO orders (...);                       -- your business write
  INSERT INTO outbox (topic, payload, ...);       -- the event, in the SAME local transaction
COMMIT;                                            -- ONE atomic local commit. No 2PC.
```

Then a **separate relay** (a poller, or **CDC via Debezium** reading the WAL) publishes rows from
`outbox` to Kafka and marks them sent.

- ✅ **Atomicity comes free from the single-node transaction.** You never needed 2PC.
- ✅ Survives every crash: the event is durable the moment the business data is.
- ⚠️ **Gives you at-least-once delivery**, not exactly-once. The relay may crash after publishing
  but before marking the row sent → **duplicate**.
- ⇒ **Consumers must be idempotent.** Which brings us to:

### 7.3 Idempotence — the real foundation

**You cannot achieve exactly-once *delivery* over an unreliable network.** What you can achieve is
**exactly-once *effect***, by making duplicate deliveries harmless:

- **Deduplication key / idempotency key** — the consumer records processed message IDs and drops
  repeats. (Store the dedup ID **in the same transaction** as the effect, or you've just moved the
  problem.)
- **Naturally idempotent operations** — `SET status = 'PAID'` is safe to apply twice;
  `balance = balance - 100` is not.
- **Conditional writes / fencing** — `UPDATE ... WHERE version = 5` (the same pattern as the atomic
  conditional UPDATE that fixes an oversell).

> **The industry's answer to distributed transactions is: don't have them. Have one local
> transaction, at-least-once delivery, and idempotent consumers.**

### 7.4 Kafka transactions (worth knowing, since it's on your stack)

Kafka's "exactly-once semantics" is **not** XA. It's:
- an **idempotent producer** (producer ID + sequence number → the broker dedups retries), plus
- **transactions across partitions** (`transactional.id`, `initTransactions`, `beginTransaction`,
  `sendOffsetsToTransaction`, `commitTransaction`), coordinated by a **transaction coordinator**
  whose log is itself **a replicated Kafka topic** (`__transaction_state`).

**Notice the move:** it *is* two-phase commit — but **the coordinator's log is replicated by the
consensus/replication layer**, so coordinator failure isn't fatal. Same trick as Spanner. **And it
only covers Kafka→Kafka** (read-process-write). It does **not** give you atomicity between Kafka and
your Postgres — that's still the outbox pattern's job.

---

## 8. When 2PC is still right

Don't over-learn the lesson. 2PC is correct and appropriate when:

- **It's internal to one database** (Spanner across shards, CockroachDB across ranges) — because
  there, **the coordinator is itself a consensus group**, so the fatal flaw is engineered away.
- **You control all participants** and can guarantee the coordinator's log is durable and
  replicated.
- **The invariant genuinely cannot tolerate an intermediate state** — a ledger where money must
  never exist in two places or neither.

**The failure mode to avoid is the one it's famous for:** XA across heterogeneous systems, with the
coordinator as a library in an ephemeral app server.

---

## 9. Interview traps

**"How do you write to the DB and publish to Kafka atomically?"**
> **Outbox pattern.** Not XA. One local transaction writes both the business row and the event row;
> a relay (CDC/Debezium) ships the outbox to Kafka. At-least-once + idempotent consumers.
> *If you answer "distributed transaction," you've failed the question.*

**"Why is 2PC blocking?"**
> Because after voting yes, a participant has **given up its right to decide**, and the **decision
> lives only in the coordinator's log**. No timeout or vote can substitute — only that coordinator
> coming back. Contrast with consensus: **elected leader + majority vote** ⇒ a replacement can be
> chosen.

**"Is exactly-once delivery possible?"**
> **No.** Exactly-once *delivery* is impossible over an unreliable network. Exactly-once *effect*
> is achievable through **idempotence + deduplication keys**, stored transactionally with the
> effect.

**"What's the difference between 2PC and consensus?"**
> **Unanimous vs majority. Fixed vs elected coordinator.** That's it — and that's everything.

**"Why do microservices avoid distributed transactions?"**
> Because **XA amplifies failures**: it makes the system as available as its *least* available
> participant and holds locks across service boundaries — destroying the fault isolation that was
> the point of splitting the services up. Sagas trade isolation for availability instead.

---

## 10. Recall

- **Commit is irrevocable** — effects are visible and side effects escape. Hence a node must not
  commit until commit is certain everywhere.
- **2PC = prepare (durably write, then promise) → coordinator logs decision (THE commit point) →
  commit (participants must comply; retry forever).**
- **Two irrevocable promises:** a "yes" vote can't be withdrawn; a logged decision can't be changed.
- **Coordinator crash between the votes and the decision ⇒ participants are IN DOUBT, holding locks,
  forever.** Only the coordinator's log resolves it. **Heuristic decisions = a guess that may break
  atomicity.**
- **3PC needs bounded delays and a perfect failure detector ⇒ not usable.**
- **2PC vs consensus: unanimous+fixed (blocking) vs majority+elected (fault-tolerant).**
- **XA's killer flaw:** the coordinator is a **library in the app process**, with its log on a
  **local disk** ⇒ your stateless service is now stateful, and its death locks your databases.
- **XA is lowest-common-denominator** (no cross-system deadlock detection, no SSI) and **amplifies
  failures** — the opposite of what services are for.
- **Modern answers:** **sagas** (compensations; no isolation), **outbox + CDC** (one local
  transaction; at-least-once), **idempotent consumers** (exactly-once *effect*).
- **Spanner / CockroachDB / Kafka transactions all use 2PC — but with a REPLICATED coordinator.**
  That's the fix: make the single point of failure a quorum.