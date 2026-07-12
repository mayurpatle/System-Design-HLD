# Cadence / Temporal — Production-Grade Orchestrated Saga

> Architecture notes. Sources: `temporalio/temporal/docs/architecture`, Temporal Server docs.
> Companion to `sagas-paper-notes.md`, `saga-pattern-microservices.md`,
> `distributed-transactions-2pc.md`.

---

## 0. The frame that makes everything click

Go back to Garcia-Molina & Salem (1987). Their saga needed two things to survive a crash:

1. A **Saga Execution Component (SEC)** — something that drives the steps.
2. A **durable log** — so that after a crash you can figure out where you were and either
  compensate backwards or roll forwards.

**Temporal is that. Built for real, at scale, with the orchestrator's state machine made durable.**

> **History Service + event history = the SEC + the log.**
> **Workflow = the saga. Activity = the subtransaction. Retries = forward recovery.
> Compensations = backward recovery.**

Everything below is engineering in service of one idea: **durable execution** — your orchestrator's
code keeps running correctly even though the process running it died.

### Lineage

- **Cadence** — built at **Uber** by Maxim Fateev and Samar Abbas (both previously on **AWS Simple
Workflow / SWF**). Still maintained by Uber.
- **Temporal** — a 2019 **fork** of Cadence by the same founders, now the commercially backed and
more widely adopted one.
- **Architecturally near-identical.** Main vocabulary difference: Cadence's **"decision tasks"** are
Temporal's **"workflow tasks."** If you read a Cadence doc, mentally rename it.

---



## 1. The premises (straight from the architecture README)

**Requirements:**

- **Workflows are defined as code**, in a normal SDK language (Java, Go, TypeScript, Python…).
**Not a YAML DSL. Not a BPMN diagram.** You write `if`, `for`, `try/catch`.
- **Durable execution must be guaranteed** — workflows must still execute correctly **in the face of
transient failures in server processes AND user-hosted processes.**
- **Scale to arbitrarily many concurrent workflow executions.**
- **User code executes in environments owned by the user.** (Your business logic never runs on
Temporal's servers — important for security, and it's why Workers exist.)

**Design decisions — and these two sentences are the entire system:**

> **1. The system functions via EVENT SOURCING: an append-only history of events is stored for each
> workflow execution, and all required workflow state can be recreated at any time by REPLAYING
> this history.**
>
> **2. User code is segregated into WORKFLOW definitions and ACTIVITY definitions. Workflow code
> must be DETERMINISTIC and have NO SIDE EFFECTS (with specific exceptions). Activity code must be
> either IDEMPOTENT or NON-RETRYABLE (i.e. at-least-once or at-most-once).**

**Read decision #2 twice.** It is the contract you sign, and every constraint that annoys you later
descends from it.

---



## 2. The Workflow / Activity split — the central abstraction


|                        | **Workflow**                                  | **Activity**                                            |
| ---------------------- | --------------------------------------------- | ------------------------------------------------------- |
| **What it is**         | The **orchestration logic** — the saga itself | A **single unit of work** — one side effect             |
| **Constraint**         | **Must be deterministic. No side effects.**   | Anything goes: I/O, network calls, DB writes            |
| **Why**                | It gets **replayed** — repeatedly             | It gets **recorded** — its *result* goes in the history |
| **On failure**         | Replayed from history; keeps running          | **Retried** per a retry policy                          |
| **Delivery semantics** | —                                             | **At-least-once** (or at-most-once if non-retryable)    |
| **Your obligation**    | Keep it deterministic                         | **Make it IDEMPOTENT**                                  |


**The mental model:**

> **The workflow is a pure function of its event history. The activities are where the impurity is
> quarantined.**

When a workflow calls an activity, **it doesn't execute it** — it *records the intent*, and the
result comes back as an event. On replay, the activity is **not re-executed**; the recorded result
is handed back. That is how replay is safe despite the world having side effects in it.

```java
// This is a workflow. It looks like ordinary blocking code.
// It is not. Every line is replayed from history on every resume.

public String createOrder(OrderRequest req) {
    orderActivities.createOrder(req);                    // ← activity: real side effect, recorded
    Workflow.sleep(Duration.ofDays(30));                 // ← DURABLE timer. Survives restarts.
    paymentActivities.charge(req.customerId, req.total); // ← retried automatically on failure
    return "done";
}
```

