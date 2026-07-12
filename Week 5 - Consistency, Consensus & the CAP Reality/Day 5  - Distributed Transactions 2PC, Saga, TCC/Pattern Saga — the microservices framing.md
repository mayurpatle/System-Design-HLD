# Pattern: Saga — the microservices framing

> Chris Richardson, microservices.io — `patterns/data/saga.html`
> The modern repurposing of Garcia-Molina & Salem (1987).
> Companion to `sagas-paper-notes.md` and `distributed-transactions-2pc.md`.

---

## 1. Context — why the problem exists at all

**It starts with a different pattern: Database per Service.**

Each service owns its own database. Nobody reaches into anyone else's tables. That's the whole
point — it's what gives you independent deployability and loose coupling.

**But then some business transactions span services**, and you have no local ACID transaction to
reach for.

**Richardson's running example:**

> An e-commerce store where customers have a **credit limit**. The application must ensure a new
> order **does not exceed the customer's credit limit.**
>
> `Order` lives in the **Order Service's** database.
> `Customer` (and their credit) lives in the **Customer Service's** database.
> **Different databases, different owners ⇒ no local ACID transaction.**

That's a genuine cross-service invariant, and it can't be enforced with a `BEGIN…COMMIT`.

## 2. Problem & Forces

**Problem:** How do you implement transactions that span services?

**Forces — and the page lists exactly one:**

> **"2PC is not an option."**

That's it. One line, no hedging. It's an *axiom* of the pattern, not a conclusion to be argued.
(The reasons live in the 2PC notes: coordinator SPOF, blocking on in-doubt, XA's log-on-local-disk
problem, failure amplification, and the fact that most modern brokers and NoSQL stores **don't even
support XA**.)

**Note the honesty in the framing:** the saga isn't the *ideal* solution. It's what you're left
with once the ideal solution is off the table.

---

## 3. Solution

> **Implement each business transaction that spans multiple services as a SAGA — a sequence of
> local transactions.**
>
> **Each local transaction updates its own database AND publishes a message/event that triggers the
> next local transaction in the saga.**
>
> **If a local transaction fails because it violates a business rule, the saga executes a series of
> COMPENSATING TRANSACTIONS that undo the changes made by the preceding local transactions.**

Note the precise trigger: *"fails because it **violates a business rule**."* That's a semantic
failure, not a crash — a crash gets retried; a rule violation gets compensated. Worth keeping
straight.

### The two coordination styles

| | **Choreography** | **Orchestration** |
|---|---|---|
| **Definition** | Each local transaction **publishes domain events** that trigger local transactions in other services | **An orchestrator object tells the participants** which local transactions to execute |
| **Decision-making** | **Distributed** — logic lives in the participants | **Centralized** — logic lives in the orchestrator |
| **Communication** | Events (pub/sub) | Asynchronous **command/reply** |

---

## 4. The two worked examples (memorize the shapes)

### 4.1 Choreography-based: Create Order

```
1. Order Service    receives POST /orders → creates Order in PENDING state
2. Order Service    emits  "Order Created"
3. Customer Service (event handler) attempts to RESERVE CREDIT
4. Customer Service emits an event with the outcome
5. Order Service    (event handler) APPROVES or REJECTS the Order
```

**Notice the shape:** nobody is in charge. Each service reacts to an event and emits another. The
saga's "state" exists only as the sum of the participants' states.

**Notice the PENDING state.** That's not incidental — it's a **semantic lock** (see §7). The Order
exists but is not yet real. It's the countermeasure for the missing isolation, hiding in plain
sight in step 1.

### 4.2 Orchestration-based: Create Order

```
1. Order Service    receives POST /orders → creates the "Create Order" SAGA ORCHESTRATOR
2. Orchestrator     creates an Order in PENDING state
3. Orchestrator     sends a  "Reserve Credit"  COMMAND to Customer Service
4. Customer Service attempts to reserve credit
5. Customer Service sends back a REPLY with the outcome
6. Orchestrator     APPROVES or REJECTS the Order
```

**The difference is the direction of knowledge.** In choreography, the Customer Service knows an
order was created and decides to act. In orchestration, the Customer Service knows **nothing** —
it just receives a `Reserve Credit` command and replies. **The saga logic is in one place.**

**Two things to spot:**
- **Commands + replies, not events.** Semantically different: a command names its recipient; an
  event doesn't care who's listening.
- **The orchestrator is a state machine**, and it typically lives *inside* the initiating service
  (here, the Order Service) rather than being a separate deployable.

---

## 5. Benefits

The page gives exactly one, and it's the one that matters:

> **It enables an application to maintain data consistency across multiple services WITHOUT using
> distributed transactions.**

---

## 6. Drawbacks — the two that define the pattern

### 6.1 No automatic rollback

> **"A developer must design compensating transactions that explicitly undo changes made earlier in
> a saga rather than relying on the automatic rollback feature of ACID transactions."**

**The word doing the work is *explicitly*.** ACID rollback is free and automatic. Compensation is
**code you write, per step, by hand** — and it's code that runs only on the unhappy path, which
means it's **code that is systematically under-tested**. This is a real, ongoing engineering tax,
not a one-time design cost.

