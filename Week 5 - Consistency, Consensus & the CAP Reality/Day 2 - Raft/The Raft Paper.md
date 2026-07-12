# Raft — In Search of an Understandable Consensus Algorithm

> Notes on Ongaro & Ousterhout (USENIX ATC 2014), §1–5: replicated state machines,
> leader election, log replication, and safety.
> Written as my own engineering notes, not a reproduction of the paper.

---

## 1. The motivation: understandability as a design goal

Paxos had been the dominant consensus algorithm for a decade, and it had two problems that
the authors treat as *engineering* failures, not pedagogical ones:

1. **It's extraordinarily hard to understand.** Even after significant effort, most people
   struggle. The authors admit they only fully understood it after doing this work.
2. **It's a bad foundation for real systems.** Single-decree Paxos ("decide one value") is
   the thing that's specified; Multi-Paxos ("decide a *sequence* of values" — what you
   actually need) is sketched but never pinned down. Every real implementation ends up
   inventing its own undocumented variant. Chubby's authors famously reported a large gap
   between the published algorithm and a working system.

So Raft's **primary design goal was understandability** — an unusual thing to optimize for
in an algorithms paper. Correctness and performance were constraints; comprehensibility was
the objective function.

### 1.1 The two techniques used to achieve it

**(a) Problem decomposition.** Raft is deliberately split into three near-independent
subproblems that can be taught and reasoned about separately:

- **Leader election** — pick a leader when the old one fails.
- **Log replication** — leader accepts entries from clients and forces all logs to match its own.
- **Safety** — the key restriction ensuring no two state machines ever apply different
  commands at the same index.

**(b) State-space reduction.** Wherever there was a choice, Raft picks the option with
*fewer states and fewer ways things can go wrong*, even at some cost in elegance or
efficiency:

- Logs are **not allowed to have holes**. Entries flow strictly leader → follower, and only
  in order.
- Raft **limits the ways logs can become inconsistent** with each other.
- It uses **randomization** (randomized election timeouts) to resolve conflicts simply,
  rather than a deterministic protocol that would need more reasoning.

**The strong-leader principle:** in Raft, **log entries only ever flow from the leader to
followers.** This is a much stronger form of leadership than Paxos uses (where leadership is
just a performance optimization), and it's what makes the algorithm tractable.

---

## 2. Replicated state machines

The whole point of consensus. The setup:

```
   Client
     │
     ▼
 ┌──────────────────────────────────┐
 │  Consensus Module  →  Log        │   ← identical log on every server
 │         │                        │
 │         ▼                        │
 │  State Machine                   │   ← deterministic; same input order = same state
 └──────────────────────────────────┘
```

If every server's log contains the **same commands in the same order**, and the state machine
is **deterministic**, then every server computes the **same state** and produces the **same
outputs**. The cluster behaves like a single, highly reliable machine.

The consensus module's job is to keep the logs identical. That's it. Everything else follows.

**Guarantees a consensus algorithm of this class provides:**

- **Safety (never return an incorrect result)** under all non-Byzantine faults: network
  delays, partitions, packet loss, duplication, and reordering.
- **Availability** as long as **a majority** of servers are up and can talk to each other and
  to clients. A 5-server cluster tolerates 2 failures. Servers may rejoin later by restoring
  state from stable storage.
- **No dependence on timing for correctness.** Faulty clocks and extreme message delays can
  hurt *availability*, but never *safety*. (Contrast: Spanner, which uses clocks for
  correctness — but with bounded uncertainty and commit-wait.)
- **A command completes as soon as a majority responds** — a minority of slow servers doesn't
  drag down the common case.