`Workflow.sleep(30 days)` **is the party trick.** The process hosting this can be killed, redeployed
five times, and thirty days later the code **resumes on the next line**. There is no cron, no
scheduler table, no polling loop. **That's durable execution.**

---



## 3. Cluster architecture

```
╔═══════════════ USER-HOSTED ═══════════════╗    ╔═════════ TEMPORAL CLUSTER ═════════╗
║                                            ║    ║                                     ║
║  User Application                          ║    ║  ┌──────────────┐                   ║
║   └─ SDK as gRPC client ──── start/signal ─╫───►║  │   FRONTEND   │  stateless        ║
║                                            ║    ║  └──────┬───────┘                   ║
║  Worker Process(es)                        ║    ║         │                           ║
║   ├─ Workflow code   ◄──── long-poll ──────╫────╫─►┌──────▼───────┐  ┌─────────────┐  ║
║   └─ Activity code   ────► commands/results╫───►║  │   HISTORY    │◄─┤  MATCHING   │  ║
║                                            ║    ║  │  (sharded)   │  │(task queues)│  ║
║  YOUR business logic runs HERE, never      ║    ║  └──────┬───────┘  └─────────────┘  ║
║  on Temporal's servers.                    ║    ║         │          ┌─────────────┐  ║
╚════════════════════════════════════════════╝    ║         │          │ INTERNAL    │  ║
                                                  ║         ▼          │ WORKER      │  ║
                                                  ║  ┌────────────────┐└─────────────┘  ║
                                                  ║  │  PERSISTENCE   │                 ║
                                                  ║  │ Cassandra /    │  + Elasticsearch║
                                                  ║  │ MySQL / PG     │    (visibility) ║
                                                  ║  └────────────────┘                 ║
                                                  ╚═════════════════════════════════════╝
```



### 3.1 Frontend Service

- **Stateless**, horizontally scalable. Add replicas → more throughput, **no coordination
overhead**.
- Hosts the **client-facing gRPC API** (port 7233 by default). Serves the SDK, the Web UI, and the
CLI.
- Essentially a **smart pass-through**: **rate limiting, authorization, validation, and routing** of
all inbound calls.
- Routes to History, Matching, the Worker service, the DB, and Elasticsearch.



### 3.2 History Service — the stateful core

> **This is where durability lives.** It maintains the **Mutable State** of each workflow execution,
> persists the **append-only event history**, and **drives the workflow state machine**.

- **Sharded.** Workflow executions are partitioned into **History Shards** (commonly **512** or
1024 in open-source configs). The **workflow ID is hashed** to pick its shard, and **all** events
for that execution are then owned by that shard.
- ⚠️ **The shard count is FIXED at cluster creation and CANNOT be changed later.** This is the single
most consequential operational decision you make. **Pick for peak load, not for today.**
- The **number of History *processes*** can range from 1 up to the number of shards, and **can**
change — shards rebalance across nodes. So: **processes scale, shard count doesn't.**
- Responsibilities per shard: **event history persistence, timer scheduling, state transitions,
activity scheduling**, and **enqueueing Workflow/Activity tasks into the Matching Service.**
- **Memory-intensive** — it caches workflow histories. Give these nodes RAM.
- It reports **NOT_SERVING** until it has acquired its initial shards, so the Frontend won't route
to a node that isn't ready.



### 3.3 Matching Service

- Manages the **Task Queues** that Workers poll. **One task queue holds tasks for many workflow
executions.** Task queues are themselves distributed/partitioned across the service.
- **The key optimization — "synchronous matching":** if a **long-polling worker is already waiting**
when a task arrives, the task is **dispatched immediately**, without a round-trip through
persistence. **The hot path skips the database entirely.** That's where the throughput comes from.



### 3.4 Internal Worker Service

Runs Temporal's **own** system workflows (archival, batch operations, etc.). *Temporal orchestrates
itself with Temporal.* Nice.

### 3.5 Persistence

**Cassandra, MySQL, PostgreSQL** (or SQLite for dev). Plus **Elasticsearch** for advanced
**visibility** (searching/listing workflows by custom attributes).

---



## 4. Tasks — the three kinds

