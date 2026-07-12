# CRDTs — Convergent and Commutative Replicated Data Types

> Shapiro, Preguiça, Baquero, Zawirski (INRIA, 2011).
> The survey that gave the field its name and its first principled foundation.
> Focus: the introduction (the theory) and the **type catalog**.

---

## 1. The problem CRDTs solve

### 1.1 The two unsatisfying options

**Strong consistency** (serializability, consensus, total order on updates) means every update
touches a **coordination protocol**. That costs latency, doesn't scale out, and — per CAP — makes
you **unavailable under partition**. Fine for a bank ledger. Fatal for a shopping cart, a
collaborative editor, or a mobile app on a train.

**Optimistic replication / eventual consistency** lets you update your local replica **immediately**
and propagate later. Great for availability — but the resulting conflicts get resolved **after the
fact**, typically by:

- **consensus + rollback** (expensive, and reintroduces coordination), or
- **ad-hoc, application-specific merge logic** (complex, error-prone, and *always* subtly wrong).

**The whole field was stuck between "coordinate on everything" and "resolve conflicts by hand."**

### 1.2 The CRDT bet

> **Design the data type so that conflicts are *mathematically impossible*.**
> **Convergence follows from the type's algebraic properties — with NO synchronization,
> NO consensus, and NO rollback.**

If concurrent operations **commute**, order doesn't matter. If merge is a **least upper bound on a
lattice**, then any replicas that have seen the same updates are **provably identical**, regardless
of the order or number of times they saw them.

**No conflict resolution, because there are no conflicts.**

### 1.3 Strong Eventual Consistency (SEC)

The paper's central definition, and it's what a CRDT buys you:

| Property | Meaning |
|---|---|
| **Eventual delivery** | An update delivered at one correct replica is eventually delivered to all. |
| **Termination** | All method executions terminate. |
| **Convergence** (EC) | Replicas that have delivered the same updates *eventually* have equivalent state — but classic EC may need **consensus and rollback** to get there. |
| **Strong Convergence (SEC)** | **Replicas that have delivered the same set of updates have equivalent state — immediately, deterministically, with no rollback and no consensus.** |

**EC is a promise about the future. SEC is a promise about the present.** The difference is that
SEC is achieved by *construction*, not by *repair*.

---

## 2. The two flavors

### 2.1 State-based — CvRDT (Convergent Replicated Data Type)

Replicas ship their **full state**; a **`merge`** function combines two states.

**The requirement: the state must form a *join-semilattice*.** That is, a partial order `≤` with a
**least upper bound** (`⊔`), where merge = `⊔` and is:

| Property | Equation | What it buys you |
|---|---|---|
| **Commutative** | `x ⊔ y = y ⊔ x` | **Messages can arrive out of order** |
| **Associative** | `(x ⊔ y) ⊔ z = x ⊔ (y ⊔ z)` | **Batching / regrouping is free** |
| **Idempotent** | `x ⊔ x = x` | **Messages can be duplicated or re-delivered** |

Plus: **updates must be monotonically increasing (inflationary)** in the partial order — a state
never goes backwards.

**Why this is beautiful:** because merge is commutative, associative, *and* idempotent, the network
can **lose, duplicate, reorder, and re-deliver messages freely**, and you still converge. You need
only **eventual delivery** — gossip, anti-entropy, whatever. **The weakest possible channel.**

The cost: **you ship whole states**, which can be large.

### 2.2 Operation-based — CmRDT (Commutative Replicated Data Type)

Replicas ship **operations**. Split into two parts:

- **`prepare`** — runs at the source, **side-effect free**; computes what to broadcast.
- **`effect`** — runs **downstream at every replica**, applying the update.

