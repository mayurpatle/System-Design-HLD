# Reliable Microservices Data Exchange With the Outbox Pattern

**Source:** [Reliable Microservices Data Exchange With the Outbox Pattern](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/) — Gunnar Morling, Debezium Blog (Feb 2019). The canonical writeup on the pattern.

## The Problem: Dual Writes

In an event-driven microservices architecture, a service often needs to do two things when handling a request:

1. Update its own database (its local state).
2. Publish an event/message to a broker (e.g. Kafka) so other services can react.

Doing both independently — one DB transaction plus one separate call to the broker — is unsafe. There's no way to guarantee both succeed or both fail together. If the DB commit succeeds but the message send fails (or vice versa), services drift out of sync. This is known as the **dual writes problem**, and it can't be solved by simply reordering the two operations or wrapping them in a distributed (XA) transaction, which most modern brokers and databases don't support well in practice.

## The Solution: Transactional Outbox

Instead of sending the message directly, the service writes the event into an **outbox table** in its own database, as part of the *same local transaction* that updates its business data. Since both writes go through one ACID transaction, they either both commit or both roll back — there is no window where one happens without the other.

A separate process then reads the outbox table and relays those events to the message broker asynchronously.

### How the relay works: Change Data Capture (CDC)

Rather than polling the outbox table (which adds latency and load), the pattern uses **Debezium** to tail the database's transaction log (e.g. MySQL binlog, Postgres WAL) via CDC. Whenever a row is inserted into the outbox table, Debezium picks it up near-instantly and publishes it to Kafka — no extra load on the source database, and no risk of missing events even if the relay process crashes and restarts.

### Typical outbox table schema

| Column | Purpose |
|---|---|
| `id` | Unique event ID |
| `aggregatetype` | The type of business entity the event relates to (e.g. `Order`) — used to route to the correct Kafka topic |
| `aggregateid` | The entity's ID (e.g. order ID) — used as the Kafka message key, preserving per-entity ordering |
| `type` | The event type (e.g. `OrderCreated`) |
| `payload` | The event body, typically JSON |

Debezium's **Outbox Event Router** (a single message transform/SMT) reads these rows and automatically routes each event to a topic derived from `aggregatetype`, using `aggregateid` as the partitioning key.

## Why It Matters

- **Atomicity** — no dual-write inconsistency; the DB commit and the "intent to publish" are one operation.
- **Read-your-own-writes** — the originating service still reads its own DB directly for consistency, while other services get eventual consistency via the outbox events.
- **Reliability** — CDC guarantees at-least-once delivery even across crashes, without polling overhead.
- **Decoupling** — downstream services don't need direct access to the source service's database or internal APIs; they just consume events.

## Relevance to Saga Choreography

The outbox pattern pairs naturally with **choreographed sagas**: each service commits its local transaction and its outbox event together, and Kafka reliably fans that event out to the next service in the saga. This avoids a fragile in-process "commit DB, then call Kafka producer" step that could otherwise leave the saga's event chain broken if the producer call fails after the DB commit.

## Trade-offs to Consider

- Requires running Debezium/Kafka Connect infrastructure alongside the service.
- Adds an extra table and slightly more write volume per transaction.
- Consumers must handle at-least-once delivery (i.e., events may be delivered more than once — idempotent consumers are needed).