**Typical uses:** leader election / configuration management for a larger system (GFS, HDFS,
RAMCloud), and the replicated state machines themselves (Chubby, ZooKeeper, etcd, Consul,
Kafka's KRaft controller quorum).

---

## 3. Raft basics

### 3.1 Server states

At any time, every server is exactly one of three states:

| State | Behavior |
|---|---|
| **Leader** | Handles all client requests. Sends heartbeats. There is at most one per term. |
| **Follower** | Passive. Never initiates anything. Only responds to RPCs from leaders and candidates. Redirects clients to the leader. |
| **Candidate** | Transient state used to campaign for leadership. |

```
            times out,                    receives votes
            starts election               from majority
 ┌────────┐ ──────────────► ┌───────────┐ ──────────────► ┌────────┐
 │Follower│                 │ Candidate │                 │ Leader │
 └────────┘ ◄────────────── └───────────┘                 └────────┘
      ▲      discovers current      │  times out,               │
      │      leader or new term     │  new election             │
      │                             └──────────┐                │
      └─────────────────────────────────────────────────────────┘
                    discovers server with higher term
```

Normal operation: **one leader, all others followers.**

### 3.2 Terms — the logical clock

Time is divided into **terms**, numbered with consecutive integers. Each term begins with an
election. If a candidate wins, it serves as leader for the rest of that term. If the election
is split, the term ends with **no leader** and a new term begins shortly after.

**Terms are Raft's logical clock**, and they're the mechanism that lets servers detect stale
information. Three rules make this work, and they're the most important invariants in the
whole algorithm:

1. Each server stores a `currentTerm` (persisted to stable storage). It **increases
   monotonically**.
2. **Terms are exchanged on every RPC.** If a server sees a term **larger** than its own, it
   immediately updates `currentTerm` and **reverts to follower**. (A leader or candidate that
   learns of a higher term steps down instantly. This is how a partitioned-then-rejoined
   stale leader is disarmed.)
3. If a server receives a request with a **stale** term, it **rejects** it.

**Key consequence:** *at most one leader per term* (Election Safety). Split brain in the sense
of "two leaders both committing" is impossible — an old leader may *think* it's leader, but it
can never get a majority to accept its entries, because a majority has already moved to a
higher term.

### 3.3 The RPCs

Raft's basic algorithm needs only **two** RPCs. (A third, `InstallSnapshot`, appears later
in §7.) All RPCs are **idempotent** and are **retried indefinitely** if no response arrives.

**RequestVote** — sent by candidates during elections.

| Field | Meaning |
|---|---|
| `term` | candidate's term |
| `candidateId` | who's asking |
| `lastLogIndex` | index of candidate's last log entry |
| `lastLogTerm` | term of candidate's last log entry |
| → `term` | receiver's currentTerm, for the candidate to update itself |
| → `voteGranted` | true if the vote was given |

**AppendEntries** — sent by the leader to replicate entries; **also used as the heartbeat**
(with an empty `entries[]`). One mechanism, two jobs — a nice bit of state-space reduction.

| Field | Meaning |
|---|---|
| `term` | leader's term |
| `leaderId` | so followers can redirect clients |
| `prevLogIndex` | index of the entry *immediately preceding* the new ones |
| `prevLogTerm` | term of that entry |
| `entries[]` | entries to store (empty = heartbeat) |
| `leaderCommit` | leader's `commitIndex` |
| → `term` | receiver's currentTerm |
| → `success` | true if follower contained a matching `prevLogIndex`/`prevLogTerm` |

### 3.4 Persistent state (must survive a crash — fsync before responding)

- `currentTerm`
- `votedFor` (candidate that received this server's vote in the current term, or null)
- `log[]`

**Why `votedFor` must be persisted:** without it, a server could crash, restart, and vote a
*second* time in the same term — electing two leaders in one term and destroying Election
Safety. This is a classic implementation bug.

Volatile: `commitIndex`, `lastApplied`. Leader-only volatile: `nextIndex[]`, `matchIndex[]`.

---

## 4. Leader election

### 4.1 The mechanism

- Servers start as **followers**. A follower stays a follower as long as it keeps receiving
  valid RPCs from a leader or candidate.
- Leaders send **periodic empty AppendEntries (heartbeats)** to maintain authority.
- If a follower receives nothing for an **election timeout**, it assumes there's no viable
  leader and **starts an election**.

**To begin an election, a server:**
1. Increments `currentTerm`
2. Transitions to **candidate**
3. **Votes for itself**
4. Issues `RequestVote` RPCs in parallel to all other servers

**Three possible outcomes:**

| Outcome | How |
|---|---|
| **It wins** | Receives votes from a **majority** for the same term. Each server votes for **at most one** candidate per term, **first-come-first-served** (plus the §5 log restriction). Majority rule ⇒ at most one winner per term. It immediately sends heartbeats to establish authority and prevent further elections. |
| **Another server wins** | It receives an `AppendEntries` from a claimed leader. If that leader's `term` ≥ its own `currentTerm`, it accepts the leader as legitimate and **returns to follower**. If the term is *smaller*, it **rejects** and remains a candidate. |
| **Nobody wins (split vote)** | Several followers time out simultaneously, split the vote, no one gets a majority. The candidate times out and starts a **new election with a higher term**. |

### 4.2 Randomized election timeouts — the elegant bit

Without care, split votes could **repeat indefinitely**: everyone times out at the same
moment, splits the vote, times out at the same moment again, forever.

**Raft's fix:** election timeouts are chosen **randomly** from a fixed interval (e.g.
150–300ms), **re-randomized on every election**.

