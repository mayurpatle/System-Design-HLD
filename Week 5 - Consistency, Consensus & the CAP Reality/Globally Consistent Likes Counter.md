## The Problem

A globally consistent **"likes" counter** for a viral post.


| Parameter        | Value                             |
| ---------------- | --------------------------------- |
| Peak write rate  | 100,000 increments/sec            |
| Peak read rate   | 1,000,000 reads/sec               |
| Read:write ratio | 10:1                              |
| Geography        | Multi-region                      |
| Data shape       | Single monotonic integer per post |


**Framing question to hold onto all day:** the counter is *monotonic* and increments *commute*. Which of the three designs actually exploits that, and which pay for coordination they don't need?

---

# Morning (3 hours)

## Design 1 — Single-leader Postgres with read replicas

Sketch the topology, then specify:

- [x] **Write path.** Single primary. `UPDATE posts SET likes = likes + 1` vs. an append-only `likes_events` table with periodic rollup. Which one survives 100k/sec, and why does the naive one not?
- [x] **Row-level contention.** All 100k writes/sec hit one row → one lock. Quantify the theoretical ceiling. Then apply the standard fix: **sharded counters** (N sub-counters, sum on read). Pick N and justify.
- [x] **Replication lag.** Async streaming replication. State a realistic p50/p99 lag figure for same-region and cross-region replicas.
- [x] **Consistency on reads.** Name the model precisely. Which session guarantees do you lose? Can a user who just liked the post see their own like? (read-your-writes — how do you fix it, and what does that fix cost?)
- [ ] **Latency estimate.** Write latency (client → primary → fsync → ack). Read latency (client → nearest replica). Show the cross-region penalty explicitly.
- [ ] **Scaling limit.** At what point does this design fall over, and what's the first thing to break?

**Numbers to fill in**


| Metric                                   | Value | Reasoning |
| ---------------------------------------- | ----- | --------- |
| Write latency p50 / p99                  |       |           |
| Read latency p50 / p99                   |       |           |
| Max sustainable write throughput         |       |           |
| Replica lag (same-region / cross-region) |       |           |
| Consistency model                        |       |           |


---

## Design 2 — Raft consensus group (etcd-like)

- [ ] **Quorum size.** 3 nodes vs 5 nodes. Fault tolerance `f = ⌊(N-1)/2⌋`. Justify your pick against the latency cost.
- [ ] **Placement.** All nodes in one region, or spread across three? Compute the majority-commit latency for each. A cross-region quorum pays at least one inter-region RTT per write — put a number on it.
- [ ] **Write path.** Leader appends → replicates → majority persists → commit → apply → ack. Every increment is a full round of consensus. What's the ceiling on ops/sec for a single Raft group?
- [ ] **The batching question.** Can you amortize? Batch N increments into one log entry. What does that do to per-increment latency vs throughput, and what does it break?
- [ ] **Reads.** Leader-only reads need a ReadIndex heartbeat or a leader lease. Follower reads give you staleness. Which do you pick at 1M reads/sec, and can a Raft group serve 1M reads/sec at all?
- [ ] **Sharding.** If one group can't take it, do you multi-Raft? Does sharding a *single counter for a single post* even make sense here?
- [ ] **Consistency model.** Name it: linearizable. State exactly what that buys the product, and whether the product needs it.

**Numbers to fill in**


| Metric                                | Value | Reasoning |
| ------------------------------------- | ----- | --------- |
| Cluster size N / tolerated failures f |       |           |
| Write latency p50 / p99               |       |           |
| Read latency (linearizable / stale)   |       |           |
| Max sustainable write throughput      |       |           |
| Consistency model                     |       |           |


---

## Design 3 — G-Counter CRDT across regions

- [ ] **Structure.** A G-Counter is a vector of per-replica counters; value = sum of all entries; merge = element-wise `max`. Write this out formally — state, `increment()`, `value()`, `merge()`.
- [ ] **Why merge is safe.** Show that merge is **associative, commutative, and idempotent** (an ACI/join-semilattice argument). This is the whole reason no coordination is needed.
- [ ] **Write path.** Local increment, ack immediately, no cross-node round trip. State the write latency — it should be a local disk/memory number, not a network one.
- [ ] **Merge frequency.** Gossip interval choice: 100ms? 1s? 10s? Quantify the tradeoff between staleness window and network amplification (`O(N²)` gossip vs. tree-based dissemination).
- [ ] **Convergence guarantee.** State it precisely: **strong eventual convergence** — all replicas that have received the same set of updates are in the same state, regardless of order. Bound the staleness under your chosen merge interval.
- [ ] **Cost.** State size grows with replica count. What happens with 50 regions? With per-user replicas? (This is where you mention PN-Counters, and why you don't need one here — likes only go up, until they don't. Unlikes?)
- [ ] **The unlike problem.** G-Counter can't decrement. Do you switch to a PN-Counter, or model an unlike as a separate G-Counter and subtract? Argue for one.

**Numbers to fill in**


| Metric                           | Value | Reasoning |
| -------------------------------- | ----- | --------- |
| Write latency p50 / p99          |       |           |
| Read latency                     |       |           |
| Max sustainable write throughput |       |           |
| Staleness bound                  |       |           |
| Consistency model                |       |           |


---

# Afternoon (3 hours)

## Part A — The comparison doc (~4 pages)

Structure it as a real design doc, not an essay:

