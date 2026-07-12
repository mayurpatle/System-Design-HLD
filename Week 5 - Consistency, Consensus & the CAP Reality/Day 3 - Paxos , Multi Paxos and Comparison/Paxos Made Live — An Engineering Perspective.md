# Paxos Made Live — An Engineering Perspective

> Chandra, Griesemer & Redstone (Google), PODC 2007.
> What actually happens when you take Lamport's one page of pseudo-code and try to ship it.
> My own notes; the paper's own framing is "algorithmic gaps → software engineering → unexpected failures."

---

## 0. The thesis, stated bluntly

The paper's closing indictment is the reason anyone still reads it. Paraphrasing the three
shortcomings the authors lay at the feet of the field:

1. **There are large gaps between the published Paxos algorithm and what a real system needs.**
   To build one, an expert must assemble ideas scattered across the literature and invent
   several protocol extensions of their own — and the resulting system rests on **a protocol
   that was never proven correct**, because it isn't the one in the papers anymore.
2. **The fault-tolerance community hasn't built tools** to make its algorithms easy to implement.
3. **The community hasn't taken testing seriously**, though testing is the key ingredient in
   actually getting a fault-tolerant system to work.

Their comparison is devastating and fair: **compiler construction** solved this. The theory is
hard, but yacc appeared soon after parsing theory matured, then ANTLR, tree-rewriters,
assemblers — an entire tool family. Parsing is now an undergrad topic. Distributed consensus
has no equivalent, so every implementation is an artisanal re-derivation.

The concrete symptom: **Paxos fits on a page of pseudo-code; their implementation is several
thousand lines of C++.** And the authors are explicit that the blow-up isn't C++ verbosity —
it's all the features and optimizations, published and unpublished, that a production system
demands. The community knows how to prove a one-page algorithm correct. **That approach does
not scale to thousands of lines.**

**Why this paper matters to you:** it is the single best answer to "why did Raft need to exist?"
Raft is, in large part, a direct response to this paper's complaints.

---

## 1. Context: Chubby

- **Chubby** = Google's fault-tolerant **distributed lock service + small-file store**. One
  instance ("cell") per datacenter. **GFS and Bigtable** use it for coordination and metadata.
- A cell is **5 replicas**, identical code, dedicated machines. Every Chubby object (a lock, a
  file) is **a row in a database** — and *that database* is what's replicated.
- **One replica is the master and serves all requests.** Contact a non-master and it just hands
  you the master's address. On master failure, a new one is elected and continues from its
  local copy of the replicated DB.

**The origin story is a great piece of engineering honesty:** Chubby v1 was built on a
**commercial third-party fault-tolerant database** (called "3DB" in the paper). It had a
**history of replication bugs**. As far as the authors knew, **its replication wasn't based on
any proven algorithm, and they didn't know whether it was correct.** Given Chubby's importance,
they replaced it with something built on Paxos.

So the project's actual motivation was: *"we are running our most critical coordination service
on a replication mechanism nobody can vouch for."*

---

## 2. Architecture — three clean layers

```
┌──────────────────────────────────────────────┐
│  Chubby            (locks, small files)      │
├──────────────────────────────────────────────┤
│  Fault-tolerant DB (snapshot + replay log)   │
├──────────────────────────────────────────────┤
│  Fault-tolerant LOG  ← Multi-Paxos           │
└──────────────────────────────────────────────┘
                one Chubby replica
```

- The **Paxos-based replicated log** sits at the bottom. Each replica keeps a local copy;
  Paxos is run repeatedly to keep the sequences identical.
- The **replicated database** = a **local snapshot** + a **replay log of DB operations**. New
  operations get submitted to the replicated log; when an operation surfaces at a replica, it's
  applied to that replica's local DB.
- **Chubby** stores its state in that DB.