This spreads servers out in time, so usually **one server times out first**, wins the
election, and sends heartbeats before anyone else has even woken up. The same mechanism
handles split votes: each candidate restarts its timer with a *fresh* random value, making
repeated splits exponentially unlikely.

The authors note they first tried a **ranking** system (servers ranked; lower-ranked ones
defer to higher). It worked but had subtle availability problems and needed fixes.
**Randomization was simpler and more obviously correct** — a direct instance of the
"understandability first" methodology.

---

## 5. Log replication

### 5.1 The normal path

1. Client sends a command to the **leader**.
2. Leader **appends** it to its own log as a new entry (each entry stores the **command**,
   the **term** in which it was created, and its **index**).
3. Leader sends `AppendEntries` **in parallel** to all followers.
4. Once the entry is **replicated on a majority**, the leader considers it **committed**.
   - Committing an entry also commits **all preceding entries in the leader's log**,
     including entries from previous leaders.
5. Leader **applies** the entry to its state machine and returns the result to the client.
6. The leader tracks the highest committed index in `commitIndex` and piggybacks it on future
   `AppendEntries` (via `leaderCommit`), so followers learn what to apply too.

**"Committed" is the durability contract:** a committed entry is guaranteed to eventually be
executed by all available state machines. It cannot be lost.

If a follower is slow, crashed, or a packet drops, the leader **retries `AppendEntries`
indefinitely** — even after responding to the client. Entries are idempotent, so duplicates
are harmless.

### 5.2 The Log Matching Property

Raft maintains this invariant, and it's the load-bearing wall of the whole algorithm:

> **If two entries in different logs have the same index and the same term, then:**
> **(a) they store the same command, and**
> **(b) the logs are identical in all preceding entries.**

**(a)** follows trivially: a leader creates at most one entry per (index, term), and never
moves or deletes entries from its own log (**Leader Append-Only**).

**(b)** is enforced by a **simple consistency check embedded in `AppendEntries`**: every
`AppendEntries` carries `prevLogIndex` and `prevLogTerm` — the index and term of the entry
*immediately before* the new ones. **If the follower doesn't have an entry matching that
index and term, it refuses the request.**

This gives an **inductive proof**: logs start empty (base case, trivially matching); the
consistency check preserves the matching property on every successful append. So a successful
`AppendEntries` means *the follower's entire log up to that point is identical to the
leader's*. That's a remarkably strong guarantee from a two-field check.

### 5.3 Repairing inconsistent logs

Leader crashes leave logs inconsistent. A follower may be **missing entries** the leader has,
have **extra uncommitted entries** the leader doesn't, or both — across multiple terms.

**Raft's approach is brutally simple: the leader is right.**

> **The leader forces followers' logs to duplicate its own. Conflicting entries in a follower's
> log are overwritten with entries from the leader's log.**

(This is safe only because of the **Election Restriction** in §5.4 — the leader is guaranteed
to hold every committed entry, so the entries being overwritten were never committed.)

**Mechanism:**
- Leader keeps `nextIndex[i]` for each follower — the next log index it will send. Initialized
  optimistically to `(leader's last log index) + 1`.