### 6.2 Lack of isolation — the "I" in ACID

> **"The lack of isolation means there's a risk that the concurrent execution of multiple sagas and
> transactions can cause data anomalies. Consequently, a saga developer must typically use
> COUNTERMEASURES — design techniques that implement isolation. Careful analysis is needed to
> select and correctly implement the countermeasures."**

**This is the deep one, and it's exactly the same cost the 1987 paper identified.** A saga is
**ACD** — atomicity (eventually, via compensation), consistency, durability — **but not I**.

Other sagas and transactions **can see your half-finished saga**. Concrete anomalies:

- **Lost updates** — saga A overwrites a change made by saga B mid-flight.
- **Dirty reads** — someone reads an Order that's about to be rejected, and acts on it.
- **Fuzzy/non-repeatable reads** — a saga reads the same data twice and gets different answers.

**Notice the phrasing: "careful analysis is needed."** Richardson is warning you that this is not a
checklist — you have to reason about your specific anomalies. That's the honest thing to say, and
it's why sagas are harder than they look in a slide deck.

---

## 7. Countermeasures — the isolation toolkit

*(Referenced by the page, detailed in Chapter 4 of his book. Not on the pattern page itself.)*

Since you can't have real isolation, you **simulate enough of it** to prevent the anomalies you
actually care about:

| Countermeasure | What it does |
|---|---|
| **Semantic lock** | Mark the record with an **in-flight flag** (`Order.state = PENDING`, `APPROVAL_PENDING`). Other sagas see the flag and either **block, fail, or wait**. ⭐ *The most-used one — and it's already in the example above.* |
| **Commutative updates** | Design operations so **order doesn't matter** (`credit`/`debit` commute). Then interleaving is harmless. *(Pure CRDT thinking.)* |
| **Pessimistic view** | **Reorder the saga steps** to minimize the damage of a dirty read. E.g., don't increase available credit until you're sure — put the risky step later. |
| **Reread value** | **Re-read the record before updating it** and verify it's unchanged (an optimistic-lock / version check). If it changed, abort the saga. |
| **Version file** | **Record the operations** and apply them in a **reorderable** way — turning non-commutative ops into commutative ones after the fact. |
| **By value** | **Choose the mechanism per request, based on business risk.** Low-risk requests use a saga; **high-risk requests use a real distributed transaction.** A pragmatic escape hatch. |

**The one you'll actually reach for is the semantic lock**, and the `PENDING` state in the Create
Order example *is* one. Every saga you've seen with a `PENDING`/`RESERVED`/`APPROVAL_PENDING` state
is applying this countermeasure — most people just don't know it has a name.

---

## 8. The two issues you must address

The page flags these as open problems that the pattern **creates** and you must solve:

### 8.1 Atomically updating the database AND publishing a message

> **"In order to be reliable, a service must atomically update its database *and* publish a
> message/event. It cannot use the traditional mechanism of a distributed transaction that spans
> the database and the message broker."**

**The saga's entire mechanism depends on this**, and it is the exact chicken-and-egg problem: the
saga exists *because* 2PC is off the table, and then each saga step needs... an atomic write across
a DB and a broker.

**The two sanctioned answers (both linked as related patterns):**
- **Transactional Outbox** — insert the event into an `outbox` table **in the same local
  transaction** as the business write; a relay (polling or **CDC / transaction log tailing**) ships
  it to the broker.
- **Event Sourcing** — the event *is* the state. Appending it is the write. Nothing to reconcile.

> **You cannot implement a saga correctly without solving this first.** Any saga tutorial that has
> a service call `repository.save()` and then `kafkaTemplate.send()` is **broken** — it will drop
> or duplicate steps on crash.

### 8.2 The synchronous client problem

The client sends a **synchronous** `POST /orders`, but the saga is an **asynchronous** flow. How
does the client learn the outcome?

**Three options, each with different trade-offs:**

| Option | Trade-off |
|---|---|
| **Hold the response** until the saga completes (until `OrderApproved`/`OrderRejected` arrives) | Simplest for the client; but **you're now holding an HTTP connection open across a distributed workflow.** Doesn't scale, and times out. |
| **Return `orderID` immediately; client POLLS** `GET /orders/{orderID}` | Robust, dead simple, works everywhere. **Chatty**, and adds latency. |
| **Return `orderID` immediately; PUSH the result** via **WebSocket / webhook** | Best UX; **most infrastructure.** |

**The under-appreciated point:** *adopting sagas changes your API's contract.* `POST /orders`
stops meaning *"the order was created"* and starts meaning *"an order creation was **initiated**."*
That has to be visible to your clients. You can't hide an asynchronous workflow behind a
synchronous façade and pretend nothing changed.

---

## 9. Choreography vs Orchestration — how to actually choose

