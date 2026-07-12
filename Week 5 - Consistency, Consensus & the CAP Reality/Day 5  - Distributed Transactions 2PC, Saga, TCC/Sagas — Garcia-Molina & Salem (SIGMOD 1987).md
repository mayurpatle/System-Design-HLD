# Sagas — Garcia-Molina & Salem (SIGMOD 1987)

> The original paper. Short, readable, and **not about what you think it's about.**
> Companion to `distributed-transactions-2pc.md`.

---

## 0. ⚠️ The misconception to clear first

**This paper is not about distributed transactions. It is not about microservices. It predates
both by decades.**

It is about **Long-Lived Transactions (LLTs)** inside **a single database**. The problem being
solved is: *"a transaction that runs for hours holds locks for hours, and blocks everything else."*

The microservices community later **repurposed** the idea, because the same structure — a sequence
of local commits plus compensations — happens to solve a completely different problem (atomicity
across services without 2PC). **The mechanism transferred; the original motivation did not.**

Knowing this is a genuine differentiator. Most people cite this paper having never read it.

---

## 1. The problem: Long-Lived Transactions

An LLT is a transaction whose execution takes **a long time** — minutes, hours, even days. The
paper's examples are the batch-processing workloads of the era:

- Processing **every record in a large table** (e.g., adding monthly interest to all bank accounts).
- Bulk reconciliation or claims processing.
- A travel booking spanning flight + hotel + car.

**Why they take so long:** they touch enormous amounts of data, or they involve **human interaction
or external systems** with long think times.

### Why LLTs break standard concurrency control

Under **2PL**, a transaction holds its locks until commit. So an LLT holds locks **for hours**.

```
LLT:  ████████████████████████████████████████  (holds locks for 3 hours)
T1:      ▒ blocked ────────────────────────────►
T2:         ▒ blocked ─────────────────────────►
T3:            ▒ blocked ──────────────────────►
```

- **Throughput collapses.** Short transactions queue behind the LLT.
- **Deadlock and abort risk explode.** The longer you run, the more likely you collide with
  something and get rolled back — and rolling back **3 hours of work** is catastrophic.
- **Recovery cost is enormous.** A crash near the end means redoing everything.

**And you cannot fix this by just running the LLT faster.** The problem is structural: **atomicity
and isolation over a long duration are fundamentally at odds with concurrency.**

---

## 2. The key insight

> **Many LLTs are not really *one* indivisible unit. They are a *sequence* of relatively
> independent steps, each of which individually leaves the database consistent.**

If that's true, you don't need to hold locks across the whole thing. You can **commit each step as
you go** — releasing its locks immediately — and let other transactions interleave freely.

**The price you pay:** you lose atomicity over the whole sequence. If step 5 fails, steps 1–4 are
**already committed** and visible to everyone.

**The paper's answer to that price:** **don't try to undo them physically. Undo them
*semantically*.**

---

## 3. The definition

> **A SAGA is an LLT that can be written as a sequence of transactions `T₁, T₂, …, Tₙ` which may be
> interleaved with other transactions.**
>
> **Each `Tᵢ` is a real, ACID transaction that commits independently.**
>
> **Each `Tᵢ` has an associated COMPENSATING TRANSACTION `Cᵢ` that semantically undoes it.**

**The system guarantees exactly one of two outcomes:**

```
SUCCESS:   T₁  T₂  T₃  …  Tₙ                       ← all steps completed

or

ABORT:     T₁  T₂  …  Tⱼ   Cⱼ  …  C₂  C₁           ← partial execution, then
                            └──────────┘             compensations in REVERSE order
```

**That's the whole contract.** Note what's *missing*: there is no "nothing happened" outcome. The
saga either finishes, or it runs the compensations — but **the intermediate states existed, and
other transactions saw them.**

### Atomicity: relaxed, not abandoned

| Level | ACID? |
|---|---|
| **Each subtransaction `Tᵢ`** | ✅ Fully ACID. Commits independently. Preserves DB consistency. |
| **The saga as a whole** | ⚠️ **Atomic-ish** (all-or-compensated), but **NOT isolated.** |

> **A saga sacrifices ISOLATION to gain concurrency.**
> Other transactions **can and will** observe the saga's partial results.

**This is the fundamental trade, and the application programmer must reason about it.** If someone
can see a booked flight that's about to be cancelled, your application must be able to live with
that.

---

## 4. Compensating transactions — the heart of it

