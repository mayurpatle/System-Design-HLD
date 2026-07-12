# Consistency Models

> Reference card. 8 models, strongest → weakest, plus a self-test drill.

---

## 0. The hierarchy (memorize the shape first)

```
Strict Serializability   ← transactions + real-time order
        │
Linearizability          ← single object + real-time order
        │
Sequential Consistency   ← single global order, but NOT real-time
        │
Causal Consistency       ← only happens-before is preserved
        │
   ┌────┴────┬──────────────┐
Read-Your-  Monotonic    Monotonic     ← "session guarantees"
 Writes      Reads        Writes         (each weaker than causal;
   └────┬────┴──────────────┘             mutually incomparable)
        │
Eventual Consistency     ← converge someday, no ordering promise
```

Two things to keep straight:

- **Strong models are about ORDER** (is there one timeline, and does it match real time?).
- **Session guarantees are about a SINGLE CLIENT's experience** (does *my* view make sense?).
  They say nothing about what other clients see. **Causal consistency implies all three
  session guarantees**; the reverse is not true.

A useful test question for any model: *"What anomaly does this model forbid?"* If you can
name the anomaly, you understand the model.

---

## 1. Strict Serializability (a.k.a. "strict lin", external consistency)

**Definition:** Transactions (multi-object) appear to execute in some serial order, and that
order is consistent with real time — if transaction T1 commits before T2 starts, T1 must
appear before T2.

**= Serializability + Linearizability.** Serializability alone gives you *a* serial order,
which may be an order from the past. Strict serializability nails that order to the wall
clock.

**Forbids:** every anomaly, including "I committed a transfer, then a later transaction read
a balance from before the transfer."

**Real-world example:** **Google Spanner** — its "external consistency" is exactly this,
implemented with TrueTime commit-wait. Also FoundationDB, CockroachDB (approximately),
and a single-node Postgres running at `SERIALIZABLE` isolation.

---

## 2. Linearizability (atomic consistency, "strong consistency")

**Definition:** Every operation on a **single object** appears to take effect atomically at
some instant between its invocation and its response, so a read always returns the most
recent completed write, and once any client sees a new value, nobody can see the old one
again.

The illusion: **there is only one copy of the data.**

**Forbids:** stale reads, and "the value went backwards."

**Real-world example:** **etcd / ZooKeeper writes / Consul** — the compare-and-set register
used for distributed locks and leader election. If two nodes could disagree about who holds
the lock, you get split brain. Also DynamoDB with `ConsistentRead=true`.

**Cost:** requires consensus (or a strict single-leader path). Response times are bounded
below by network round-trips — this is the E**C** side of PACELC.

---

## 3. Sequential Consistency

**Definition:** All nodes observe operations in **the same total order**, and that order
respects each individual process's program order — but the order need **not** match real
time.

The difference from linearizability in one line: *linearizability = sequential + real-time*.
Under sequential consistency, my write can be "delayed" into the future relative to your read,
as long as everyone agrees on the resulting sequence.

**Forbids:** two clients seeing operations in *different* orders. Permits: staleness.

**Real-world example:** **ZooKeeper reads.** Writes go through Zab (linearizable), but reads
are served from any follower — so a read may be stale. However, a client is guaranteed to
see updates in order, never out of order. (Call `sync()` before a read to upgrade to
linearizable.) Also: CPU memory models with store buffers are sequentially consistent at
best, not linearizable across cores.

---

## 4. Causal Consistency

**Definition:** Operations that are causally related (A *happened-before* B) are seen in that
order by every node; **concurrent** operations may be seen in different orders by different
nodes.

This is a **partial order** — a DAG, like a git history — rather than a single timeline.

**Forbids:** the "reply visible before the post" anomaly. Permits: two independent posts
appearing in different orders for different users.

**Real-world example:** A **comment thread**. If Alice posts "Is the server down?" and Bob
replies "Yes, restarting now," nobody may ever see Bob's reply without Alice's question —
that would be nonsense. But two unrelated comments from strangers can safely appear in
either order.