- If `AppendEntries` fails the consistency check, the leader **decrements `nextIndex[i]`** and
  retries.
- Eventually `nextIndex` reaches the point where the logs match. The follower then **deletes
  everything after that point** and appends the leader's entries. From then on the logs are
  identical and stay that way for the rest of the term.

**Optimization** (mentioned as probably unnecessary in practice): the follower's rejection can
include the **term of the conflicting entry and the first index it stores for that term**,
letting the leader skip over an entire term's worth of entries in one round-trip rather than
backing up one index at a time.

**Elegant consequence:** *the leader never needs to take any special action to restore
consistency after a crash.* It just starts normal operation, and the `AppendEntries`
consistency check converges the logs automatically. **A leader never overwrites or deletes
entries in its own log.**

### 5.4 Safety: the Election Restriction

Log replication alone is **not sufficient**. Consider: a follower is unavailable while the
leader commits several entries, then that follower gets elected leader and overwrites those
committed entries with its own. Committed data is lost. Disaster.

**Raft's fix — restrict who can be elected:**

> **A candidate cannot win an election unless its log contains all committed entries.**

Implemented **without any extra communication**, purely inside `RequestVote`: the RPC carries
the candidate's `lastLogIndex` and `lastLogTerm`. **A voter refuses its vote if its own log is
"more up-to-date" than the candidate's.**

**"Up-to-date" is defined by comparing the last entries:**
1. **The log with the later term in its last entry is more up-to-date.**
2. **If the last terms are equal, the longer log is more up-to-date.**

(Term first, *then* length. Getting this backwards is a classic bug — a long log full of
uncommitted entries from an old term must lose to a shorter log with a newer entry.)

**Why it works:** a candidate needs a **majority** to win. A committed entry is on a
**majority**. Any two majorities **intersect**. So at least one voter has the committed
entry — and by the up-to-date rule, that voter will refuse to vote for a candidate lacking it.
Therefore an elected leader necessarily holds every committed entry. This is the
**Leader Completeness Property**.

### 5.5 Committing entries from previous terms (the subtle part)

**This is the trap in the whole paper, and the thing to be able to explain on demand.**

The naive rule — *"an entry is committed once it's on a majority of servers"* — is **WRONG for
entries created by a previous leader.**