> **`Cᵢ` semantically undoes `Tᵢ`. It does NOT restore the database to the exact state before
> `Tᵢ` ran.**

**This is the distinction that carries the whole paper.**

A classical rollback is a **physical undo** — restore the old bytes, as if it never happened.
Impossible here, because other transactions have already read and built upon the committed value.

A compensation is a **semantic undo** — a *new forward transaction* that negates the *business
effect*:

| `Tᵢ` | `Cᵢ` | The state afterwards |
|---|---|---|
| Reserve a seat | Cancel the reservation | Seat is free — but a **cancellation fee** may have been charged, and a cancellation record exists |
| Debit an account | Credit the account | Balance restored — but the **statement shows both entries** |
| Increment inventory | Decrement inventory | Count restored — unless someone bought one in between |
| Send an email | Send a retraction email | **You cannot unsend the email.** The best you can do is apologize. |

**The email row is the honest limit of the technique.** The paper is clear-eyed about this:
compensation is only possible where the effect is **semantically reversible**. Some effects aren't.

### The properties compensations MUST have

**1. They must be written by the application programmer.**
Only the application has the **semantic knowledge** of what "undoing" means. The DBMS cannot infer
that the inverse of `reserve_seat` is `cancel_seat`. **This is not free — it is real work you must
do, per step.**

**2. They must never fail — or rather, they must be retried until they succeed.**
> **The system's guarantee depends on being able to run the compensations to completion.**

If `C₃` can just... fail, the saga is stuck in a half-done state with no way out, and the entire
model collapses. So compensations must be **retryable and effectively idempotent** — because the
recovery system *will* retry them after a crash.

**3. The data needed to compensate must be persisted.**
`C₃` needs to know *what* `T₃` did — the booking reference, the amount debited, the row ID. That
information has to survive a crash, which means it goes in the log (see below).

---

## 5. System support: the log and the Saga Execution Component

The paper proposes a **Saga Execution Component (SEC)** — a system component that drives sagas and
survives crashes.

### The log

The SEC writes records to the same durable log the DBMS already has:

```
  begin saga
  begin T₁ … end T₁
  begin T₂ … end T₂
  begin T₃ …            ← 💥 CRASH HERE
  ...
  abort saga  /  end saga
```

**On recovery, the SEC reads the log and decides what to do.** The log is what makes a saga
**durable across a crash of the process driving it** — which is exactly the thing an application-
level retry loop *cannot* give you.

### Two recovery strategies

**Backward recovery (compensate):**
> Run `Cⱼ … C₂ C₁` in reverse order. The saga is aborted; its effects are semantically undone.

**Forward recovery (roll forward):**
> **Don't compensate — finish the job.** Retry the failed subtransaction and continue to `Tₙ`.

**Forward recovery needs SAVE POINTS.** The SEC must be able to restore enough execution state to
**restart the saga from a checkpoint** rather than from the beginning. Save points are written to
the log.

- The paper notes save points can be **expensive** — the full execution state may be large.
- You can **mix** the strategies: compensate back to a save point, then roll forward from there.

**This is a genuinely underrated part of the paper.** Modern saga discussions obsess over
compensation and almost never mention **forward recovery**, even though for many workloads
("this payment gateway is down, retry it in 5 minutes") **rolling forward is obviously the right
answer** and compensating is absurd.

---

## 6. The trade-offs, stated plainly

| Gained | Given up |
|---|---|
| **Concurrency** — locks held only for each short `Tᵢ` | **Isolation** — partial results are visible |
| **No long-held locks; no blocking** | **Compensations must be hand-written** |
| **Cheaper recovery** — no 3-hour rollback | **Compensation is semantic, not exact** |
| **Progress under failure** (roll forward) | **Some effects can't be compensated at all** |
| **No global coordinator, no 2PC** | **Intermediate states can violate app-level invariants** |

---

## 7. What came AFTER the paper (don't attribute these to it)

Be precise about the boundary — it's a good way to show you actually read the thing:

**In the paper:** LLTs, subtransactions, compensating transactions, the saga contract, the SEC,
the log, save points, backward vs forward recovery, nested sagas (as a sketch).

**Not in the paper — later literature:**