Workers **long-poll** the Matching Service. What they get back:


| Task              | What the worker does                                                                                                                                                                                                      | Effect                                                          |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| **Workflow Task** | **Resume the workflow code** until it **blocks** (on a timer, an activity, a signal) or **completes**. Then send back a **sequence of COMMANDS** describing how to advance (e.g. "start a timer," "schedule activity X"). | Advances the workflow; new events are appended to history.      |
| **Activity Task** | **Execute the activity.** Send back the **result or failure**.                                                                                                                                                            | Result recorded as an event.                                    |
| **Query Task**    | Like a Workflow Task, but **only produces a query result.**                                                                                                                                                               | ⚠️ **Does NOT advance the workflow. Not persisted in history.** |


**The command/event distinction is worth internalizing:**

- **The worker issues COMMANDS** ("schedule this activity") — it *requests*.
- **The server writes EVENTS** ("ActivityTaskScheduled") — it *decides and records*.
- **The worker is never the source of truth.** The history is.

---



## 5. The event history

A workflow execution **is** its event history. Everything else is derived.

```
 1  WorkflowExecutionStarted        { input, taskQueue, workflowType }
 2  WorkflowTaskScheduled
 3  WorkflowTaskStarted
 4  WorkflowTaskCompleted           ← worker replied with commands
 5  ActivityTaskScheduled           { activityType: createOrder }
 6  ActivityTaskStarted
 7  ActivityTaskCompleted           { result }                   ← ★ the recorded side effect
 8  TimerStarted                    { 30 days }
 9  TimerFired                                                   ← 30 days later. Same log.
10  ActivityTaskScheduled           { activityType: charge }
11  ActivityTaskFailed              { retryable }
12  ActivityTaskScheduled           ← retry
13  ActivityTaskCompleted
14  WorkflowExecutionCompleted
```

**On resume, the worker replays the workflow function from line 1** and feeds it events 1..N. When
it re-reaches `orderActivities.createOrder(req)`, it doesn't call it — it **returns the recorded
result from event 7**. When it reaches `Workflow.sleep(30 days)`, it doesn't sleep — **event 9 says
the timer already fired.** The code races forward through history until it hits the point where
history runs out, and *then* it does real work again.

> **The workflow's local variables, its call stack, its loop counters — all of it is reconstructed
> by re-running the code. Nothing is serialized. The code IS the state machine, and history is the
> tape.**

That's the whole trick, and it's genuinely elegant.

---



## 6. Determinism — the contract, and the price

**Because the code is replayed, it MUST produce the same commands in the same order every time.**

### ❌ Forbidden in workflow code

```java
new Random().nextInt()          // different every replay
System.currentTimeMillis()      // different every replay
UUID.randomUUID()               // different every replay
Thread.sleep(...)               // not durable, and blocks the worker thread
new HttpClient().get(...)       // I/O — this is what ACTIVITIES are for
HashMap iteration order         // non-deterministic in some languages
static/global mutable state     // leaks across replays
```



### ✅ The deterministic replacements

```java
Workflow.currentTimeMillis()    // returns the time RECORDED IN HISTORY
Workflow.randomUUID()           // seeded deterministically from the run
Workflow.sleep(...)             // a durable timer, recorded as an event
Workflow.sideEffect(...)        // executes ONCE, records the result in history
Activity.doTheThing(...)        // anything impure goes here
```

**A "non-determinism error" means your code diverged from the recorded history** — it issued a
different command than the history says it issued. Temporal will refuse to proceed rather than
corrupt the execution.

### The consequences you will actually feel

**1. Versioning.** If you **change workflow code** while workflows are **in flight**, replay breaks —
the old executions replay against new code and diverge. Solutions:

- `Workflow.getVersion()` **/** `patched()` — branch on a version marker recorded in history, so old
runs take the old path and new runs take the new one.
- **Worker Versioning** — pin workflow versions to worker build IDs.
- **This is real, permanent engineering discipline.** Every workflow code change needs a review pass
asking *"does this break replay for in-flight executions?"*

**2. Sticky execution.** Replaying from event 1 on every single workflow task would be brutal. So
workers **cache the workflow in memory** and the server sends **only the new events**. Full replay
happens only on a cache miss (worker restart, eviction, rebalance).

**3.** `continueAsNew`**.** History **grows without bound** on long-running workflows, and there are hard
limits (order of tens of thousands of events / tens of MB). `continueAsNew` atomically **closes
the current run and starts a fresh run with a clean history**, carrying forward the state you choose.
Mandatory for infinite loops (e.g. a per-customer subscription workflow that runs forever).

---



## 7. The features that make it a *real* saga engine


| Feature                              | What it gives you                                                                                                                                                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Activity retry policies**          | Initial interval, **backoff coefficient**, max interval, max attempts, **non-retryable error types**. **This is forward recovery, first-class** — the thing the microservices saga literature underplays.                 |
| **Heartbeats**                       | Long-running activities heartbeat; a **heartbeat timeout** detects a dead worker quickly instead of waiting out a long start-to-close timeout. Heartbeats can carry **progress** so a retry resumes rather than restarts. |
| **Durable timers**                   | `sleep(30 days)`. No cron table. No scheduler.                                                                                                                                                                            |
| **Signals**                          | Durable, **external input into a running workflow** — recorded as events. ("The customer approved.")                                                                                                                      |
| **Queries**                          | **Read-only** introspection of workflow state. **Not persisted, doesn't advance the workflow.** "Where is order #4471?" — the question choreography can't answer.                                                         |
| **Child workflows**                  | Compose sagas out of sagas. (The 1987 paper's "nested sagas," shipped.)                                                                                                                                                   |
| **Cancellation scopes**              | Structured cancellation of in-flight work.                                                                                                                                                                                |
| **The** `Saga` **helper (Java SDK)** | Register compensations as you go; `saga.compensate()` runs them **in reverse order** on failure. **Literally the 1987 contract, as an API.**                                                                              |


```java
Saga saga = new Saga(new Saga.Options.Builder().build());
try {
    String orderId = orderActivities.create(req);
    saga.addCompensation(() -> orderActivities.cancel(orderId));   // register the undo

    String payId = paymentActivities.charge(req);
    saga.addCompensation(() -> paymentActivities.refund(payId));

    inventoryActivities.reserve(req);                               // ← if this throws...
} catch (ActivityFailure e) {
    saga.compensate();                                              // ← ...refund, then cancel
    throw e;
}
```

**Compare that to hand-rolling a choreographed saga across five services with an event per hop.**
The saga is *readable*. It's a function. You can unit-test it.

---



## 8. ★ The Outbox pattern, hiding inside the History Service

**This is the detail that ties your whole reading list together, and almost nobody points it out.**

The History Service faces exactly the problem from the saga pattern notes: **it must atomically
(a) append an event to history AND (b) tell the Matching Service to dispatch a task.** Two systems.
No 2PC.

**Its solution is the Transactional Outbox.** In one local DB transaction it:

1. appends to the **event history**,
2. inserts a row into an internal **transfer task** queue ("dispatch this activity"),
3. updates the workflow's **mutable state**.

Then **a separate background loop** reads the transfer-task rows and calls Matching to dispatch.
(Similar internal queues exist for **timers**, **replication**, and **visibility**.)

> **Temporal solves its own "atomically update the DB and publish a message" problem with the exact
> pattern it exists to help *you* implement.** One local transaction, an outbox table, an
> asynchronous relay, at-least-once dispatch, idempotent consumers.
>
> **Which is also why activities are at-least-once — the outbox relay can crash after dispatching
> and before marking the row done. The duplicate is structural, not sloppy.**

If you can say this in an interview, you have demonstrably read past the marketing page.

---



## 9. Trade-offs — the honest list

**Costs:**

- **Operational complexity.** A cluster (4 services) + a database + Elasticsearch. **Shard count is
fixed forever** — get it wrong and you rebuild the cluster.
- **The determinism constraint is a permanent tax.** Every workflow code change needs a
"does this break replay?" review. Versioning/patching APIs are correct but fiddly.
- **Latency overhead.** Every step is a DB write plus a task dispatch. This is **not** for
microsecond paths. It's for **business processes**, not for the request hot path.
- **History size limits** ⇒ `continueAsNew` discipline for long-lived workflows.
- **Coupling.** Your orchestration logic is now written against Temporal's SDK. Not portable.
- **Testing needs replay tests** (feed old histories to new code and assert no divergence).

**When it's the right call:**

- Multi-step business processes spanning services (order fulfillment, onboarding, payouts, KYC).
- Anything with **long waits** (wait 7 days for approval; retry the payment gateway for 3 days).
- Anywhere you'd otherwise build **a state machine + a jobs table + a cron + a retry loop + a dead
letter queue** — because **that's what you're actually rebuilding, badly.**

**When it's not:**

- Simple 2-step flows (an outbox + an idempotent consumer is enough).
- Latency-critical synchronous paths.
- Teams that can't absorb the operational + determinism learning curve.

---



## 10. How it maps onto everything else you've read


| Concept                             | Where it came from              | Temporal's realization                                                                                                                    |
| ----------------------------------- | ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **Saga**                            | Garcia-Molina & Salem 1987      | The **Workflow**                                                                                                                          |
| **Subtransaction** `Tᵢ`             | 1987                            | The **Activity**                                                                                                                          |
| **Compensating transaction** `Cᵢ`   | 1987                            | `saga.addCompensation(...)`, run in reverse                                                                                               |
| **Saga Execution Component (SEC)**  | 1987                            | The **History Service**                                                                                                                   |
| **The durable log**                 | 1987                            | The **event history**                                                                                                                     |
| **Save points + forward recovery**  | 1987 (and underused since!)     | **Retry policies + heartbeats** — first-class                                                                                             |
| **Orchestration (vs choreography)** | Richardson                      | The workflow **is** the orchestrator, and it's **durable**                                                                                |
| **"Where is this saga stuck?"**     | Choreography's fatal weakness   | **Queries** + the Web UI + the full event history                                                                                         |
| **Atomic DB-write + publish**       | Richardson's "issue to address" | **Transactional Outbox, inside the History Service**                                                                                      |
| **Event sourcing**                  | CQRS/ES literature              | The core persistence model                                                                                                                |
| **Replicated state machine**        | Raft/Paxos                      | **Deterministic function + ordered log ⇒ recoverable state.** *Exactly the same idea, applied to application code instead of a database.* |


**That last row is the deepest one.** Raft says: *feed the same commands, in the same order, to a
deterministic state machine, and every replica ends up in the same state.* Temporal says: *feed the
same events, in the same order, to a deterministic workflow function, and every replay ends up in
the same state.* **It is state machine replication, with your business logic as the state machine
and time as the thing being replicated across.**

---



## 11. Recall

- **Durable execution:** your orchestration code keeps running correctly even though the process
running it died.
- **Two design decisions define everything:** **(1) event sourcing** — an append-only history,
replayed to reconstruct state; **(2) Workflow (deterministic, no side effects) vs Activity
(idempotent or non-retryable).**
- **Four services:** **Frontend** (stateless gRPC gateway — rate limit, auth, route), **History**
(stateful, **sharded**, owns event history + mutable state + timers), **Matching** (task queues;
**synchronous matching** skips the DB when a worker is already polling), **Internal Worker**
(system workflows).
- ⚠️ **Shard count is fixed at cluster creation, forever.** Process count is not.
- **Three task types:** **Workflow Task** (resume code → return **commands**), **Activity Task**
(do the work → return result), **Query Task** (read-only, **not persisted**).
- **Worker issues COMMANDS; server writes EVENTS.** History is the source of truth.
- **Replay = re-run the workflow function against the history.** Recorded activity results are
handed back rather than re-executed. **The code is the state machine; history is the tape.**
- **Determinism is the price:** no `Random`, no wall clock, no UUID, no I/O in workflow code. Use
`Workflow.*` equivalents. **Code changes require versioning (*`*getVersion`**/**`patched`**).**
- **Sticky execution** avoids full replay; `continueAsNew` caps unbounded history.
- **Activities are AT-LEAST-ONCE ⇒ make them idempotent.** Non-negotiable.
- **Retries + heartbeats = the 1987 paper's forward recovery**, finally treated as first-class.
- **★ The History Service uses the Transactional Outbox internally** — one local transaction writes
the event *and* the transfer task; a relay dispatches to Matching. **That's why activities are
at-least-once.**
- **Temporal = state machine replication applied to your business logic.** Deterministic function +
ordered log ⇒ recoverable state. Same idea as Raft, different target.

