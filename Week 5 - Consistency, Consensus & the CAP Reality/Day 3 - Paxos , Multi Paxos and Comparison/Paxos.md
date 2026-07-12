# Paxos


---

## 1. Basic (single-decree) Paxos

**Goal:** a set of processes agree on **one** value, despite crashes and an unreliable network.

**Roles** (one process usually plays all three):

| Role | Job |
|---|---|
| **Proposer** | Proposes values. Stateless — can crash and restart freely. |
| **Acceptor** | Votes. **The durable memory of the system.** |
| **Learner** | Finds out what was chosen. |

**Proposal = `(n, v)`** where `n` is a globally unique, monotonically increasing number.
Uniqueness trick: proposer `i` of `N` picks numbers where `n mod N == i`.

### The protocol

```
PHASE 1 — PREPARE / PROMISE
  Proposer → majority:  Prepare(n)

  Acceptor: if n > (highest prepare I've answered):
              • PROMISE never to accept anything numbered < n
              • report back the highest-numbered proposal I've ALREADY accepted (if any)
            else: ignore / reject

PHASE 2 — ACCEPT / ACCEPTED
  Proposer: on hearing from a MAJORITY:
              • if ANY of them reported an accepted value
                    → v = the value from the HIGHEST-numbered one reported   ← NOT YOUR VALUE
              • else → v = whatever you want
            send Accept(n, v)

  Acceptor: accept (n,v) IFF I haven't promised to a number > n

  If a majority accept → v is CHOSEN.   (Nobody necessarily knows yet.)
```

### The two things that make it work

1. **Majorities intersect.** If `v` was chosen, a majority accepted it. Your Phase-1 quorum is a
   majority. **They overlap** ⇒ someone will tell you about `v`.
