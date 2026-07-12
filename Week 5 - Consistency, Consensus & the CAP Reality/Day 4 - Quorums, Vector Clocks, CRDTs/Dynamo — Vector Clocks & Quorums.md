# Dynamo — Vector Clocks & Quorums

> DeCandia et al., *"Dynamo: Amazon's Highly Available Key-value Store"*, SOSP 2007.
> Focus: **§4.4 Data Versioning (vector clocks)**, **§4.5 Execution of get()/put() (R/W/N quorums)**,
> **§4.6 Handling Failures (sloppy quorums + hinted handoff)**.
> My own notes.

---

## 0. The design pressure that explains everything

Amazon's SLA is stated at the **99.9th percentile**, not the mean. And the business requirement is
brutal:

> **The "Add to Cart" operation must never be rejected or lost.**

A cart write that fails is **lost revenue**. A cart that shows a stale state is merely *annoying*.
So Dynamo makes the trade explicitly:

> **"Always writeable."** Push conflict resolution to the **read** path (and to the
> **application**), so the **write** path never has to say no.

This inverts the usual database posture, where reads are cheap and writes may be rejected. Every
design decision below falls out of it.

---

## 1. §4.4 — Data Versioning and Vector Clocks

### 1.1 The problem

Dynamo is eventually consistent: updates propagate **asynchronously**. A `put()` may **return
before the update has reached all replicas**, so a subsequent `get()` may return stale data.

Under failures or partitions, an update may be applied to an **older** version of the object. Now
you have **divergent version histories**, and something has to reconcile them.

**The shopping cart example is the whole motivation:** if the latest cart is unreachable, the "add
to cart" is applied to an older cart. That older change **must not be lost**. Later, the branches
are merged.

**The known cost, and the paper is honest about it:** merging by union means **deleted items can
resurface.** You add a book, remove it, a partition heals, and the book is back in your cart.
Amazon decided a resurrected item is an acceptable price for never losing an add.

### 1.2 Immutable versions

**Dynamo treats every modification as a new, immutable version of the data.** Multiple versions can
exist in the system at once.

- **Most of the time**, new versions **subsume** their predecessors, and the system can figure out
  the authoritative one on its own. This is **syntactic reconciliation**.
- **When failures + concurrent updates collide**, you get **version branching** — conflicting
  versions that the system *cannot* order. Then the **client must reconcile** them. This is
  **semantic reconciliation** (e.g., *merge the two carts*).

### 1.3 Vector clocks

> **A vector clock is a list of `(node, counter)` pairs. One vector clock per version of every
> object.**

**The comparison rule:**

> **If every counter in clock A is `≤` the corresponding counter in clock B (and B has all of A's
> nodes), then A is an ancestor of B and can be discarded.**
> **Otherwise, the two versions are *concurrent* — a genuine conflict requiring reconciliation.**

That's it. Vector clocks give you a **partial order** (causality), not a total order. They tell
you *"did this happen before that, or were they concurrent?"* — and being able to say **"these are
concurrent"** honestly, instead of silently picking one, is the entire point.

### 1.4 The `context` — how clients participate

**A client must tell Dynamo which version it is updating.** It does this by passing a **context**
obtained from a prior read. The context is **opaque to the client** and **contains the vector
clock**.

```
ctx, values = get(key)          # may return MULTIPLE values (siblings) + ONE context
merged = reconcile(values)      # application logic — merge the carts
put(key, ctx, merged)           # writing back with ctx COLLAPSES the branches
```

**If a `get()` finds multiple causally-unrelated versions, it returns them all** (with a single
summarizing context). The client's write-back is what prunes the tree.

### 1.5 The canonical trace (the paper's Figure 3 — know this)

```
Sx writes                    D1  ([Sx,1])
Sx writes again              D2  ([Sx,2])                  ← D1 is an ancestor, discarded
                                     │
                    ┌────────────────┴────────────────┐
Sy handles a write        Sz handles a write   ← NETWORK PARTITION: both saw only D2
       D3 ([Sx,2],[Sy,1])      D4 ([Sx,2],[Sz,1])
                    └────────────────┬────────────────┘
                                     │
             D3 and D4 are CONCURRENT — neither dominates.
             (D3 has Sy:1 that D4 lacks; D4 has Sz:1 that D3 lacks.)
                                     │
      get() returns BOTH → client reconciles → Sx coordinates the write:
                       D5 ([Sx,3],[Sy,1],[Sz,1])   ← subsumes everything
```

**Read the D3/D4 comparison until it's automatic.** Neither clock dominates the other; *that* is
what "concurrent" means mechanically.

