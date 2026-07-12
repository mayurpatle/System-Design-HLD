# Reliable Reprocessing & Dead Letter Queues with Apache Kafka

> **Source:** Uber Engineering Blog — *Building Reliable Reprocessing and Dead Letter Queues with Apache Kafka*
> **Author:** Ning Xia (Uber Insurance Engineering, later Payments) · Feb 16, 2018
> **URL:** https://www.uber.com/us/en/blog/reliable-reprocessing/
> **Canonical status:** The reference writeup on tiered retry topology in event-driven Kafka systems.

---

## TL;DR

Uber's Insurance Engineering team needed to retry failed messages **without blocking real-time traffic**. Their answer: instead of retrying in place, push a failed message to a **separate retry topic**, commit the original offset, and let a dedicated consumer group handle it later — with a growing delay at each tier. Messages that exhaust all retry tiers land in a **dead letter queue (DLQ)** for manual inspection. This gives non-blocking, decoupled, observable error handling.

The pattern powers **Driver Injury Protection**, an opt-in program running in 200+ cities that deducts a per-mile insurance premium on each trip.

---

## 1. The context: why retries are unavoidable

In distributed systems, failure is the normal case, not the exception — network errors, replication lag, and downstream outages all guarantee that some fraction of requests will fail. A system at Uber's scale has to *fail intelligently* rather than avoid failure.

The worked example in the article (a stand-in for the real insurance backend) is a **pre-order system** where a single `PreOrder` event must trigger two independent things:

1. **A payment** (the actual charge).
2. **A separate analytics/reporting record** (for real-time product analytics).

Two consumer groups subscribe to the same `PreOrder` Kafka topic, each running its own business logic against its own downstream service. This mirrors how one insurance premium event produces both a real charge *and* a reporting record.

---

## 2. The naive approach: client-level retries with backoff

The simplest retry strategy is a **feedback loop at the call site**: if a downstream call (e.g. `makePayment`) throws a timeout, keep re-calling it under a retry limit, optionally with backoff, until it succeeds or hits a stop condition.

This is fine in isolation but breaks down at scale.

---

## 3. Why simple retries fail at scale

### Problem A — Clogged / blocked batch processing
Kafka consumers process messages in batches and only advance the committed offset once a batch resolves. A message that **keeps failing** never lets the consumer commit past it, so:

- The bad message is re-consumed again and again.
- **New, healthy messages queue up behind it** and starve.
- The worst offenders — the ones that always exhaust the retry limit — are exactly the ones that consume the most time and resources.

This is effectively **head-of-line blocking** caused by a poison message.

### Problem B — Hard to get retry metadata
With in-place retries it's cumbersome to answer basic operational questions: *When was this retried? How many times? What was the payload?* That metadata is essential for diagnosis but isn't naturally captured.

---

## 4. The core idea: process retries in separate queues

The key move is to **redefine what "consumer success" means**.

- **Old definition:** success = the handler returned no error.
- **New definition:** success = the message reached a *conclusive result* — either it was handled correctly, **or** it was moved somewhere else to be handled separately.

Concretely, when a handler fails a message (after its in-place retries), the consumer:

1. **Publishes** the message to a dedicated **retry topic**.
2. **Returns `true`** to the original consumer.
3. The original consumer **commits the offset** and moves on.

The failed message is now *out of the main stream*, so the live batch keeps flowing. Failures no longer block successes.

> Example from the article: `msg_a` errors during handling → consumer publishes it to `payment_retry` → commits `msg_a`'s offset on the original `pre-orders` topic → immediately proceeds to the next message.

---

## 5. Building the retry ladder (tiered topology)

Retrying is then just "normal consumption" against a different topic:

- Each **retry topic** has its **own dedicated consumer group**, behaving like the main consumers but reading from the retry topic instead.
- To support **multiple retry attempts**, you chain **multiple retry topics**. Each tier's consumer, on failure, publishes the message **down to the next retry topic**.
- Errors "trickle down" the ladder — tier 1 → tier 2 → … → final tier.

### The Dead Letter Queue (end of the line)
The **DLQ is the terminal Kafka topic**. If the *last* retry tier still can't succeed, the message is published to the dead letter topic, where it waits for human intervention.

A well-designed DLQ must support three operations:

| Operation | Purpose |
|-----------|---------|
| **List**  | View the contents of the queue for inspection/diagnosis. |
| **Purge** | Clear out entries (e.g. after they're resolved or deemed unrecoverable). |
| **Merge** | Re-inject dead-lettered messages back into processing for a bulk re-run. |

A common implementation is a **CLI tool** backed by its own consumer using offset tracking. Crucially, **merged messages re-enter at the *first retry topic*, not the live topic** — so replayed failures can never interfere with real-time traffic.

---

## 6. Delays between tiers: the leaky-bucket / delayed-processing model

Retrying immediately, back-to-back, would just **amplify the spam of bad requests** and hammer an already-struggling dependency.

Instead, each retry tier enforces a **processing delay (timeout) that grows as the message steps down the ladder**. Because Kafka has no native delayed-delivery and consumers can't reorder, the delay is achieved by the consumer **blocking** until the timeout elapses.

This yields a **[leaky bucket](https://en.wikipedia.org/wiki/Leaky_bucket) flow-control pattern**: the blocking consumption controls the flow rate. An important consequence, in Uber's own framing:

> These aren't really "retry queues" — they're **delayed-processing queues**. Re-execution is **best-effort**: the handler runs *at least* after the configured timeout, but possibly later.

---

## 7. What the design buys you

**Unblocked batch processing** — Failures divert into their own channels, so successes in the same batch proceed immediately. Higher real-time throughput.

**Decoupling** — Independent work streams on the same event (payment vs. reporting) each get their *own* retry + DLQ flows. If reporting fails but payment succeeded, only reporting is retried/dead-lettered — payment is never needlessly re-run.

**Configurability** — Creating Kafka topics is essentially free, and all retry topics can share one schema. A single higher-level consumer class, driven by config, handles which topic to read, which topic to publish to on failure, and how long to delay. It also enables **error-type-aware routing**: transient issues (network flakiness) get retried, while deterministic bugs (null pointer exceptions, code defects) go **straight to the DLQ** because retrying can't fix them.

**Observability** — Splitting processing across topics makes an errored message's full path traceable: when it failed, how many times it retried, and the exact payload. Comparing production rates across the main topic vs. retry topics vs. DLQ feeds alerting thresholds and real uptime tracking.

**Flexibility** — Kafka is Scala/Java internally but offers clients in many languages (many Uber services use **Go**). Pairing with a serialization framework like **Avro** gives evolvable schemas — data-model changes need minimal rework.

**Performance & dependability** — Kafka's default **at-least-once** semantics protect business-critical data from loss, and its pull-based, parallel model delivers high throughput at low latency.

---

## 8. Caveats & tradeoffs (the "read the fine print" section)

- **Ordering is not global.** Kafka guarantees order *within a partition*, not across partitions — and this whole scheme reorders messages by design. The application **must tolerate out-of-order processing**.
- **Idempotency is mandatory.** At-least-once delivery means a message can be processed more than once, so **downstream consumers must be idempotent**. (True of any at-least-once distributed system.)
- **Topic sprawl.** A separate set of topics per work stream *per event type* can explode into a huge number of topics to manage. An alternative to these **count-based queues** is to **embed retry metadata (retry count, timestamp) into the message itself** — but that shifts complexity into how you schedule delays, since scheduling was previously handled implicitly by the queue ladder.
- **Mileage varies.** The benefits are real but use-case dependent.

---

## 9. Mental model / cheat sheet

```
                 ┌──────────────┐
   PreOrder ───▶ │ main consumer│ ──success──▶ done
    topic        └──────┬───────┘
                        │ fail
                        ▼
                 ┌──────────────┐   delay: t1
                 │ retry topic 1│ ──success──▶ done
                 └──────┬───────┘
                        │ fail
                        ▼
                 ┌──────────────┐   delay: t2 (> t1)
                 │ retry topic 2│ ──success──▶ done
                 └──────┬───────┘
                        │ fail
                        ▼
                       ...
                        │ fail (last tier)
                        ▼
                 ┌──────────────┐
                 │     DLQ      │  ← list / purge / merge
                 └──────┬───────┘
                        │ merge (manual)
                        └────────▶ back to retry topic 1
```

**One-line takeaways**
- Success = "conclusive result," not "no error."
- Divert-and-commit beats retry-in-place → no head-of-line blocking.
- Each tier = separate topic + separate consumer group + longer delay.
- Delays = blocking consumption = leaky-bucket flow control = *delayed-processing*, best-effort.
- DLQ = terminal topic with list/purge/merge; merges rejoin at tier 1, never live traffic.
- Route by error type: transient → retry, deterministic bug → DLQ immediately.
- Non-negotiables: tolerate reordering + idempotent consumers.

---

## 10. Connections & further reading

- **.NET reference implementation** of this exact topology (main / delayed / DLQ topics): `github.com/joelbraun/KafkaRetry`.
- **Kai Waehner** — *Error Handling via Dead Letter Queue in Apache Kafka*: broader survey (Kafka Streams, Kafka Connect, Spring, Parallel Consumer) with Uber, CrowdStrike, Santander, Robinhood case studies.
- **CrowdStrike's 3 DLQ best practices:** store errors in the right system (they use S3 / Tiered Storage), automate remediation, and document the business process so on-call engineers know the drill.
- **Modern Uber evolution:** the current production stack layers a **uForwarder Consumer Proxy** in front of brokers for 1,000+ consumer services, but the tiered-retry-into-DLQ topology with Avro schemas and leaky-bucket flow control remains the backbone.