**The requirement: concurrent operations must commute.** (Causally-ordered ones don't need to —
they'll be applied in order.)

**The cost: you need a stronger channel — reliable causal broadcast**, i.e. **exactly-once
delivery in causal order**. No duplicates (ops usually aren't idempotent), no causal reordering.

The benefit: **small messages** (just the ops).

### 2.3 The equivalence result

> **State-based and op-based CRDTs can emulate each other. They are equally expressive.**

So the choice is **purely an engineering trade-off** — big messages + a dumb network, or small
messages + a smart network. Nice, clean result.

---

## 3. THE TYPE CATALOG

### 3.1 Counters

#### **Op-based Counter**
`increment()` and `decrement()` — they commute trivially (addition is commutative). Done.
**But** requires exactly-once delivery — a duplicated `increment` corrupts the count. This is
precisely why the state-based version needs a different design:

#### **G-Counter** (Grow-only Counter, state-based)

**The problem:** you can't just keep an integer and merge by `max` — two replicas each incrementing
from 5 to 6 would merge to 6, losing an increment. And you can't merge by `sum` — that's not
idempotent (re-delivering doubles it).

**The fix — the foundational CRDT trick:**

> **Give each replica its own slot. Never touch anyone else's.**

```
state:   a vector  P[i]  — one counter per replica i
increment() at replica i:   P[i] += 1        ← only ever touches its OWN slot
value():                    sum(P)
merge(X, Y):                pointwise MAX    ← ⊔ = max, which IS commutative/assoc/idempotent
```

**Why `max` works:** each slot only grows, and only its owner writes it. `max` on each slot picks
the freshest known value for that replica. Duplicate merges are harmless (idempotent). This is a
join-semilattice.

**Limitation: increment only.** `max` can't express a decrement.

#### **PN-Counter** (Positive-Negative Counter)

> **Two G-Counters: `P` for increments, `N` for decrements.**
> **`value() = sum(P) − sum(N)`. `merge` = pointwise max on each.**

Elegant: rather than break monotonicity, you **track two monotonic things and subtract**. This
"split it into two grow-only halves" pattern recurs constantly in CRDT design.

**⚠️ The invariant limitation (important — see §5):** you **cannot** enforce **"counter ≥ 0"**
without coordination. Two replicas can each concurrently decrement a balance of 1 down to 0, and
the merge gives **−1**. **Convergence is guaranteed; the invariant is not.** Enforcing a
non-negative counter requires synchronization or a reservation/escrow scheme.

---

### 3.2 Registers (a single overwritable value)

A register has `assign(v)` and `value()`. Two concurrent assigns **fundamentally conflict** —
there's no commutative way to combine "set to A" and "set to B." So you must pick a *semantics*:

#### **LWW-Register** (Last-Writer-Wins)

Attach a **timestamp** to each assignment. On merge, **the higher timestamp wins**. Requires a
**total order** on timestamps (a global clock, or a Lamport clock with node-ID tiebreak).

- ✅ Simple, small, converges.
- ❌ **Silently discards one of the two concurrent writes.** Data loss by design.
- ❌ Correctness depends on the timestamp order being sensible — with wall clocks, **clock skew
  decides your data**.

*(This is exactly Cassandra's and Riak's LWW conflict resolution.)*

#### **MV-Register** (Multi-Value Register)

**The Dynamo approach.** Concurrent assigns **both survive**; `value()` returns a **set** of values
(siblings), tracked with **version vectors**. The application resolves.

- ✅ **Loses nothing.** It tells you the truth: "these were concurrent; you decide."
- ❌ Pushes the problem to the application; the "set" semantics are a bit odd (the paper notes an
  MV-Register does not behave like a sequential set).

**LWW vs MV is the fundamental register choice: lose data silently, or surface conflicts loudly.**

---

### 3.3 Sets — *the canonical difficulty*

**Why sets are hard: `add(e)` and `remove(e)` do not commute.**

```
add(e) then remove(e)   →   {}        }  different results
remove(e) then add(e)   →   {e}       }  ⇒ NOT commutative ⇒ not a CRDT
```

So every set CRDT is really **a choice of what concurrent `add` ∥ `remove` should mean.** The
catalog is a tour of those choices:

#### **G-Set** (Grow-only Set)
`add` only. `merge = union`. Union is commutative, associative, idempotent. **Trivially a CRDT.**
Useless if you ever need to remove anything — but it's the base case everything else is built from.

#### **2P-Set** (Two-Phase Set)
> **Two G-Sets: `A` (added) and `R` (removed = tombstones).**
> **`e ∈ set` iff `e ∈ A ∧ e ∉ R`. `merge` = pairwise union.**

Same "two grow-only halves" trick as PN-Counter.

- **Semantics: remove wins.**
- ❌ **Fatal flaw: once removed, an element can NEVER be re-added.** The tombstone is permanent.
- ❌ Tombstones accumulate forever.

#### **LWW-Element-Set**
Each element carries an **add-timestamp** and a **remove-timestamp**. `e ∈ set` iff its add-ts >
remove-ts (with a **bias** rule for ties — add-wins or remove-wins, you choose).
Allows re-adding. Inherits LWW's clock-dependence.
*(SoundCloud's Roshi is this.)*

#### **PN-Set**
A **counter per element**; `e ∈ set` iff count > 0. Converges, but the semantics are **bizarre**:
`add(e)` twice then `remove(e)` once leaves `e` **in** the set. Nobody expects that. A good
cautionary example: *converging is not the same as being correct.*

#### **OR-Set** (Observed-Remove Set) — ⭐ **the important one**

**This is the one that gets the semantics right, and the one real systems use.**

> **Every `add(e)` generates a fresh, globally unique tag.** The set stores `(element, tag)` pairs.
> **`remove(e)` removes only the `(e, tag)` pairs that were OBSERVED at the source** — i.e. the
> tags visible to *that replica at the moment remove was called*.

**Why this is exactly right:**

```
add(e) at A          →  (e, tag₁)
remove(e) at A       →  removes tag₁  (it observed tag₁)          →  e ∉ set   ✅ intuitive

CONCURRENTLY:
  Replica A: remove(e)   — observes and removes tag₁
  Replica B: add(e)      — creates a BRAND NEW tag₂
  merge                  → tag₁ is gone, but tag₂ was never observed by the remove
                         → e ∈ set                                  ✅ ADD WINS
```

> **OR-Set semantics: sequential `remove` after `add` works as expected; a *concurrent* `add`
> beats a `remove`.**

**Add-wins is the intuitive default** — it matches "an add is a user's fresh intention that the
remover never saw." (And it's the Dynamo cart lesson, made rigorous: never lose an add.)

- ✅ Re-adding works.
- ✅ Semantics match intuition.
- ❌ **Tombstones for tags** — you must remember which tags were removed. **Garbage collection is
  the open problem.** *(Later work — **δ-CRDTs**, **Riak's ORSWOT** ("Observed-Remove Set Without
  Tombstones") — attacks exactly this.)*

**Riak's set, Akka Distributed Data, Redis Enterprise's set, and Automerge all use OR-Set
semantics.** If you remember one type from this paper, remember this one.

#### **Set semantics cheat-sheet** — *what happens on concurrent `add(e)` ∥ `remove(e)`?*

| Type | Result | Re-add allowed? | Notes |
|---|---|---|---|
| **G-Set** | n/a | — | Add only. |
| **2P-Set** | **remove wins** | ❌ **never** | Tombstone is permanent. |
| **LWW-Element-Set** | **whoever has the later clock** | ✅ | Clock skew decides your data. |
| **PN-Set** | depends on counts | ✅ | Unintuitive (add×2 + remove×1 = present). |
| **OR-Set** | ✅ **add wins** | ✅ | **The right answer.** Unique tags per add. |

---

### 3.4 Graphs — *where composition bites you*

A graph = a set of vertices + a set of edges. So compose two set CRDTs and you're done?

**No.** There's a **global invariant**: **no dangling edges** (an edge must connect two existing
vertices). And CRDT composition **does not preserve invariants automatically.**

**The problematic interleaving:**

```
Replica A: addEdge(u, v)          }  concurrent
Replica B: removeVertex(u)        }
merge → an edge pointing at a vertex that no longer exists.   💥
```

**There is no "correct" answer — you must CHOOSE a semantics**, and the paper walks through the
options:

- **removeVertex wins** → the edge is removed too (the "2P2P-Graph" resolution).
- **addEdge wins** → the vertex is resurrected (or the removal is voided).

**The lesson generalizes far beyond graphs:**

> **Composing CRDTs gives you convergence for free. It does NOT give you invariants for free.**
> **Any cross-object invariant requires you to explicitly define the concurrent semantics.**

Also catalogued: **Monotonic DAG** (edges may only be added between existing vertices, preserving
reachability) and **Add-only Monotonic DAG**.

---

### 3.5 Sequences / Lists — *the hardest type* (collaborative text editing)

The target problem: **two people typing in the same document at the same time.**

**Why it's brutal:** positions are **relative**. If I insert at index 5 and you delete index 3
concurrently, my index 5 now means something different. **Index-based operations don't commute.**

**The universal solution:**

> **Give every element (character) a globally unique, immutable position identifier drawn from a
> DENSE total order — so you can always generate a new identifier strictly between any two
> existing ones.**

Then `insert` = "create an element with an ID between the IDs of its neighbors." IDs never change,
so operations commute. Ties (two people inserting at the same spot) are broken by appending a
**unique replica ID**.

Systems catalogued: **Treedoc** (the authors' own — identifiers are paths in a binary tree),
**WOOT**, **Logoot**, **RGA**.

**The practical problems:**
- **Identifier growth.** Repeatedly inserting between two adjacent IDs makes them longer and
  longer. Type a paragraph left-to-right and your IDs bloat.
- **Rebalancing the identifier space** to fix that **requires agreement among replicas** — i.e. it
  sneaks consensus back in through the side door. (Treedoc needed a "core" of nodes to agree.)
- **Deletion needs tombstones** (you can't remove an ID that others may still reference as a
  neighbor).

*(Modern descendants: **Yjs**, **Automerge**, **Y-CRDT** — these power live collaborative editors.
The core idea is unchanged from this paper.)*

---

## 4. Garbage collection — the Achilles heel

**Tombstones accumulate.** 2P-Set tombstones, OR-Set tags, sequence tombstones — all of them grow
without bound. A long-lived CRDT can end up **mostly metadata**.

**To safely collect a tombstone, you must know that every replica has seen the corresponding
removal** — i.e. the update is **stable**. Determining stability requires knowing about all
replicas (version vectors, and a membership view).

> **Which means garbage collection requires *some* form of agreement.**

**The saving grace:** GC is **off the critical path**. Reads and writes never wait for it, so it
can be slow, lazy, and occasionally-unavailable without hurting the system's availability. **CRDTs
don't eliminate coordination; they push it out of the request path.** That's the honest framing.

---

## 5. ⚠️ The limitation that matters most

> **CRDTs guarantee CONVERGENCE. They do NOT guarantee INVARIANT PRESERVATION.**

Say it again. **Convergence ≠ correctness.**

All replicas will agree on the same value — but that value may **violate your business rule**:

| You want | CRDT gives you |
|---|---|
| Bank balance **≥ 0** | Two concurrent withdrawals of the full balance → converges to **−100**. Everyone agrees the account is overdrawn. |
| **Unique** usernames | Two replicas concurrently register "mayur" → converges to... both. |
| Inventory **≥ 0** (no overselling) | Two concurrent purchases of the last item → **oversold**, consistently. |
| Seat booked **once** | Double-booked, and every replica agrees it's double-booked. |

**These require actual coordination — consensus, locks, or a reservation/escrow scheme.**
CRDTs are wonderful for **commutative, monotone, "more is fine"** state (counters, carts, presence,
sets of likes, collaborative text). They are **useless for uniqueness and non-negativity
constraints.**

**This is the exact boundary that determines whether you reach for a CRDT or for Raft.** The
oversell bug you fixed with an atomic conditional UPDATE is a **canonical non-CRDT problem** — no
amount of clever merge semantics saves you; you need a serialization point.

---

## 6. How this fits the rest of the map

- **CAP:** CRDTs are unapologetically **AP**. They sidestep the theorem by **weakening the
  consistency requirement to SEC** rather than by cheating it.
- **PACELC:** CRDTs are **PA/EL** — and they're the *honest* version of it. Instead of "eventual
  consistency, hope for the best," they give you **provable convergence with defined semantics**.
- **Vs. Dynamo:** Dynamo's vector clocks **detect** conflicts and hand you siblings to resolve.
  **CRDTs make the resolution part of the type**, so the app never sees a conflict. Riak's evolution
  from "siblings + your merge function" to "Riak Data Types" is exactly this progression.
- **Vs. consensus:** Raft/Paxos buy you a **total order** (and therefore invariants) at the cost of
  **coordination on every write**. CRDTs buy you **availability and low latency** at the cost of
  **giving up invariants**. Pick based on whether your problem has a hard constraint.

---

## 7. Recall

- **SEC:** replicas that delivered the same updates have **equal state immediately** — no
  consensus, no rollback.
- **CvRDT (state-based):** state is a **join-semilattice**; `merge` = **LUB**, which is
  **commutative, associative, idempotent** ⇒ tolerates **loss, duplication, reordering**. Needs
  only eventual delivery. Big messages.
- **CmRDT (op-based):** **concurrent ops commute**; needs **reliable causal broadcast**
  (exactly-once, causal order). Small messages.
- **They're equivalent in power.** Choose on engineering grounds.
- **G-Counter:** per-replica slots, merge by **pointwise max**. Own-slot-only writes is *the* core
  trick.
- **PN-Counter:** two G-Counters, subtract. "Split into two monotone halves" — the recurring pattern.
- **LWW-Register** loses a write silently; **MV-Register** surfaces siblings.
- **Sets are hard because add/remove don't commute.** The type you want is the **OR-Set**:
  unique tag per add; remove only kills **observed** tags ⇒ **concurrent add WINS**.
- **2P-Set can never re-add.** **PN-Set has nonsense semantics.** Know why both are wrong.
- **Graphs show that composition doesn't preserve invariants** — you must choose the
  concurrent semantics for dangling edges yourself.
- **Sequences** need **dense, unique, immutable position IDs** (Treedoc/Logoot/RGA → Yjs/Automerge).
  Identifier growth and rebalancing are the pain.
- **GC needs stability detection ⇒ some agreement** — but it's **off the critical path**.
- **THE headline:** **CRDTs give convergence, not invariants.** No CRDT can enforce "balance ≥ 0"
  or "usernames are unique." For those, you need consensus.