**The design conviction worth stealing:** they deliberately built **clean interfaces separating
the three layers**, intending to **reuse the replicated-log layer for other systems**, because
they believe **a fault-tolerant log is a powerful primitive** to build on. (This is the same bet
KIP-500 makes for Kafka metadata, and the same bet Raft's paper makes. "The log is the
primitive" is one of the load-bearing ideas of the era.)

**Threading choice with a payoff later:** the replicated log **creates no threads of its own**,
though any number of threads may call into it concurrently. This was done specifically to make
the system **testable** — it can be driven single-threaded and deterministically. Remember this;
it pays off in §5.

---

## 3. Their framing of Paxos (worth noting the vocabulary)

The paper uses **coordinator** where Lamport says proposer, and **replica** where he says
acceptor. Three phases:

1. **Elect a coordinator.**
2. Coordinator **selects a value and broadcasts an `accept` message**; replicas ack or reject.
3. Once a **majority acks**, consensus is reached and the coordinator broadcasts **`commit`**.

Two mechanisms make concurrent coordinators safe:
- **Order the coordinators** with increasing sequence numbers (each replica picks the smallest
  number larger than any it's seen with `s mod n = its_id` — a neat trick for uniqueness), sent
  in a **`propose`** message; a majority replying that they've seen nothing higher constitutes
  **`promise`** messages.
- **Restrict the coordinator's choice of value:** promises carry the **most recent value the
  replica has accepted** plus the sequence number of the coordinator it came from, and the new
  coordinator **must pick the value from the most recent coordinator**. Only if no promise
  carries a value is it free to choose its own.

(Same P2c logic as Paxos Made Simple, in engineer-speak.)

### Multi-Paxos, and the disk-write count

**The naive implementation costs five disk flushes on the critical path** — one for each of
`propose`, `promise`, `accept`, `acknowledge`, `commit` — because every sender must log its
state before sending. **When replicas are close together on the network, disk flush time
dominates latency.** This is the real cost of consensus, and it's not the network.

**The optimization:** if the coordinator doesn't change between instances, **`propose` messages
can be omitted**. Keep one coordinator for a long time and call it **the master**. This drops
the cost to **a single disk write per Paxos instance per replica**, done in parallel: the master
writes right after sending `accept`, the others write before sending `acknowledge`.

Throughput extra: **batch values from multiple application threads into one Paxos instance.**

---

## 4. The algorithmic gaps (the meat)

These are the things Paxos-on-paper doesn't tell you, and each one is a real extension.

### 4.1 Disk corruption — *"a replica can renege on its promises"*

**The problem:** disks get corrupted, via media failure or **operator error** (someone deletes
critical data). A replica that loses its persistent state **may renege on promises it made** —
which **violates a core Paxos assumption.** Paxos assumes stable storage is *stable*. Reality
disagrees.

**Detection**, two failure modes:
- **File contents changed** → detected via a **checksum stored inside each file**.
- **File inaccessible/gone** → this is **indistinguishable from a brand-new replica with an
  empty disk**. Clever fix: **a new replica leaves a marker in GFS at startup.** If a replica
  ever boots with an empty disk *and finds its GFS marker*, it knows it isn't new — it's
  **corrupted**.

**Recovery:** the corrupted replica rebuilds by participating as a **non-voting member** — it
uses catch-up to get current, but **sends no `promise` or `acknowledge` messages.** It stays
non-voting **until it observes one complete instance of Paxos that began after it started
rebuilding.** Waiting that extra instance out guarantees it **cannot have reneged on an earlier
promise.**

*(A nice side note they raise but didn't implement: once you can tolerate occasional disk
corruption, you might not need to flush every write to disk immediately — a big latency win.)*

### 4.2 Master leases — *"how do you read without paying for consensus?"*

**The problem:** with plain Paxos, **every read must run an instance of Paxos.** Why? Because
**the master can't trust its own local copy** — other replicas might have elected a new master
and modified the data without telling it. Serving a read locally risks **stale data**. And since
**reads dominate the workload**, running Paxos for each is brutally expensive.

**The fix — master leases:** while the master holds the lease, **no other replica can
successfully submit values to Paxos.** Therefore the master's local copy **is** up to date, and
**reads can be served purely locally.** The master renews before expiry, so it holds the lease
essentially all the time — in production, **masters hold leases for days at a stretch**.

Implementation details that matter:
- **All replicas implicitly grant a lease to the master of the previous Paxos instance**, and
  refuse to process Paxos messages from anyone else while it's held.
- **The master uses a *shorter* timeout for its own lease than the replicas do** — the safety
  margin that **protects against clock drift**. (This is where a real system quietly takes on a
  timing assumption. Safety now leans on bounded clock error.)
- The master **periodically submits a dummy heartbeat value** to Paxos to refresh the lease.

### 4.3 Master churn — an instability Multi-Paxos introduces

**The bug pattern:** a master briefly disconnects → a new master is elected and holds a fixed
sequence number across instances. Meanwhile the **old master, still trying to run Paxos, may
connect to some replica and bump its own sequence number higher**. When it reconnects, its
number **outranks the new master's** and it **displaces it**. Then it disconnects again. Loop.

Result: **rapid, repeated master changes** in a poorly-connected network — and Chubby master
changes hurt its users.

**Fix:** the master **periodically "boosts" its sequence number** by running a full round of
Paxos (`propose` messages included). Boost at the right frequency and the churn mostly goes
away. In a loaded system, **under 1% of Paxos instances run the full algorithm.**

*(Note the shape of this bug: it doesn't exist in the algorithm. It only exists because of the
optimization. This is exactly the "your final system is based on an unproven protocol" problem.)*

### 4.4 Epoch numbers — mastership can change *mid-request*

**The problem:** between the moment the master receives a Chubby request and the moment it
updates the database, **it may have lost mastership — and even regained it.** Chubby requires
the request to be **aborted** if that happened. So you need to reliably detect master turnover.

**The fix:** a **global epoch number** with a precise contract:

> **Two reads of the epoch number at the master return the same value if and only if that
> replica was master *continuously* for the entire interval between them.**

The epoch is **stored as an entry in the database**, and **all database operations are made
conditional on its value.** (Which is why MultiOp, below, turned out to be so useful.)

**This is a fencing token.** Same idea as ZooKeeper's `zxid` and Raft's term — under a different
name, discovered because a real workload demanded it.

### 4.5 Group membership — *"the literature just doesn't cover this"*

Papers note that Paxos itself can be used to implement group membership, and with **core Paxos
it is straightforward.** But **once you add Multi-Paxos, disk corruption handling, and so on,
the details become non-trivial** — and the authors state plainly that **the literature neither
spells this out nor proves any of it correct.** They had to fill the gap themselves, and call
the details "subtle."

That's a remarkable admission: **one of the most important pieces of a production consensus
system had no correct published algorithm.** (This is precisely why Raft devotes a whole section
to joint consensus, and why membership change is still where implementations get bitten.)

### 4.6 Snapshots — the log grows forever

**Two problems with an unbounded log:** unbounded disk, and **unbounded recovery time** (a
recovering replica must replay everything).

**But the Paxos framework knows nothing about the data structure being replicated** — it only
cares about log consistency. **So the *application* must take snapshots.** The framework offers
a hook: tell me you snapshotted, and I'll truncate the log.

This "obvious" mechanism, briefly mentioned in the literature, **introduces a lot of complexity.**
The persistent state is now **a log plus a snapshot that must be mutually consistent**, where
the log is the framework's and the snapshot format is the application's. Their solutions:

- **Snapshot handle** — the framework hands the app an opaque handle capturing the Paxos state
  at that log position (**the Paxos instance number** and **the group membership at that point**).
  The app stores it alongside its snapshot and returns it on recovery. *The handle is a snapshot
  of Paxos itself.*
- **Non-blocking snapshots, in three phases:** app requests a handle → app takes the snapshot
  (possibly on a separate thread while the replica keeps participating in Paxos) → app tells the
  framework, which truncates. The snapshot must correspond to the state **at the log position
  where the handle was obtained**, so if you keep serving updates you need care. *Their evolution
  is instructive:* v1 **briefly blocked** the system to make an in-memory copy of the (small) DB,
  then wrote it out on another thread. Later they built **virtually pause-less snapshots** using
  a **"shadow" data structure to track updates** while the DB is serialized to disk.
- **Snapshots can fail.** The framework truncates **only** when told a snapshot exists and given
  its handle — so the app can **verify the snapshot's integrity and discard it** if it's bad.
- **Catch-up interacts nastily.** A lagging replica that can't get old-enough log records must
  fetch a **snapshot** instead, then request the remaining log records. But **the leader may
  create a *newer* snapshot while the laggard is installing the old one**, at which point the
  laggard may find **no one has the log records it now needs** — and must go fetch a more recent
  snapshot. And **the leader may die mid-transfer**, so catch-up must fail over to another
  replica. In a fault-tolerant system, none of this can be designed away.

### 4.7 MultiOp — transactions without a transaction system

Chubby's DB needs are modest: key-value pairs, insert/delete/lookup, **atomic compare-and-swap**,
and iteration. CAS is easy in this architecture — **just submit all the CAS data as a single
value to Paxos**, and the log's serialization makes it atomic for free.

They then generalized that into **`MultiOp`**, which they explicitly flag as a primitive they
think is broadly useful. **Every DB operation except iteration is a single `MultiOp` call.**
It is applied **atomically** and has three parts:

| Part | Meaning |
|---|---|
| **`guard`** | A list of **tests**, each checking one DB entry (presence, absence, or comparison to a value). All are evaluated. |
| **`t_op`** | A list of operations (insert / delete / lookup) executed **if guard is all true**. |
| **`f_op`** | Same, executed **if guard is false**. |

**That's it.** `if (all conditions hold) then do X else do Y`, atomically. You get
transaction-*style* semantics without building a transaction system.

**And the payoff was unplanned:** *after* they'd built the DB and MultiOp, they discovered they
needed **epoch numbers** (§4.4). Because MultiOp existed, the fix was easy — **store the epoch
as a DB entry and add one more guard to every call checking it.** The authors treat this as
evidence that MultiOp is a genuinely powerful primitive. (If `MultiOp` feels familiar: it is
essentially **etcd's `Txn` (compare / success / failure)**. This is where that API comes from.)

---

## 5. Software engineering

### 5.1 Expressing the algorithm — they built a DSL and a compiler

Fault-tolerant algorithms are **hard to express correctly even as pseudo-code**, and it gets
worse when the algorithm is **tangled up with the rest of the system's code** — you can no
longer see it, reason about it, debug it, or change it.

**Their answer:** code the core algorithm as **two explicit state machines**, written in a
**purpose-built state machine specification language**, with a **compiler that emits C++**. The
language was **deliberately terse so a full algorithm fits on one screen.** Bonus: the compiler
**auto-generates state-transition logging and code-coverage instrumentation.**

**The vindication story** is the best anecdote in the paper. Late in development they had to
make a **fundamental change to group membership**. The original design had three states — waiting
to join, in the group, left the group — and **once a replica left, it could never rejoin** (the
idea being that a flapping replica shouldn't be able to disrupt the group repeatedly).

**Reality:** intermittent failure turned out to be **far more common than expected** — *normal,
healthy replicas fail intermittently from time to time*. So the model had to change to two
states, in or out, freely switching.

**It took one hour to change the state machine — and three days to update the tests.** Had the
algorithm been intermingled with the rest of the system, the change would have been much harder.

*(Two lessons in one: separate your protocol from your plumbing; and the tests are the expensive
part, which is a fact about all serious systems work.)*

### 5.2 Runtime consistency checking — assume your own code is wrong

Assertions everywhere, plus explicit verification code. The headline mechanism:

**The master periodically submits a checksum request *through the Paxos log*.** Each replica
computes a checksum of its local DB. Since **the log serializes all operations identically
everywhere, all checksums must match.** The master then sends its own checksum to all replicas
to compare.

**They found three real database inconsistencies:**
1. **Operator error.**
2. **Never explained.** Replaying the faulty replica's log produced a *consistent* database —
   so the log was fine and the divergence probably came from **random hardware memory
   corruption.**
3. **Suspected illegal memory access from errant code elsewhere in the (large) shared codebase.**
   Their response: **maintain a second database of checksums and double-check every database
   access against it.**

**In all three cases, manual intervention fixed it before Chubby's users saw anything.** Read
that again: *the invariant checker, not the algorithm, is what saved them.* The algorithm was
correct; the machine, the operators, and the neighboring code were not.

### 5.3 Testing — and why fault tolerance hides bugs

**They state flatly that proving a real system correct is unrealistic given the state of the art.**
So: **design for testability from day one.**

Two modes, run in sequence:

| Mode | Requirement |
|---|---|
| **Safety mode** | System must stay **consistent**. It is **allowed to make no progress** — operations may fail or report unavailability. |
| **Liveness mode** | System must be **consistent AND making progress.** All operations must complete. |

**The protocol:** start in safety mode, **inject random failures** for a while, then **stop
injecting and let the system recover**, then switch to liveness mode. **The liveness phase exists
to prove the system doesn't deadlock after a storm of failures.** That's a really clean test
design and worth copying.

**Repeatability was the key investment.** The fault-tolerant log test simulates a random number
of replicas through random **network outages, message delays, timeouts, crashes, recoveries, file
corruptions, and schedule interleavings.** The failure schedule is driven by a **seeded RNG**, and
the test runs **single-threaded** to eliminate multi-threading non-determinism — *possible only
because the log deliberately creates no threads of its own* (§2). **A failing run can be replayed
with the same seed under a debugger.**

**How they validated the test itself:** they **deliberately left in some protocol bugs found
during code and design review** and confirmed the test caught them. Then, to raise the bug yield,
they ran it **on hundreds of Google machines at once** — and found bugs that took **weeks of
simulated execution at extremely high failure rates** to surface.

**The scariest paragraph in the paper** — a challenge they had **no systematic solution** for:

> **Fault-tolerant systems mask problems. That means they can mask *bugs and misconfigurations*,
> silently eroding their own fault tolerance.**

Their example: they once started a 5-replica cell with **one replica's name misspelled**. The
system **appeared to work perfectly** — the four correct replicas made progress, and the fifth
sat in catch-up mode looking healthy. **But the cell tolerated one failure instead of two, and
nothing told them.** They added a check for that specific case, and then admit: **"We have no way
of knowing if there are other bugs/misconfigurations that are masked by fault-tolerance."**

*This is the deepest point in the whole paper. Redundancy is a silence generator. If you don't
actively measure your remaining fault tolerance, you don't know that you have any.*

### 5.4 Concurrency — the goal they set and then lost

They set out to **constrain concurrency to keep tests repeatable** (threads only at the edges,
where network calls come in). **As the product's needs grew, they couldn't hold the line:**
Chubby itself is multi-threaded at its core, so **the full system can't be tested repeatably.**
Then the **database** had to go multi-threaded (for snapshots, checksums, iterators concurrent
with serving), and eventually the **local log-handling code** too.

Their own verdict: **right goal, and they were unable to adhere to it.** Refreshingly honest.

---

## 6. Unexpected failures (100+ machine-years in production)

The war stories. Note how **few are algorithm bugs**.

- **Thread starvation → cascading master failover.** Their first release shipped with **10× the
  worker threads** of the old Chubby, hoping to handle more requests. Under load, the worker
  threads **starved other key threads**, causing timeouts → **rapid master failover** → **en-masse
  client migration to the new master** → new master overwhelmed → **more failover**. A classic
  metastable failure loop.

  **And the response made it worse.** Not knowing the cause, they decided to **roll back to the
  3DB version** in one datacenter. But **the rollback mechanism was undocumented** (they never
  expected to use it), **non-intuitive**, **the operator had never used it**, and **no developer
  was present.** An **old snapshot was used by mistake**. They **lost 15 hours of data** and had
  to rebuild several key datasets.

- **Upgrade script failure.** Months later, the upgrade failed because **stale files from the
  previous failed upgrade hadn't been deleted.** The cell ran on a **months-old snapshot for a few
  minutes**. **~30 minutes of data lost.** (Chubby's clients did all recover.)

- **Semantic mismatch discovered in production.** Chubby expected an operation to **fail** if the
  database lost master status mid-operation. Their system allowed the replica to be **re-installed
  as master during the operation and the operation to succeed.** The fix required **substantial
  rework of the Chubby/framework integration layer** — this is where **epoch numbers** came from.

- **Three DB divergences**, caught only by the checksum mechanism (§5.2).

- **OS bug.** On their Linux 2.4 kernel, **flushing a small file could hang for a long time if
  there were many buffered writes to other files** — which is exactly the situation right after
  writing a database snapshot. **Flushing a tiny Paxos log write could take seconds.**
  **Workaround: write all large files in small chunks, flushing after each**, sacrificing a little
  write performance to **protect the critical log writes from unexpected delay.**

**Their own tally:** three failures during upgrade/rollback, two from since-fixed bugs, **two from
operator error during rollout that caused data loss**, one from memory corruption. Their
conclusion on operators is worth quoting in spirit: system operators are **very competent but are
not on the development team** and don't know the system's intricacies — so **automate rollout with
carefully written, well-tested scripts and minimize operator involvement.** After doing so, their
next major release rolled out across **hundreds of machines with no incident, serving live
traffic.**

They also note: **a handful of failures in 100 machine-years would be excellent for most
production systems — but they consider it too high for Chubby.**

---

## 7. Measurements

They benchmarked Paxos-Chubby against 3DB-Chubby on the same 5 servers.

- Paxos-Chubby **beat 3DB on every test** — roughly **1.2×** at a single worker up to **~3.6×** at
  20 workers on ops/sec, and **2×–4.4×** on MB/s throughput.
- **The multi-worker gains come from batching** values into a single Paxos instance.
- **The tests were deliberately write-intensive**, even though **reads dominate in practice** —
  because reads are served locally by the lease-holding master and **never touch Paxos at all.**
  (Which is a quiet reminder of how much of consensus performance is really about *avoiding*
  consensus.)
- Snapshot tests show a **temporary performance dip** while a snapshot is written.
- They close with: **the system isn't optimized for performance**, there's plenty of headroom,
  and given the win over 3DB it isn't a priority.

---

## 8. Why this paper is the bridge to Raft

Read Raft's introduction next to this paper and the lineage is unmistakable. Raft's stated
motivation — *Paxos is hard to understand and a bad foundation for real systems; Multi-Paxos is
never pinned down; every implementation invents its own unproven variant* — **is a restatement of
this paper's complaints.**

Map the gaps onto Raft's answers:

| Gap identified here | Raft's response |
|---|---|
| Multi-Paxos underspecified; everyone invents a variant | Raft **specifies the whole thing** (Figure 2 is a complete spec) |
| Master churn from sequence-number games | **Terms + randomized election timeouts** |
| Epoch numbers invented ad hoc for fencing | **Terms are fencing tokens by construction** |
| Group membership has no correct published algorithm | **§6: joint consensus**, specified and argued |
| Log holes need no-op filling | **Holes are impossible** — the AppendEntries consistency check |
| Snapshot/log consistency (snapshot handles) | **§7: snapshots + `InstallSnapshot`**, specified |
| Read leases invented to avoid Paxos-per-read | **ReadIndex / lease reads**, discussed in §8 |
| No tools for expressing the algorithm | Raft is **designed to be implementable from the paper** |

**The honest summary:** *Paxos Made Live* is the bill of indictment; **Raft is the response.**
And notice that KRaft, etcd, and every modern system still carry these scars — **fencing tokens,
lease-based local reads, snapshot handles, `MultiOp`-shaped transactions (etcd's `Txn`), and
checksummed self-verification** are all here, invented at Google in 2006 because the papers didn't
tell anyone they'd be needed.

---

## 9. Recall list

- **Chubby = 5 replicas, one master, replicated DB.** Built on a fault-tolerant log built on
  Multi-Paxos. Layers deliberately separated so the log could be reused.
- **One page of pseudo-code → several thousand lines of C++.** The gap *is* the paper.
- **Disk corruption breaks Paxos's stable-storage assumption.** Fix: checksums + a GFS marker to
  distinguish "corrupted" from "brand new"; rebuild as a **non-voting member** until one full
  Paxos instance has passed.
- **Master leases** make local reads safe — and quietly introduce a **clock-drift assumption**
  (master's lease timeout is shorter than the replicas').
- **Master churn** is caused by the Multi-Paxos optimization, not by Paxos. Fix: periodically
  **boost** the sequence number.
- **Epoch numbers = fencing tokens**, stored in the DB, guarding every operation.
- **Group membership had no correct published algorithm.** They filled the gap themselves.
- **Snapshots are the application's job**, and the snapshot/log consistency machinery
  (**snapshot handles**, pause-less snapshotting via a shadow structure, catch-up races) is a
  large chunk of the complexity.
- **`MultiOp` = guard / t_op / f_op, atomic.** Transactions without a transaction system. It's
  where **etcd's `Txn`** comes from.
- **They built a state-machine DSL + compiler** so the protocol could be read on one screen —
  and it let them rewrite group membership **in an hour** (and the tests in three days).
- **Runtime checksum comparison across replicas** caught three real divergences that the
  algorithm could never have prevented (operator error, memory corruption, errant code).
- **Test in safety mode, then liveness mode.** Seeded RNG + single-threaded execution for
  **repeatability**. Validate the test by leaving known bugs in.
- **Fault tolerance masks bugs and misconfiguration, silently eroding fault tolerance.** The
  misspelled-replica story. *They had no general solution — and neither does anyone else.*