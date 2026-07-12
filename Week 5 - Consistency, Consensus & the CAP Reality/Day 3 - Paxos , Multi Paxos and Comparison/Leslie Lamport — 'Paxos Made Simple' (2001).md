# Paxos Made Simple — Lamport (2001)

> Notes on the single-decree consensus algorithm and its use for state machine replication.
> Written in my own words. The paper is famously ~13 pages with no formal proofs — Lamport's
> rewrite of "The Part-Time Parliament" after nobody understood the Greek-parliament allegory.
> The title is a small joke. It is *simpler*. It is not *simple*.

---

## 0. The one-sentence version

**Get a majority to promise they'll ignore older proposals, ask them what they've already
accepted, and if anyone has accepted anything, you must re-propose *their* value instead of
your own.**

Everything below is the derivation of why that works.

---

## PART I — The Consensus Algorithm

## 1. The problem

A collection of processes can each **propose** values. The algorithm must ensure that **a
single one of the proposed values is chosen**.

### Safety requirements

1. **Only a value that has been proposed may be chosen.** (No inventing values.)
2. **Only a single value is chosen.** (Agreement.)
3. **A process never learns that a value has been chosen unless it actually has been.**
   (No false positives.)

Note what's *not* here: there's no requirement that a value is chosen *quickly*, or at all.
Liveness is handled separately — and, as we'll see, can't be guaranteed at all.

### The desired liveness (goal, not a guarantee)

Eventually some proposed value **is** chosen, and once chosen, a process can eventually
**learn** it.

### Three roles

| Role | Job |
|---|---|
| **Proposer** | Suggests values. |
| **Acceptor** | Votes. The durable memory of the system. |
| **Learner** | Finds out what was chosen. |

**Crucially, one process can play all three roles.** In practice they usually do. Separating
them is a reasoning device, not a deployment topology.

### System model

- **Asynchronous**, non-Byzantine. Messages may be **lost, delayed, reordered, or duplicated**
  — but **not corrupted**, and nobody lies.
- Agents can **fail and restart**. So they must **remember their state on stable storage**
  (fsync). An acceptor that forgets what it promised will break safety.
- Arbitrary speeds. No clock assumptions.

---

## 2. Deriving the algorithm (this *is* the paper)

The beautiful thing about Paxos Made Simple is that the algorithm is never *stated* first —
it's **derived** by starting with the weakest possible constraint and repeatedly strengthening
it until the algorithm falls out. Follow this and Paxos stops being mysterious.

### Step 0: one acceptor?

Trivially works: the single acceptor takes the first proposal it gets. **But it's a single
point of failure.** If it dies, nothing is ever chosen.

So: **multiple acceptors**, and a value is **chosen when a majority accept it.**

**Why majorities?** Because **any two majorities intersect.** That single fact is the entire
foundation. (Same fact that powers Raft.)

### Step 1: P1

> **P1. An acceptor must accept the first proposal that it receives.**

Necessary: if only one proposal is ever issued, and every acceptor sits waiting for a better
one, nothing is ever chosen.

**But P1 creates a problem.** Suppose three proposers concurrently issue three different
values to three acceptors, each acceptor gets a different one first — **each accepts a
different value, no majority, deadlock.** Nothing is chosen, and P1 forbids anyone from
changing their mind.

**⇒ An acceptor must be able to accept more than one proposal.**

To keep them straight, give every proposal a **unique, ordered proposal number**. A proposal
is now a pair **`(n, v)`**. Different proposers must never reuse a number — e.g. each proposer
draws from a disjoint set (`serverID + k·numServers`).

Now multiple proposals can be **accepted**, but we still need only one value **chosen**.
So we require:

### Step 2: P2

> **P2. If a proposal with value `v` is chosen, then every higher-numbered proposal that is
> chosen has value `v`.**

Since a proposal is only chosen if it's accepted, it suffices to guarantee something stronger
about **acceptance**:

### Step 3: P2a

> **P2a. If a proposal with value `v` is chosen, then every higher-numbered proposal
> *accepted by any acceptor* has value `v`.**