**The scenario (the paper's Figure 8):** an entry from an old term gets replicated to a
majority by a *new* leader, and the new leader — before committing anything of its own —
crashes. It is then possible for a *different* server to be elected (its log is legitimately
more up-to-date by the term-first rule) and **overwrite that entry**, even though it was
present on a majority. If the first leader had already told a client "committed," that's a
safety violation.

**Raft's rule:**

> **A leader only counts replicas to commit entries from its *current* term.**
> **Entries from prior terms are committed *indirectly*, once an entry from the current term
> is committed.**

By the Log Matching Property, committing a current-term entry at index `i` automatically
commits everything before `i`. So old entries do get committed — just never *directly* by
counting replicas.

**Practical implication:** a newly elected leader should immediately append a **no-op entry**
in its own term. This lets it commit the no-op (and thereby, transitively, all inherited
entries) and start serving reads safely. Every real implementation does this.

**The alternative Raft rejected:** assigning the *current* term number to old entries when
re-replicating them. That would make the commitment rule uniform — but it would **destroy the
history**, because Raft relies on the term in an entry to reason about *when* it was created.
Keeping the original term preserves that reasoning at the cost of one wrinkle in the
commitment rule. Classic "state-space reduction over local elegance."

### 5.6 The five safety properties

Everything above exists to guarantee these:

| Property | Statement |
|---|---|
| **Election Safety** | At most one leader can be elected in a given term. |
| **Leader Append-Only** | A leader never overwrites or deletes entries in its own log; it only appends. |
| **Log Matching** | If two logs contain an entry with the same index and term, the logs are identical in all entries up to that index. |
| **Leader Completeness** | If an entry is committed in a term, it is present in the logs of the leaders of all higher terms. |
| **State Machine Safety** | If a server has applied an entry at a given index to its state machine, no other server will ever apply a different entry for the same index. |

The proof structure: **Leader Completeness** is proved by contradiction (assume a leader of a
later term lacks a committed entry; the voter-intersection argument shows the voter that had
the entry couldn't have voted for it — contradiction). **State Machine Safety** then follows
directly from Leader Completeness plus Log Matching.

### 5.7 Follower and candidate crashes

Handled by one line: **RPCs are retried indefinitely, and they are idempotent.** If a follower
crashes, subsequent `RequestVote` and `AppendEntries` calls fail; the leader keeps retrying
until it comes back. If a follower crashes *after* completing an RPC but *before* responding,
it receives the same RPC again and simply ignores the redundant work.

Far simpler than the leader-crash case, which is where all the machinery lives.

### 5.8 Timing and availability

**Safety never depends on timing.** But **liveness** does — you need this inequality to hold:

```
broadcastTime  <<  electionTimeout  <<  MTBF
```

- `broadcastTime` — time to send RPCs to all servers and get responses. **~0.5–20 ms**
  (dominated by fsync, if you persist before responding — which you must).
- `electionTimeout` — **~10–500 ms**, randomized within its range.
- `MTBF` — mean time between failures for a single server. **Months.**

**Why each `<<` matters:**
- `broadcastTime << electionTimeout`: otherwise heartbeats can't arrive reliably before the
  timeout fires, and followers start spurious elections, causing **leader flapping** —
  the cluster spends its time electing instead of serving.
- `electionTimeout << MTBF`: otherwise servers fail faster than the cluster can replace a
  leader, and the system is unavailable more often than not.

Practical tuning note: over a WAN, or with a slow fsync (`broadcastTime` climbing), an
aggressively low election timeout **reduces** availability. This is the most common Raft
misconfiguration in production.

---

## 6. Beyond §5 (pointers)

- **§6 Cluster membership changes** — you can't switch configurations atomically across all
  servers, so a naive switch can produce **two disjoint majorities** (two leaders!). Raft's
  answer: **joint consensus**, a transitional config requiring majorities from *both* old and
  new configurations. (Later work simplified this to **single-server-at-a-time** changes,
  which is what etcd actually implements.)
- **§7 Log compaction / snapshots** — the log grows forever; snapshot the state machine,
  discard the prefix, add an `InstallSnapshot` RPC for followers that have fallen too far
  behind.
- **§8 Client interaction** — linearizable semantics require **client IDs + sequence numbers**
  to make retried commands idempotent (otherwise a retry after a lost response executes twice).
  Read-only queries need care: the leader must (a) commit a no-op in its term and (b) confirm
  it's still leader via a heartbeat round (**ReadIndex**) *before* serving a read — otherwise
  a deposed leader returns stale data.

---

## 7. Interview-ready recall

- **Raft's differentiator is a strong leader** — entries flow only leader → follower. Paxos
  treats leadership as an optimization; Raft makes it structural, which is why it's
  comprehensible.
- **Terms are a logical clock.** Higher term seen ⇒ step down immediately. This alone kills
  split-brain writes.
- **Majority quorums intersect.** This one fact powers *both* Election Safety and Leader
  Completeness.
- **`2f + 1` servers tolerate `f` failures.** 3 servers → 1 failure. 5 → 2. (Even-sized
  clusters buy you nothing: 4 servers still only tolerate 1.)
- **Up-to-date = compare last log TERM first, then INDEX.** Not length first.
- **A leader cannot commit a previous term's entry by counting replicas.** Append a no-op in
  your own term first. If you can explain Figure 8's scenario, you understand Raft.
- **Persist `currentTerm`, `votedFor`, and `log[]` before responding to any RPC.** Skipping
  `votedFor` gives you a double-vote and two leaders in one term.
- **Randomized election timeouts** are how split votes resolve — no clever deterministic
  protocol needed.
- **Timing affects liveness only, never safety.**

**Where you've already met this:** etcd (backs Kubernetes), Consul, TiKV, CockroachDB, and
**Kafka's KRaft controller quorum** — the KRaft metadata log *is* a Raft log, and the
controller is the Raft leader. The `__cluster_metadata` topic is literally the replicated log
described above.