2. **Constrain the proposer, not the acceptor.** This is the heart of it (Lamport's P2b):

> **A proposer is an archaeologist, not a legislator.** It must dig up whatever might already
> have been accepted and re-propose *that*, abandoning its own value.

The promise is what makes step 1 durable — without it, an acceptor could report "nothing
accepted" and then immediately accept an older proposal behind your back.

### Acceptor persistent state (fsync before replying)

- highest **prepare** number responded to
- highest **proposal accepted** (number + value)

**An acceptor that forgets a promise can vote twice for conflicting values and destroy
agreement.** (Disk corruption breaking this assumption is exactly the first problem *Paxos Made
Live* had to solve.)

### Liveness: the dueling-proposers livelock

```
A: Prepare(1) ✓ →  B: Prepare(2) ✓  (A's Accept(1) now rejected)
A: Prepare(3) ✓ →  B: Prepare(4) ✓  (B's Accept(2) now rejected)
... forever
```

**Safety holds perfectly. Nothing is ever chosen.**
**Fix: elect one distinguished proposer (a leader).** Which relocates the problem rather than
solving it — leader election in an asynchronous system is itself impossible (**FLP**), so you fall
back on timeouts/randomness.

> **Paxos guarantees safety unconditionally. Liveness only under partial synchrony.**
> (Same bargain Raft makes, just stated out loud.)

---

## 2. Multi-Paxos

You never want to agree on *one* value. You want a **log** — a sequence of commands for a
replicated state machine. So: **run a separate Paxos instance per log slot.**

Naive cost: **2 phases (4 message delays) per command.** Wasteful.

### The optimization

> **Elect a stable leader. Run Phase 1 ONCE, with one proposal number `n`, covering ALL future
> instances. From then on, every command costs only Phase 2 — one round trip.**

```
Steady state:   Client → Leader → Accept(n, slot_i, cmd) → Acceptors → ack → commit
                                      ↑ 2 message delays
Phase 1 is now the LEADER-ELECTION cost, paid once per leadership change.
```

**Consequences:**

- **Batching:** bundle commands from many threads into one instance.
- **Pipelining:** propose slot `i+1` before `i` is decided (a window of α), executing in order.
- **Log holes are legal.** Instances are independent, so a new leader may know slots 1–10 and
  13–15 but not 11–12. **It must fill gaps with `no-op` commands**, or the state machine stalls
  (it executes strictly in order).
- **Reconfiguration:** put the acceptor set in the state machine; the config for slot `i+α` is
  whatever the state says after slot `i`.

---

## 3. Paxos vs Raft

| | **Paxos** | **Raft** |
|---|---|---|
| Round ID | Proposal number | **Term** |
| Phase 1 | `Prepare` / `Promise` | `RequestVote` / vote |
| Phase 2 | `Accept` / `Accepted` | `AppendEntries` / ack |
| Quorum | Majority | Majority |
| Leader | **Optional.** A performance optimization. Any proposer may act any time. | **Structural.** Entries flow *only* leader → follower. |
| Leader's log | May be **incomplete** — repaired during Phase 1 | **Guaranteed complete** by the election restriction |
| Log holes | **Allowed** (filled with no-ops) | **Impossible** by construction |
| Safety mechanism | **P2c** — must adopt the highest-numbered accepted value found | **Election restriction** — can't win unless your log is up-to-date |
| Membership change | Config in the state machine, α-slot lag | **Joint consensus** (or single-server changes) |
| Specification | Single-decree only; **Multi-Paxos never pinned down** | **Fully specified**, implementable from the paper |

### The insight that unifies them

**P2c and Raft's election restriction are the same idea, applied at different times.**

- **Paxos** lets *anyone* become leader, then **repairs the leader's ignorance during Phase 1** —
  it discovers previously-accepted values and adopts them.
- **Raft** simply **refuses to elect anyone who would need repairing** — the up-to-date log check
  inside `RequestVote` filters incomplete candidates out *before* they take office.

Both rest on the identical fact: **two majorities intersect, so a chosen value cannot hide.**

**Raft front-loads the constraint. That's the whole trick.** Because the leader is *never* wrong,
followers can be blindly overwritten; because followers are blindly overwritten, logs can't have
holes; because logs can't have holes, the whole thing fits in your head.

*(And per **Flexible Paxos**: even the majority requirement is stronger than necessary — you only
need `|Q1| + |Q2| > N`. Phase-2 quorums never needed to intersect each other.)*

---

## 4. DRILL — "Why is Raft preferred over Paxos in new systems?" (60 seconds)

### The script (~150 words, ~55s at interview pace)

> **"Because Paxos is a proof, and Raft is a specification.**
>
> Lamport's paper describes **single-decree** Paxos — agreeing on *one* value. But nobody wants
> one value; everyone wants a replicated **log**. Multi-Paxos, the version you'd actually build,
> is **sketched but never fully specified**. So every implementation invents its own undocumented
> variant, and each one ends up resting on **a protocol that was never proven correct**. Google's
> *Paxos Made Live* says exactly this — a page of pseudo-code became thousands of lines of C++,
> and the gaps they had to fill themselves — **group membership, snapshots, leases, fencing** —
> had **no correct published algorithm.**
>
> Raft was designed with **understandability as the explicit goal.** It decomposes into three
> clean pieces — **leader election, log replication, safety** — and it deliberately **reduces the
> state space**: a **strong leader** so entries flow one direction only, **no holes in the log**,
> and **randomized timeouts** instead of a clever protocol.
>
> The cost is some flexibility. The benefit is that **you can implement it from the paper and
> reason about it** — which is why etcd, Consul, TiKV, CockroachDB, and Kafka's KRaft all chose it.
>
> **Correctness you can't verify isn't correctness.**"

### The 10-second version

> *"Paxos is a correct algorithm for the wrong problem — one value, not a log. Multi-Paxos, the
> thing you'd actually build, was never specified, so everyone ships an unproven variant. Raft
> specifies the whole system and is designed to be understood, so implementations are actually
> correct."*

### Follow-ups they'll throw at you

**"Isn't Raft just Multi-Paxos with a strong leader?"**
> Essentially yes — and that's not a criticism, it's the *point*. The contribution isn't a new
> impossibility result; it's making the leader **structural instead of optional**, forbidding log
> holes, and **specifying the parts Paxos left out** — membership changes, snapshots, client
> semantics. The delta is small and it's exactly the delta that makes implementations correct.

**"So is Paxos worse?"**
> No — it's **more general**, and generality is what costs you. Paxos permits any node to propose
> at any time, tolerates holes, and decides slots independently, which allows more parallelism
> (see EPaxos). Raft **gives that flexibility up on purpose.** Where you genuinely need
> leaderless/geo-optimized commits, Paxos variants still win.

**"Is Raft slower?"**
> In the steady state, no — both are **one round trip** to a majority. Multi-Paxos amortizes Phase
> 1 across instances exactly like Raft amortizes an election across a term. Where Raft *can* lag is
> that a strong leader is a bottleneck and can't accept out-of-order commits, which leaderless
> variants exploit.

**"Where does Raft still bite you?"**
> **Membership changes** (joint consensus is subtle), **leader flapping** if `electionTimeout`
> isn't ≫ `broadcastTime` — a real problem over WANs or with slow fsync — and **the
> previous-term commitment rule**, which is the thing most implementations get wrong.

---

## 5. Recall

- **Phase 1 = "promise to ignore anything older, and tell me what you've already accepted."**
- **Phase 2 = "accept `(n,v)` — where `v` is *theirs* if they had one, *mine* if they didn't."**
- **Acceptors persist two things.** Proposers persist nothing.
- **Dueling proposers livelock forever.** Safety intact, liveness dead ⇒ elect a leader ⇒ FLP.
- **Multi-Paxos:** Phase 1 once for all slots; steady state is one round trip.
- **Fill log gaps with no-ops** — the state machine executes in order and will otherwise stall.
- **Raft = Paxos with the constraint moved to election time.** Never elect a leader who'd need
  repairing, and you never need to repair a leader.
- **The elevator answer:** *Paxos is a proof; Raft is a specification.*