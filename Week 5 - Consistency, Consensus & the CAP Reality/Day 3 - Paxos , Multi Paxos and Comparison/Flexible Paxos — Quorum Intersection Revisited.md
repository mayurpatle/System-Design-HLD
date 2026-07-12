# Flexible Paxos — Quorum Intersection Revisited

> Howard, Malkhi, Spiegelman (2016). Brief notes.

---

## The one-line result

> **Paxos does not need majority quorums. It only needs Phase-1 quorums to intersect with
> Phase-2 quorums — *not* with each other.**

Formally, for `N` acceptors:

```
|Q1| + |Q2| > N
```

Majority quorums (`Q1 = Q2 = ⌈(N+1)/2⌉`) are just **one point on this line**, not a requirement.
Everyone assumed majorities were necessary for 25 years. They aren't.

---

## Why it works

Recall what each phase is actually for:

- **Phase 1 (Prepare/Promise)** — the new leader **discovers** any value that might already have
  been chosen, and must adopt the highest-numbered one it hears about (Lamport's P2c).
- **Phase 2 (Accept)** — the leader **commits** a value to a quorum.

The safety argument only ever requires that **a new leader's Phase-1 quorum sees anything a
previous leader committed in Phase 2.** That is a **Q1 ∩ Q2 ≠ ∅** requirement.

**Q2 ∩ Q2 was never needed.** Two Phase-2 quorums for *different* proposal numbers don't need to
overlap, because the value in the later one was already **constrained by Phase 1**. Classic Paxos
enforces Q2∩Q2 only as an accident of using majorities everywhere — it's **strictly more
conservative than the proof requires.**

---

## What you can do with it

### Shrink the steady-state quorum

In **Multi-Paxos / Raft, Phase 1 runs only on leader change; Phase 2 runs on every write.**
So make **Q2 small** and pay for it with a **larger Q1**.

**N = 5:**

| Config | Q1 (election) | Q2 (commit) | Effect |
|---|---|---|---|
| Classic majority | 3 | 3 | baseline |
| **Flexible** | **4** | **2** | Faster/cheaper commits. Elections need 4 of 5. |

Fewer acks per write ⇒ lower latency (you wait on the 2nd-fastest replica, not the 3rd) and less
network/disk work in the common case. Leader elections get more expensive — but they're rare.

### Grid quorums

Arrange acceptors in a grid. **Q1 = a column, Q2 = a row.** Every row intersects every column, so
safety holds — and **both quorums can be smaller than a majority.** Rows don't intersect other
rows, which is now fine.

### Even-numbered clusters stop being pointless

Classic wisdom: 4 nodes tolerate the same 1 failure as 3, so don't bother. With FPaxos, `N=4` with
`Q1=3, Q2=2` is a genuinely useful configuration.

---

## The catch (read this before you get excited)

- **Fault tolerance is redistributed, not increased.** With `Q1=4, Q2=2` on 5 nodes, you can commit
  while 3 nodes are down — but you **cannot elect a new leader** unless 4 are up. If the leader
  then dies, you're stuck. **Availability is bounded by the *harder* phase.**
- **Grid quorums have non-uniform fault tolerance** — losing the wrong combination of nodes (a
  whole row or column) takes you out even if the raw failure count looks survivable. Majorities are
  *uniform*; that's their real virtue.
- **Durability shrinks.** A committed entry now lives on only `|Q2|` replicas. Safety still holds
  (Q1 will find it), but you have less redundancy against permanent loss.
- **Applies to Raft too** — with care. Raft's election restriction (the up-to-date log check)
  *is* the mechanism that plays Phase 1's role, so the voting quorum must intersect the replication
  quorum. Real implementations have used this; it interacts messily with **membership changes**,
  where the quorum sizes shift underneath you.

---

## Why it matters

1. **Intellectually:** it shows the field had an unexamined assumption sitting in plain sight for
   two decades. The proof was always weaker than the folklore.
2. **Practically:** it gives you a **tuning knob** between steady-state write cost and
   leader-election cost, which classic Paxos hid from you.
3. **It sharpens what quorums are *for*:** not "majority = democracy," but simply
   **"the reader must intersect the writer."** Everything else was decoration.

---

## Recall

- **`|Q1| + |Q2| > N`.** That's the whole theorem.
- **Phase-2 quorums need not intersect each other.**
- Majorities are a *sufficient* choice, not a *necessary* one — and they're the choice that makes
  fault tolerance **uniform**, which is why they're still the sane default.
- **Multi-Paxos/Raft favor small Q2** because Phase 1 is rare and Phase 2 is the hot path.
- **You don't get free fault tolerance** — you move it from one phase to the other.