**Why it matters:** causal consistency is the **strongest model that remains available under
partition and doesn't pay unbounded latency**. That makes it the sweet spot everyone chases.
Implemented via version vectors / dotted version vectors. See: COPS, Riak's vclocks,
MongoDB causally-consistent sessions.

---

## 5. Read-Your-Writes (Read-Your-Own-Writes / RYW)

**Definition:** A client that has written a value will, in all subsequent reads *by that same
client*, see that value or a newer one — though other clients may still see the old value.

**Forbids:** "I updated my profile, hit refresh, and my change was gone."

**Real-world example:** You post a comment on a blog. The page reloads and reads from an
async replica that hasn't got your write yet — your comment vanishes and you post it again.
The standard fix: **route a user's reads to the primary (or to a sticky replica) for N
seconds after they write.**

**Note:** this is a *session guarantee*. It promises nothing about anyone else's view.

---

## 6. Monotonic Reads

**Definition:** If a client reads a value, any subsequent read by that client returns that
value or a newer one — never an older one.

**Forbids:** **time going backwards.** You refresh the page and *lose* data you already saw.

**Real-world example:** You load a Twitter thread and see 3 replies. You hit refresh and now
see only 2 — because the second read hit a lagging replica. This is arguably *more*
confusing to users than plain staleness, because it looks like data was deleted.
Standard fix: **hash the user ID to always pick the same replica.**

---

## 7. Monotonic Writes

**Definition:** Writes by a single client are applied in the order that client issued them.

**Forbids:** your own writes being reordered against each other.

**Real-world example:** You upload a document, then immediately delete it. If the writes
reach a replica out of order, the delete is applied to nothing, then the upload lands — and
the "deleted" document exists forever. Or: you set your status to "Away", then to
"Available", and end up showing as "Away."

This is essentially **sequential consistency, but only for one client's own writes.**

---

## 8. Eventual Consistency

**Definition:** If no new writes are made, all replicas will *eventually* converge to the
same value — with no guarantee about when, and no guarantee about what any individual read
returns in the meantime.

**Forbids:** ...almost nothing. It's a *liveness* guarantee, not a safety one. Note that
"eventually" is unbounded: a system that returns stale data for a year and then converges
technically satisfies it.

**Real-world example:** **DNS.** You change an A record; the propagation takes minutes to
hours depending on TTLs, and different resolvers return different answers in the meantime.
Everyone eventually agrees. Also: Cassandra at `CL=ONE`, S3 (historically), Dynamo,
Riak by default.

**Conflict resolution is the hidden question.** "Eventually converge" *to what*? Last-writer-
wins (drops data silently), siblings/vector clocks (pushes conflict resolution to the app),
or CRDTs (converges deterministically by construction).

---

## 9. The anomaly cheat-sheet

| Anomaly | Forbidden by |
|---|---|
| Two clients see a different order of events | Sequential and above |
| Read returns a stale value despite a completed write | Linearizability and above |
| Value goes backwards across two clients | Linearizability and above |
| Value goes backwards for *one* client | Monotonic Reads and above |
| My own write disappears when I read | Read-Your-Writes and above |
| My own two writes get reordered | Monotonic Writes and above |
| Reply visible before the post it replies to | Causal and above |
| Transaction sees a snapshot from before a committed transaction | Strict Serializability |

---

## 10. DRILL — Name the model

Cover the answers. For each system, say the model out loud, then check.
The recurring lesson: **almost every answer is "it depends on configuration."** If you
answer with a single word, you're wrong. Answer with the *config → model* mapping.

---

### 1. PostgreSQL (single node)

<details><summary>Answer</summary>