1. **Context and requirements** — restate the problem, call out the explicit non-functional targets, and state the one property that makes this problem unusual (commutativity).
2. **Design 1 / 2 / 3** — one page each, with the diagram, the write path, the read path, and the filled-in numbers table.
3. **Comparison matrix** — see below.
4. **Failure analysis** — Part B.
5. **Recommendation** — Part C.

### Comparison matrix


| Dimension               | D1: Postgres + replicas | D2: Raft group | D3: G-Counter CRDT |
| ----------------------- | ----------------------- | -------------- | ------------------ |
| Write latency (p50/p99) |                         |                |                    |
| Read latency (p50/p99)  |                         |                |                    |
| Max write throughput    |                         |                |                    |
| Max read throughput     |                         |                |                    |
| Consistency model       |                         |                |                    |
| Staleness bound         |                         |                |                    |
| Fault tolerance         |                         |                |                    |
| Operational complexity  |                         |                |                    |
| Cost at 1M reads/sec    |                         |                |                    |


---

## Part B — Failure analysis

Work each design through all three scenarios. Don't summarize — say what specifically happens to the write path, the read path, and the counter value.

### B1. A single node fails


|                                                       | Postgres | Raft | CRDT |
| ----------------------------------------------------- | -------- | ---- | ---- |
| Which node? (primary vs replica / leader vs follower) |          |      |      |
| Time to detect                                        |          |      |      |
| Time to recover                                       |          |      |      |
| Writes during the window                              |          |      |      |
| Reads during the window                               |          |      |      |
| Data loss?                                            |          |      |      |


Prompts:

- Postgres: primary failure → failover. Automatic or manual? How much committed-but-unreplicated data is lost with async replication? Where does split brain come from, and what's your fencing story?
- Raft: leader failure → election. Bound the unavailability window (election timeout + election round). Is any *committed* data at risk? (No — say why: the election restriction.)
- CRDT: node failure → its counter contribution is temporarily invisible. Is the value wrong, or just stale? What happens to increments that were local-only and unreplicated when the disk died?

### B2. A region is partitioned


|                       | Postgres | Raft | CRDT |
| --------------------- | -------- | ---- | ---- |
| Minority side: writes |          |      |      |
| Minority side: reads  |          |      |      |
| Majority side         |          |      |      |
| Behavior on heal      |          |      |      |
| CAP classification    |          |      |      |


Prompts:

- Postgres: does the partitioned replica serve unboundedly stale reads? Does anyone promote a second primary?
- Raft: minority side is **unavailable for writes** — that's the CP choice. Can it serve reads? Only stale ones. Is that acceptable for a likes counter?
- CRDT: **both sides keep accepting writes.** Both are "wrong" (each undercounts) but neither is inconsistent. On heal, merge converges. Show the merge arithmetic with a concrete two-region example.

### B3. Workload spikes 10x (1M writes/sec, 10M reads/sec)


|                                    | Postgres | Raft | CRDT |
| ---------------------------------- | -------- | ---- | ---- |
| First bottleneck to break          |          |      |      |
| Failure mode (degrade vs collapse) |          |      |      |
| Backpressure mechanism             |          |      |      |
| Scaling lever available            |          |      |      |
| Time to apply that lever           |          |      |      |


Prompts:

- Which designs degrade *gracefully* (higher staleness, same availability) versus *catastrophically* (queue buildup, timeouts, cascading retries)?
- Which one scales horizontally without a schema change or a re-shard?
- Where does a write-ahead buffer (Kafka / Redis) belong in each, and does adding it just turn D1 into D3 with extra steps?

---

## Part C — Recommendation

For each workload, name the pick, then give the **one-sentence reason** and the **one thing you're giving up**.

### C1. Real-time viral post counter

**Pick:** ___
**Because:** commutative, monotonic, and nobody is harmed by being 300ms behind.
**Giving up:** ___

### C2. Bank balance

**Pick:** ___
**Because:** ___
**Giving up:** ___
*Push yourself here — is a raw Raft counter actually the right answer for a balance, or do you want a replicated **ledger** (append-only entries) with the balance as a derived fold? Argue it.*

### C3. Analytics counter that can lose a few seconds

**Pick:** ___
**Because:** ___
**Giving up:** ___
*And the contrarian option: is the real answer here neither — just batch to a columnar store and stop pretending it's a counter?*

---

## Closing synthesis (write this last, ~3 paragraphs)

Answer the question the whole exercise is built around:

> **Consistency is not a property you choose for a system. It's a property you choose per-operation, based on what the operation's semantics allow.**

Explain how a likes counter, a bank balance, and a page-view metric are all "a number that goes up" and yet demand three different architectures. Then generalize: what's the *test* you'd apply to any new counter to decide which bucket it falls in?

---

## Self-check before you call it done

- [ ] Every latency number has a stated assumption behind it (RTT, fsync cost, hop count) — no bare figures
- [ ] You used *linearizable* and *serializable* correctly and didn't conflate them
- [ ] You named the CAP/PACELC position of each design explicitly
- [ ] The failure analysis says what happens to the **value**, not just to availability
- [ ] The recommendation section names a tradeoff you're accepting, not just a winner
- [ ] You can defend the sharded-counter trick in D1 against "but then reads get expensive"

---

## Related notes

- [[raft-consensus]]
- [[crdts]]
- [[consistency-models]]
- [[quorum-systems]]
- [[cap-pacelc]]