### 1.6 The wart: vector clocks grow — and the truncation hack

**The problem:** the clock gains an entry for every node that has ever coordinated a write to the
object. In the happy path only the top-N nodes coordinate, so the clock stays small. **But during
failures and partitions, nodes outside the top N end up coordinating** — and the clock grows.

**Dynamo's fix — and it's a hack, openly:**

> **Store a timestamp with each `(node, counter)` pair recording when that node last updated the
> item. When the clock exceeds a threshold (they used 10), drop the oldest pair.**

**And the paper's admission is famous:**

> Truncation **can break the descendant relationship** — you may no longer be able to derive
> correctly that one version is an ancestor of another, causing **reconciliation inefficiency**.
> *"This problem has not surfaced in production and therefore has not been thoroughly
> investigated."*

**That is a correctness bug they shipped and chose not to chase.** Worth remembering: it's the
kind of pragmatism a production paper is willing to admit and a theory paper never would.

*(Later work fixed this properly: Riak's **dotted version vectors** eliminate the sibling-explosion
and false-concurrency problems that plague naive vector clocks. Also note: Dynamo's "vector clocks"
are, pedantically, **version vectors** — clocks per *object replica*, not per *process event*.)*

---

## 2. §4.5 — Execution of `get()` and `put()`: the R / W / N quorum

### 2.1 Routing

Any node can receive a request for any key. Two strategies:

1. **Through a generic load balancer.** The node picked may **not** be in the key's preference
   list, so it **forwards** the request. Simple; costs an extra hop.
2. **Partition-aware client library.** The client routes **directly to the coordinator** — a
   **zero-hop** DHT, which is a big latency win at the 99.9th percentile.

**Coordinator** = typically the **first node among the top N** in the key's preference list.

### 2.2 The three knobs

| Knob | Meaning |
|---|---|
| **N** | Number of replicas of each object. |
| **R** | Minimum nodes that must participate in a **successful read**. |
| **W** | Minimum nodes that must participate in a **successful write**. |

> **`R + W > N` gives a quorum-like system** (read and write sets intersect).

**Crucial latency point, and it's the reason for the whole design:**

> **The latency of a `get()`/`put()` is dictated by the SLOWEST of the R (or W) replicas.**
> That's why **R and W are usually set to less than N** — you don't wait for the stragglers.

**Common configuration: `(N, R, W) = (3, 2, 2)`.**

### 2.3 The write path

```
put(key, ctx, value):
  coordinator:
    1. generates the vector clock for the new version
    2. writes it LOCALLY
    3. sends (version + new vector clock) to the N highest-ranked REACHABLE nodes
    4. if at least (W - 1) of them respond → the write is SUCCESSFUL
```

Note the `W-1`: the coordinator's own local write counts as one.

### 2.4 The read path

```
get(key):
  coordinator:
    1. requests ALL existing versions of that key from the N highest-ranked REACHABLE nodes
    2. waits for (R - 1) responses
    3. returns ALL causally unrelated versions  ← siblings surface here
    4. divergent versions are reconciled, and the reconciled version
       (superseding the current ones) is WRITTEN BACK        ← "read repair"
```

**Read repair** is the quiet workhorse: every read is an opportunity to heal divergence.

### 2.5 The tuning space

| Config | Effect | Use |
|---|---|---|
| `(3, 2, 2)` | Balanced. `R+W>N`. | The default. |
| `(3, 1, 3)` | Very fast reads; expensive, less-available writes | Read-heavy caches |
| `(3, 3, 1)` | **Always-writeable** (W=1); reads must consult everyone | "Never reject a write" services |
| `W = 1` | A write succeeds if **one** node durably stores it | Highest availability; the paper notes services *needing* the highest availability do this |

The paper is clear that **most Amazon services set W higher than 1** to meet durability
requirements, despite the availability appeal.

---

## 3. §4.6 — Sloppy Quorums and Hinted Handoff

### 3.1 Why a *strict* quorum won't do

> **If Dynamo used a traditional quorum, it would be unavailable during failures and partitions,
> with reduced durability — even when only a few nodes are down.**

Amazon can't accept that. So:

### 3.2 The sloppy quorum

> **All reads and writes are performed on the first N *healthy* nodes from the preference list —
> which are not necessarily the first N nodes encountered walking clockwise around the ring.**

Read that carefully. **The quorum is still "N nodes," but they may be the *wrong* N nodes.** This
is the definition of a sloppy quorum, and it is the thing that most people gloss over.

### 3.3 Hinted handoff