- **Single node ⇒ linearizable by construction** (there's literally only one copy).
- **Isolation is the separate axis:** default is `READ COMMITTED` — *not* serializable
  (permits non-repeatable read, phantom, write skew). At `SERIALIZABLE` (SSI), you get
  **strict serializability** on a single node.
- **The trap:** add an **async streaming replica** and route reads to it → you drop to
  **eventual**, and you break both **monotonic reads** and **read-your-writes**. This is the
  single most common accidental consistency bug in production.
- `synchronous_commit = remote_apply` on the replica pulls you back toward linearizable, at
  the cost of the replica's round-trip latency.
</details>

---

### 2. DynamoDB

<details><summary>Answer</summary>

- **Default reads: eventually consistent** (and cheaper — half the RCU).
- `ConsistentRead=true` ⇒ **linearizable** for that item.
- `TransactWriteItems` / `TransactGetItems` ⇒ **serializable** across items.
- **Global Tables** (multi-region, multi-master) ⇒ **eventual**, with **last-writer-wins**
  conflict resolution. Cross-region, you cannot get linearizability at all.
- Correct answer: *"per-request tunable: EL by default, EC on demand."*
</details>

---

### 3. Cassandra with QUORUM reads + QUORUM writes

<details><summary>Answer</summary>

**The trap question.** The tempting answer is "linearizable, because R + W > N." **Wrong.**

`R + W > N` guarantees the read quorum *overlaps* the write quorum, so a read sees the
latest **successfully completed** write. But:

- Cassandra writes are **not atomic across replicas**. A write that fails partway (say, it
  reached 1 of 3 replicas) is **not rolled back**. A later quorum read may or may not see
  it, and *two successive reads can disagree* → value can go **backwards**.
- There is **no consensus** in the normal write path — no ordering of concurrent writes.
  Conflicts resolve by **last-writer-wins on timestamp**, which silently drops data.
- Jepsen confirmed: quorum Cassandra is **not linearizable**.

**Correct answer:** *"strong-ish / mostly-latest reads, but formally not linearizable — it's
roughly eventual-with-high-probability-freshness."*

To actually get linearizability in Cassandra you need **LWT** (`IF NOT EXISTS`, `SERIAL`
consistency), which runs **Paxos** per partition — and costs ~4 round-trips.
</details>

---

### 4. Cassandra with CL=ONE

<details><summary>Answer</summary>

**Eventual consistency**, plainly. Write acked by one replica; read served by one replica,
which may be a different one that has never heard of the write.

Breaks read-your-writes, monotonic reads, everything. Converges later via hinted handoff,
read repair, and anti-entropy (Merkle tree) repair.

`LOCAL_ONE` is the same thing, scoped to the local DC — the standard **PA/EL** posture.
</details>

---

### 5. etcd

<details><summary>Answer</summary>

- **Linearizable.** It's a Raft-replicated consistent key-value store; that's its entire
  purpose. Writes go through the Raft log (total order broadcast); reads are linearizable by
  default (leader confirms it's still leader via a quorum ReadIndex before serving).
- **Escape hatch:** `WithSerializable()` on a read ⇒ served from the local node's state
  without the quorum round trip ⇒ **stale / sequential**, faster. Explicit opt-out.
- Transactions (`Txn` with compare/success/failure) ⇒ effectively **strict serializable**
  over the keys involved.
- This is why it backs Kubernetes: leader election and locks require exactly this.
</details>

---

### 6. Spanner

<details><summary>Answer</summary>

- **Strict serializability** — Google calls it **external consistency**, which is the same
  thing. This is the strongest model on the list.
- **How:** Paxos per shard for replication + 2PC across shards for multi-shard transactions
  + **TrueTime** (GPS + atomic clocks giving a bounded uncertainty interval `ε`) to assign
  globally meaningful commit timestamps. The commit **waits out** the uncertainty window
  (`commit-wait`) so that timestamp order provably matches real-time order.
- **The cost is latency, not availability** — pure PACELC. Spanner's insight is that with
  good enough clocks and networks, EC's latency cost becomes *bounded and small* rather than
  eliminated.
- **Read-only transactions** at a past timestamp get lock-free **snapshot reads** — still
  consistent, no coordination.
</details>

---

### 7. Redis, master–replica (async replication)

<details><summary>Answer</summary>

- **Eventual consistency**, and weaker in practice than people assume.
- Replication is **asynchronous by default**. The master acks the client *before* the replica
  has the data.
- **Reads from replicas** ⇒ break read-your-writes and monotonic reads.
- **Even reading only from the master is not linearizable**, because on failover Sentinel can
  promote a replica that is **missing acked writes** ⇒ **acknowledged writes are silently
  lost**. Jepsen: Redis Sentinel/Cluster loses updates under partition.
- `WAIT numreplicas timeout` gives you *partial* protection — but it's not consensus; it
  doesn't prevent a stale master from acking, and it's best-effort.
- **Redlock** (the distributed lock recipe) is famously **not** a safe lock — no fencing
  tokens, relies on clock/timing assumptions. Use etcd/ZooKeeper for locks, not Redis, if
  correctness matters.
- Correct answer: *"eventual; not linearizable even from the master; can lose acked writes on
  failover."*
</details>

---

### 8. Riak

<details><summary>Answer</summary>

- **Default: eventual consistency**, in the Dynamo lineage.
- But it's the **causally-aware** flavor: it uses **dotted version vectors** to track
  causality, and when it can't order two writes it *keeps both* as **siblings**, handing
  conflict resolution to the application. That's the honest alternative to last-writer-wins.
- **CRDT data types** (counters, sets, maps, registers) give you deterministic, automatic
  convergence — still eventual, but with no lost updates.
- **Strong consistency mode** (`riak_ensemble`, strongly-consistent bucket types) uses a
  Paxos variant ⇒ **linearizable** for those buckets.
- Correct answer: *"eventual with explicit causality tracking; optionally linearizable via
  consensus buckets."*
</details>

---

### 9. MongoDB (bonus)

<details><summary>Answer</summary>

The most configurable of the lot — the answer is a matrix:

| Read/Write concern | Model |
|---|---|
| `w:1`, read from primary | Read-your-writes-ish; can **lose acked writes** on failover (rollback) |
| `w:1`, read from secondary | **Eventual** |
| `w:majority` + `readConcern:majority` | No stale-committed reads; still **not linearizable** |
| `w:majority` + `readConcern:linearizable` | **Linearizable** (single document) |
| Causally consistent session | **Causal** |
| Multi-document transaction + `majority`/`snapshot` | **Snapshot isolation** (not strict serializable) |

Note the important one: **`readConcern: majority` is NOT linearizable.** It only guarantees
you read data that won't be rolled back — it can still be stale.
</details>

---

### 10. ZooKeeper (bonus)

<details><summary>Answer</summary>

The classic "split answer," and a great interview flex:

- **Writes: linearizable.** All writes are totally ordered by Zab (total order broadcast) and
  assigned a monotonically increasing `zxid`.
- **Reads: sequentially consistent, NOT linearizable.** Reads are served locally by whatever
  follower you're connected to, which may be lagging. You will never see events *out of
  order*, but you may see them *late*.
- **Upgrade path:** call `sync()` before the read ⇒ forces the follower to catch up ⇒
  effectively linearizable read.
- The `zxid` doubles as a perfect **fencing token** — this is what makes ZooKeeper locks
  actually safe (unlike Redlock) when the storage layer checks the token.
</details>

---

## 11. The meta-lesson from the drill

1. **"What consistency model is X?" is almost always an ill-posed question.** The real answer
   is a function: `(write concern, read concern, read routing) → model`. Say that out loud in
   an interview and you sound senior immediately.
2. **`R + W > N` does not mean linearizable.** Cassandra QUORUM is the canonical counterexample
   (no atomic writes, no consensus, LWW conflict resolution).
3. **Reading from the primary does not mean linearizable either**, if failover can lose acked
   writes (Redis, MongoDB `w:1`).
4. **Linearizability requires consensus** — if a system doesn't run Paxos/Raft somewhere in
   the path, be skeptical of its "strong consistency" marketing.
5. **The session guarantees are cheap and buy most of the UX win.** Sticky routing gets you
   read-your-writes + monotonic reads for near-zero cost. Do this before reaching for
   linearizability.