*(Synthesizing the page with Richardson's broader writing.)*

### Choreography

✅ **Pros**
- **Simple.** No extra moving part; no orchestrator to build, deploy, or operate.
- **Loose coupling.** Services don't know about each other, only about events.
- Good for **simple sagas** (2–3 participants).

❌ **Cons**
- **Cyclic dependencies.** Services end up subscribing to each other's events, and the "loose
  coupling" quietly becomes a knot.
- **No single place to see the saga.** The workflow is an **emergent property** of the code in N
  services. You cannot read it anywhere.
- **"How do I know when it's done?"** — Richardson's own framing of the core weakness. You only know
  it completed because **the last thing happened** and emitted a terminal event (`OrderApproved` /
  `OrderRejected`). You must monitor for that.
- **Debugging is brutal.** "Where is order #4471 stuck?" has no direct answer.
- **Risk of every service needing to know about every event** as the saga grows.

### Orchestration

✅ **Pros**
- **The saga logic is in ONE place** — readable, testable, versionable. It's a **state machine**.
- **You can answer "what is the state of this saga?"** — because the orchestrator persists it.
- **Participants are dumb.** The Customer Service just handles `Reserve Credit`; it doesn't know a
  saga exists. **Far less coupling in practice**, despite appearances.
- Cyclic dependencies are avoided.

❌ **Cons**
- **An extra component** to build and operate.
- **Risk of centralizing too much business logic** in the orchestrator, turning participants into
  anemic CRUD services (the "smart orchestrator, dumb services" anti-pattern).

### The rule I'd actually apply

> **Choreography for 2–3 steps. Orchestration for anything more.**

The moment you cannot draw the saga on a whiteboard from memory, you need a state machine that
persists its state — because you will be paged at 3 AM asking *"where is this order stuck?"* and
**choreography has no answer to that question.**

---

## 10. Related patterns (the map)

```
        Database per Service
                 │  creates the need for
                 ▼
              SAGA
                 │  requires atomic "update DB + publish event"
                 ▼
      ┌──────────┴──────────┐
Transactional Outbox    Event Sourcing
      │
      └── Transaction Log Tailing (CDC / Debezium)
```

Also noted: the **Command-side replica** is an *alternative* that can **replace a saga step that
merely queries data** — if a step only *reads* from another service, you may not need a saga step
at all; replicate the data instead. **Good instinct: not every cross-service interaction needs a
saga.**

---

## 11. 1987 vs 2019 — what changed and what didn't

| | **Garcia-Molina & Salem (1987)** | **Richardson / microservices** |
|---|---|---|
| **The problem** | **Long-lived transactions** hold locks for hours, killing concurrency | **Database-per-service** means no ACID transaction spans services |
| **The unit** | Subtransactions in **one DBMS** | **Local transactions in different services' databases** |
| **Why not the alternative?** | 2PL is too slow for LLTs | **"2PC is not an option"** |
| **Coordination** | The **SEC** + a log | **Choreography** (events) or **orchestration** (commands) |
| **The step trigger** | The SEC drives it | Each local txn **publishes a message/event** |
| **Isolation loss** | ✅ Identified | ✅ Identified — and named: **countermeasures** |
| **Forward recovery / save points** | ✅ **A first-class option** | ⚠️ **Barely discussed** |
| **The atomic DB+broker write** | Not a problem (one DBMS!) | **THE central implementation problem** ⇒ Outbox / Event Sourcing |

**The two genuinely new problems in the microservices version** — and they exist *only* because the
steps are now in different processes with a network between them:

1. **Atomically writing to your DB and publishing the event** (⇒ Outbox / CDC / Event Sourcing).
2. **Telling the synchronous caller what happened.**

**The thing the modern framing quietly LOST:** the 1987 paper's **forward recovery + save points**.
Modern saga discourse is almost entirely about compensation, even though for transient failures
("the payment gateway is down") **retrying forward is obviously correct** and unwinding four
completed steps is absurd. Most production saga frameworks do support retries — but the *pattern
literature* doesn't emphasize it, and people build compensation-only sagas as a result.

---

## 12. Recall

- **Context: Database per Service ⇒ no cross-service ACID transaction.** The saga exists to fill
  that hole.
- **Forces: "2PC is not an option."** Stated as an axiom.
- **Saga = a sequence of local transactions, each publishing a message/event that triggers the
  next.** Business-rule failure ⇒ run compensating transactions.
- **Choreography = events, distributed logic.** **Orchestration = commands + replies, centralized
  state machine.**
- **Two drawbacks: (1) no automatic rollback — you hand-write every compensation; (2) NO ISOLATION**
  ⇒ concurrent sagas cause anomalies ⇒ **countermeasures**.
- **Countermeasures:** semantic lock (⭐ the `PENDING` state), commutative updates, pessimistic view,
  reread value, version file, by value.
- **You MUST solve "atomically update DB + publish event" first** ⇒ **Transactional Outbox** or
  **Event Sourcing**. A `save()` followed by a `send()` is a broken saga.
- **The client-outcome problem:** hold the response / poll / push. **`POST /orders` now means
  "initiated," not "created."**
- **Choreography ≤3 steps; orchestration beyond that.** *"Where is this order stuck?"* is a question
  choreography cannot answer.
- **A saga is ACD, not ACID.**