- **Orchestration vs choreography.** The microservices distinction (a central coordinator drives
  the steps, vs services reacting to each other's events). The paper's SEC is closest to an
  *orchestrator*, but the terminology and the distributed framing are later.
- **Countermeasures for lost isolation** — semantic lock, commutative updates, pessimistic view,
  re-read value, version file, by-value. These are the standard toolkit for coping with a saga's
  missing "I," and they postdate the paper.
- **Compensatable / pivot / retriable step classification.** Structure your saga so all the
  compensatable steps come first, then **one pivot** (the point of no return), then only retriable
  steps. Extremely useful in practice, not in the original.
- **The Outbox pattern / CDC**, and the whole "how do I reliably emit the next saga step" problem —
  a distributed-systems concern the 1987 paper simply didn't have.

---

## 8. Sagas vs 2PC — the comparison that matters

| | **2PC** | **Saga** |
|---|---|---|
| **Atomicity** | ✅ True atomicity | ⚠️ **Eventual/semantic** — compensated, not undone |
| **Isolation** | ✅ Yes (locks held) | ❌ **None.** Partial state is visible. |
| **Locks** | Held **across all participants**, for the whole transaction | Held only **within each short `Tᵢ`** |
| **Blocking** | ❌ **Blocks forever** if the coordinator dies | ✅ Never blocks |
| **Coordinator** | **Single point of failure** | SEC/orchestrator — but it can just **resume from the log** |
| **Undo** | **Physical rollback** | **Semantic compensation** (hand-written) |
| **Failure amplification** | ❌ Yes — least-available component dominates | ✅ No — failures are contained per step |
| **Programmer burden** | Low | **High** — you write every compensation |

> **2PC buys atomicity + isolation with availability.**
> **A saga buys availability with isolation — and makes you write the undo yourself.**

**The deep point:** a saga doesn't *solve* the distributed atomic commit problem. **It dissolves it
by refusing to need it** — replacing one global atomic commit with N local ones plus a
forward-only recovery story. Same move as the Outbox pattern.

---

## 9. Practical guidance (the modern reading)

**Use a saga when:**
- The steps are **naturally independent** and each leaves the system in a *usable* state.
- **Compensation is semantically meaningful** ("cancel the booking," "refund the charge").
- You can **tolerate other actors seeing intermediate state**.
- The alternative would be 2PC/XA across services — which **amplifies failures** and holds locks
  across boundaries.

**Do NOT use a saga when:**
- **Intermediate state is unacceptable** — a ledger where money may never briefly exist in two
  places, or nowhere.
- **Steps are not compensatable** — you can't unsend the email, un-fire the missile, un-publish
  the tweet.
- **You need real isolation** and can't afford to build countermeasures.

**Engineering rules I'd actually enforce:**
1. **Order your steps: compensatable → pivot → retriable.** Everything reversible happens *before*
   the point of no return. After the pivot, you only roll forward.
2. **Every compensation must be idempotent and retried until success.** The recovery system *will*
   call it twice.
3. **Persist everything a compensation needs** — in the same local transaction as the step itself
   (this is where the Outbox pattern meets the saga).
4. **Prefer forward recovery where you can.** "The payment gateway is down; retry in 5 minutes" is
   almost always better than unwinding four completed steps.
5. **Orchestration over choreography** past ~3 steps. Choreography looks elegant in a diagram and
   becomes an unfollowable web of events in production; you cannot answer "what is the state of
   this saga?" without a coordinator.

---

## 10. Recall

- **The paper is about LONG-LIVED TRANSACTIONS in ONE DATABASE**, not microservices. LLTs hold
  locks for hours and destroy concurrency.
- **A saga = `T₁…Tₙ`, each an independently-committing ACID transaction, each with a compensating
  `Cᵢ`.**
- **Outcome contract:** either `T₁…Tₙ` all complete, **or** `T₁…Tⱼ` then `Cⱼ…C₁` **in reverse
  order**.
- **Sagas relax ISOLATION, not just atomicity.** Other transactions see partial results. That is
  *the* cost.
- **Compensation is SEMANTIC, not physical.** `Cᵢ` doesn't restore the prior state — it negates the
  business effect. **You cannot unsend an email.**
- **Compensations are written by the programmer, must be retryable, and must effectively never
  fail** — the whole guarantee rests on being able to run them to completion.
- **The SEC + a durable LOG** is what makes a saga survive a crash. Not an application retry loop.
- **Save points enable FORWARD recovery** — retry and finish, instead of unwinding. Underrated, and
  usually the right choice.
- **A saga doesn't solve distributed atomic commit — it makes you not need it.**
- **Orchestration/choreography, countermeasures, and pivot-step ordering are all LATER work.** Don't
  attribute them to Garcia-Molina & Salem.