**But P2a fights P1.** Picture it: value `v` gets chosen (majority accepted it), but some
acceptor `c` was asleep/partitioned and **never saw any of it**. `c` wakes up, and a proposer
that also knew nothing issues a *higher-numbered* proposal with a *different* value `w`. By
**P1**, `c` must accept it (it's the first proposal `c` has seen) — which **violates P2a**.

The tension is real, and the resolution is the key insight of the whole paper:

**⇒ You can't fix this at the acceptor. You must constrain the *proposer*.**

### Step 4: P2b — the heart of Paxos

> **P2b. If a proposal with value `v` is chosen, then every higher-numbered proposal *issued
> by any proposer* has value `v`.**

An acceptor accepts what it's given, so if no proposer ever *issues* a conflicting value, no
acceptor can ever accept one. P2b ⇒ P2a ⇒ P2.

**This is the moment Paxos clicks:** *a proposer is not allowed to propose whatever it wants.
It must first discover whether anything might already have been chosen, and if so, it must
propose that same value — abandoning its own.* **A Paxos proposer is more of an archaeologist
than a legislator.**

### Step 5: P2c — how a proposer can actually satisfy P2b

How can a proposer, issuing `(n, v)`, *know* nothing conflicting was chosen? It can't know
the future, but it can **make the future impossible**. It asks a majority to make a promise.

> **P2c. For any `v` and `n`, if a proposal `(n, v)` is issued, then there is a set `S`
> consisting of a **majority of acceptors** such that either:
> **(a)** no acceptor in `S` has accepted any proposal numbered less than `n`, **or**
> **(b)** `v` is the value of the **highest-numbered proposal among all proposals numbered
> less than `n`** accepted by the acceptors in `S`.**

**Read that twice.** In plain English:

> *Before proposing, ask a majority what they've already accepted. If they've accepted nothing,
> you're free — propose anything. If they've accepted something, you must adopt the value from
> the **highest-numbered** one you heard about.*

**Why "highest-numbered"?** Because any value that was *chosen* was accepted by a majority.
Your `S` is a majority. **They intersect.** So if something was chosen, at least one acceptor
in `S` accepted it — and the chosen value will be carried forward. The highest-numbered rule
ensures you pick the most recent one, and an induction argument shows P2c ⇒ P2b.

**But there's a gap:** an acceptor might tell you "I've accepted nothing below `n`," and then
**immediately afterwards** accept some lower-numbered proposal from a slow proposer — making
your information stale and breaking everything.

**⇒ So the response must be a *promise about the future*, not just a report about the past.**
The acceptor must promise: **"I will not accept any proposal numbered less than `n` from now
on."** That promise is what makes the majority's answer *durable*.

And now the algorithm has fully written itself.

---

## 3. The algorithm

### Phase 1 — Prepare / Promise

**Proposer:** picks a new number `n`, sends **`Prepare(n)`** to a majority of acceptors.

**Acceptor**, on receiving `Prepare(n)`:
- If `n` is greater than any prepare it has already responded to, it responds with:
  1. **a promise never to accept any proposal numbered less than `n`**, and
  2. **the highest-numbered proposal it has accepted, if any** (both number and value).
- Otherwise it can ignore the request (or, as an optimization, reply with a rejection so the
  proposer learns to give up on `n` immediately).

### Phase 2 — Accept / Accepted

**Proposer:** if it receives responses from a **majority**:
- If any acceptor reported an accepted proposal → **`v` = the value of the highest-numbered
  one reported.** *(Not your value. Theirs.)*
- If none did → **`v` = whatever you want.** (Your own value.)
- Send **`Accept(n, v)`** to that majority.

**Acceptor**, on receiving `Accept(n, v)`:

> **P1a. An acceptor can accept a proposal numbered `n` iff it has not responded to a prepare
> request having a number greater than `n`.**

(P1a **subsumes P1** — it's a strictly better version of "accept the first thing you see.")

If a majority accept `(n, v)`, **`v` is chosen.** Note: nobody necessarily *knows* it yet.
That's the learners' problem.

### What acceptors must persist

Only two things (plus the value):

- **the highest-numbered prepare it has responded to**, and
- **the highest-numbered proposal it has accepted** (number + value).

These **must survive a crash** (stable storage, fsync before replying). An acceptor that
forgets a promise can vote twice for conflicting values and destroy agreement.

**Proposers keep no critical state.** A proposer may **abandon a proposal at any time** and
restart with a higher number. Nothing breaks. (Contrast with an acceptor, where amnesia is
fatal.) This asymmetry is why the algorithm tolerates chaos.

### Optimization noted in the paper

An acceptor that has already promised `n' > n` can just **ignore** an `Accept(n, ·)` — it can
ignore *any* message it likes without harming safety. Safety never depends on any message
arriving.

---

## 4. Learning the chosen value

A value is chosen when a majority accepts it — but **acceptance is a distributed fact that no
single node observes.** Learners must be told. Options:

| Approach | Trade-off |
|---|---|
| Every acceptor → every learner | Reliable, but **|acceptors| × |learners|** messages |
| Acceptors → **one distinguished learner** → everyone | Cheap, but the distinguished learner is a **single point of failure** |
| Acceptors → a **set** of distinguished learners → everyone | The practical middle ground |

Because messages get lost, **a value can be chosen and yet no learner finds out.** The
acceptors "know" collectively but nobody assembled the evidence. Remedy: a learner can ask a
proposer to **issue a fresh proposal** (using the algorithm above) — which, by P2c, will
re-discover and re-propose the already-chosen value. **You can always safely re-run Paxos; it
will just re-confirm the existing answer.** That idempotence is a lovely property.

---

## 5. Progress — and why Paxos can't guarantee it

**The dueling-proposers livelock:**

```
Proposer A: Prepare(1) → majority promises
Proposer B: Prepare(2) → majority promises  (now A's Accept(1,·) is rejected)
Proposer A: Prepare(3) → majority promises  (now B's Accept(2,·) is rejected)
Proposer B: Prepare(4) → ...
```

Each proposer's Phase 2 is invalidated by the other's Phase 1, forever. **Safety holds
perfectly — nothing wrong is ever chosen — but nothing is ever chosen at all.**

**The fix: elect a single distinguished proposer (a "leader"). Only the leader issues
proposals.** If the leader can reach a majority, it completes both phases and progress happens.

**And here's Lamport's honest admission:** this doesn't *solve* the problem, it *relocates* it.
Reliably electing a unique leader in an asynchronous system is itself impossible —
**FLP impossibility** (no deterministic algorithm can guarantee consensus in an asynchronous
system with even one crash). So leader election must rely on **timeouts or randomness**, which
means:

> **Paxos guarantees safety unconditionally. Liveness only under partial-synchrony
> assumptions.**

Every real consensus system lives with this. Raft's randomized election timeouts are the same
bargain, made explicit.

---

## PART II — Implementing a State Machine

## 6. Replicated state machines

A server is a **deterministic state machine** executing a **sequence** of client commands.
Replicate it: run the same state machine on every server, and feed all of them **the same
commands in the same order** ⇒ they stay identical.

**So: run a separate *instance* of the Paxos consensus algorithm for each slot in the
sequence.** Instance `i` chooses the `i`-th command. That's the whole idea.

## 7. Multi-Paxos — the essential optimization

The naive version (2 full phases per command = 4 message delays) is wasteful. The paper's
optimization is the thing everyone actually implements:

**A leader is elected (distinguished proposer + distinguished learner). Then:**

> **Phase 1 is executed ONCE, for ALL instances simultaneously, with a single proposal number
> `n`.**

The leader sends one `Prepare(n)` covering *every* future instance. Acceptors respond with
whatever they've accepted **in any instance**. The leader is now the established proposer for
all of them.

**From then on, each new command costs only Phase 2 — a single round trip.**

```
Client → Leader → Accept(n, i, cmd) → Acceptors → Accepted → Leader → Client
                        ↑
              2 message delays. This is the steady state.
```

**Phase 1 becomes the leader-election / recovery cost, paid once per leadership change.**
This is *exactly* Raft's structure: `RequestVote` ≈ Phase 1, `AppendEntries` ≈ Phase 2.

## 8. Gaps in the log, and no-ops

When a new leader takes over, it learns from the acceptors which commands have been chosen.
It might learn about instances **1–10 and 13–15**, but **not 11 and 12**.

- For **11 and 12**, it re-runs Phase 2 — constrained by P2c, so if anything was already
  accepted there, it must re-propose *that*.
- **But what if nothing was ever proposed for 11 and 12?** The state machine executes commands
  **in order**, so it is **stuck at 10 forever**, unable to execute 13–15 that it already knows
  about.

**Fix: fill the gaps with a `no-op` command.** The leader proposes a do-nothing command for
each empty slot, the state machine executes it harmlessly, and the log becomes contiguous.

**This is a crucial structural difference from Raft:**

> **Paxos logs can have holes. Raft logs cannot.**

Paxos allows any instance to be decided independently and out of order (which is *more*
parallel, and *much* harder to reason about). Raft forbids holes by construction — the
`AppendEntries` consistency check makes them impossible. **That single restriction is a
large chunk of why Raft is comprehensible and Paxos isn't.**

## 9. Pipelining (the α window)

The leader doesn't have to wait for command `i` to be chosen before proposing `i+1`. It can
have up to **α** commands in flight concurrently, choosing instances `i+1 … i+α` in parallel,
and only *executing* them in order once the prefix is complete. Great for throughput; a
significant source of implementation complexity.

## 10. Reconfiguration

**The set of acceptors is itself part of the state machine's state.** The leader can propose a
command that changes the configuration.

**The trick:** the configuration used to choose command `i + α` is the one determined by the
state **after executing command `i`**. The α-slot lag gives every replica time to learn about
the new configuration before it takes effect.

(This is Paxos's answer to what Raft handles via **joint consensus**.)

---

## 11. Paxos ↔ Raft (side-by-side)

| Concept | Paxos | Raft |
|---|---|---|
| Round identifier | **Proposal number** `n` | **Term** |
| Phase 1 | `Prepare` / `Promise` | `RequestVote` / vote |
| Phase 2 | `Accept` / `Accepted` | `AppendEntries` / ack |
| Quorum | Majority | Majority |
| Leader | Optional performance optimization; any proposer may act at any time | **Structural.** Entries flow only leader → follower |
| Log holes | **Allowed** (filled with no-ops) | **Forbidden** by the consistency check |
| Leader's log | May be **missing** entries — recovered via Phase 1 | Guaranteed **complete** by the election restriction |
| Safety mechanism | **P2c** — must re-propose the highest-numbered accepted value | **Election restriction** — can't win unless your log is up-to-date |
| Membership change | Configuration in the state machine, α-slot lag | Joint consensus / single-server changes |

**The deep observation:** P2c and Raft's election restriction are **the same idea, applied at
different times.**

- **Paxos** lets *anyone* become leader, then **repairs the leader's ignorance during Phase 1**
  (it discovers and adopts previously-accepted values).
- **Raft** refuses to elect anyone who'd need repairing — the **up-to-date check** in
  `RequestVote` filters out incomplete candidates *before* they take office.

Both rely on the identical fact: **two majorities intersect, so a chosen value cannot hide from
a new quorum.** Raft just front-loads the constraint, which means the leader is never wrong,
which means followers can be blindly overwritten, which means the algorithm fits in your head.

---

## 12. Recall list

- **Safety requirements:** only a proposed value is chosen; only one value is chosen; you never
  learn a value that wasn't chosen.
- **Roles:** proposer, acceptor, learner. Acceptors are the durable memory.
- **Majorities intersect.** This is the only real magic.
- **P2b is the core insight:** *constrain the proposer, not the acceptor.* A proposer must
  adopt the highest-numbered previously-accepted value it hears about.
- **Phase 1 = "promise to ignore anything older, and tell me what you've already accepted."**
- **Phase 2 = "accept `(n, v)`, where `v` is theirs if they had one, mine if they didn't."**
- **Acceptors persist two things:** highest prepare responded to, highest proposal accepted.
- **Proposers are stateless** and may abandon proposals freely. Re-running Paxos is safe and
  idempotent.
- **Dueling proposers livelock forever** — safety intact, liveness dead. **Elect one leader.**
- **FLP:** liveness requires timeouts/randomness. Safety never does.
- **Multi-Paxos:** run Phase 1 once for all instances; steady state is one round trip.
- **Fill log gaps with no-ops** — the state machine executes in order and will stall otherwise.
- **The title is a joke.** But the derivation (P1 → P2 → P2a → P2b → P2c) genuinely is the
  clearest path in. Learn the derivation, not the pseudocode.