# Pattern: Transactional Outbox

**Source:** [Pattern: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html) — microservices.io (Chris Richardson)

*Also known as: Application events*

## Context

A service command often needs to both update business entities in the database **and** send messages/events to a message broker. This happens, for example, when a service participates in a **Saga** or publishes a **Domain event**. Both operations must happen atomically — otherwise the system ends up in an inconsistent state.

## Problem

How do you atomically update the database and send messages to a message broker?

## Why It's Hard (Forces)

- **2PC (two-phase commit) isn't a real option.** The database and/or broker may not support it, and coupling a service to both a DB and a broker transactionally is undesirable anyway.
- If the DB transaction commits, the message **must** be sent; if it rolls back, the message **must not** be sent.
- Messages must reach the broker **in the same order** they were produced by the service — even across multiple instances updating the same aggregate.

## Solution

The service first writes the message to the database, in the **same local transaction** as the business entity update. A separate process then relays those stored messages to the message broker.

Participants:

- **Sender** — the service producing the message
- **Database** — stores both business entities and the outbox
- **Message outbox** — a table (relational DB) or a per-record property (NoSQL) holding messages awaiting delivery
- **Message relay** — reads the outbox and publishes messages to the broker

## Resulting Benefits

- No 2PC required.
- Messages are guaranteed to be sent if and only if the transaction commits.
- Message order is preserved as sent by the application.

## Drawbacks / Issues

- **Error-prone by convention** — a developer can forget to write to the outbox when updating the database.
- **At-least-once delivery** — the message relay can crash after publishing but before marking the message as sent, causing it to republish on restart. Consumers must therefore be **idempotent** (e.g., by tracking processed message IDs) — the same requirement message consumers generally have anyway, since brokers can redeliver.

## Related Patterns

- **Saga** and **Domain event** are what create the need for this pattern.
- **Event sourcing** is an alternative solution to the same problem.
- Two patterns implement the Message relay itself:
  - **Transaction log tailing** (e.g., CDC tools like Debezium reading the DB's transaction log)
  - **Polling publisher** (periodically polling the outbox table)

## Takeaway

This is the theoretical/architectural definition of the pattern this repo's Orderflow project applies in practice — using Kafka + saga choreography, where transaction log tailing (Debezium-style CDC) is the relay mechanism referenced in the companion Debezium blog summary.