```
N = 3.  Preference list for key k: [A, B, C, ...]
        Node A is temporarily down during a write.

  → The write meant for A is sent to node D instead.
  → D stores it with a HINT in the metadata: "this really belongs to A."
  → D keeps hinted replicas in a SEPARATE local database, scanned periodically.
  → When D detects that A has recovered, it delivers the replica to A.
  → Once the transfer succeeds, D may delete its local copy —
    the total replica count in the system never decreased.
```

**The result:** reads and writes **do not fail** because of **temporary** node or network failures.

**Preference list design details that matter:**
- It contains **more than N nodes** precisely to allow this substitution.
- It **skips virtual nodes that map to the same physical node** (otherwise your "3 replicas" could
  be 3 vnodes on one box).
- It is built to **span multiple data centers**, so a **whole-datacenter failure doesn't lose the
  object**.

*(For **permanent** failures, hinted handoff isn't enough — §4.7 adds **anti-entropy with Merkle
trees**, so replicas can compare ranges cheaply and sync only what differs.)*

---

## 4. The interview traps (this is the payoff section)

### Trap 1: **`R + W > N` does NOT give you linearizability.**

This is the single most common misconception in the field, and Dynamo is the canonical
counterexample. Even with a *strict* quorum:

- **Writes are not atomic across replicas.** A write that fails partway (reached 1 of 3) is **not
  rolled back**. A subsequent read may or may not see it — and **two successive reads can
  disagree**, meaning **the value can go backwards.** Monotonic reads are violated.
- **There is no consensus in the write path.** Concurrent writes aren't ordered; they become
  **siblings** (Dynamo) or get resolved by **last-writer-wins timestamps** (Cassandra) — which
  **silently drops data**.
- **Read repair is not atomic** either.

**`R + W > N` buys you "a read set overlaps a write set." It does not buy you a single timeline.**

### Trap 2: **A sloppy quorum breaks the intersection guarantee entirely.**

This is the sharper point, and it's the one that separates people who've read the paper from
people who've read a blog post about it.

> **With hinted handoff, the W nodes that accepted your write may share *no members at all* with
> the R nodes that serve the next read.**

The write went to `{B, C, D}` (because A was down); the read hits `{A, B, C}` — or the write went to
`{D, E, F}` entirely. **`R + W > N` is arithmetic about *set sizes*, and it only implies overlap if
the sets are drawn from the same N nodes.** Sloppy quorums abandon that premise.

> **So: a sloppy quorum is a DURABILITY and AVAILABILITY mechanism, not a CONSISTENCY mechanism.**
> It guarantees your write is stored *somewhere* durable. It guarantees **nothing** about the next
> read seeing it.

### Trap 3: **Vector clocks detect conflicts; they don't resolve them.**

The resolution is **the application's job** (merge the carts), or a **policy** (LWW), or a **data
type** (CRDT). Dynamo's real contribution here is *refusing to silently pick a winner* — it hands
you the siblings and makes you decide. Cassandra later chose LWW instead, which is simpler and
loses data.

### Trap 4: **"Add to cart is never lost" ⇒ "deleted items can resurface."**

If you merge by union, deletes lose to adds. This is *why* CRDTs need **tombstones** (an OR-Set,
not a naive set). Being able to say this out loud demonstrates you understand what eventual
consistency actually costs.

---

## 5. Recall

- **Design driver:** never reject a write (`Add to Cart` = revenue). Push conflict resolution to
  reads and to the app.
- **Vector clock = list of `(node, counter)`.** A dominates B iff every counter in B ≤ A's.
  Otherwise **concurrent** → siblings → **semantic reconciliation** by the client.
- **`context`** carries the vector clock from `get()` back into `put()`. Writing back **collapses
  the branches.**
- **Clock truncation at 10 entries** can break ancestry detection. Amazon shipped it and said the
  problem *"has not surfaced in production."*
- **`R + W > N`** = quorum-*like*. **Latency = the slowest of R (or W).** Hence R, W < N.
  **Default `(3,2,2)`.**
- **Write:** coordinator writes locally, forwards to N reachable nodes, needs `W-1` acks.
  **Read:** gathers versions from N reachable nodes, waits for `R-1`, returns **all** siblings,
  then **read-repairs**.
- **Sloppy quorum:** the first N **healthy** nodes — **not** the first N on the ring.
- **Hinted handoff:** the substitute node tags the replica with the intended owner and hands it
  back on recovery. Cures **temporary** failures. **Merkle trees** (§4.7) cure **permanent** ones.
- **The two things to be able to say cold:**
  1. **`R + W > N` is not linearizability** (no atomic writes, no consensus, reads can go
     backwards).
  2. **A sloppy quorum doesn't even guarantee the read and write sets intersect** — it's about
     durability, not consistency.