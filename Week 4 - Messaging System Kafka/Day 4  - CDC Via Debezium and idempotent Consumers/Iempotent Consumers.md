# Idempotent Consumer (Idempotent Receiver) — Summary

*Pattern by Chris Richardson, from **Microservices Patterns** / [microservices.io](https://microservices.io/patterns/communication-style/idempotent-consumer.html). Commonly called "Idempotent Receiver" — Richardson's pattern name is "Idempotent Consumer."*

## Context
Enterprise applications usually rely on a message broker that guarantees **at-least-once delivery** — the broker will re-deliver a message even when errors occur. The side effect is that a consumer can be invoked **more than once for the same message**.

Therefore a consumer must be **idempotent**: processing the same message repeatedly must produce the same outcome as processing it once. A non-idempotent consumer causes bugs — e.g., a consumer of an `AccountDebited` message that subtracts the debit from the balance would compute the wrong balance if it ran twice.

## Problem
**How does a message consumer handle duplicate messages correctly?**

## Solution
Make the consumer idempotent by **recording the IDs of already-processed messages in the database**. When a message arrives, the consumer checks the database and **detects and discards duplicates**.

Two places to store the processed IDs:

1. **A dedicated `PROCESSED_MESSAGES` table** — a separate table just for tracking IDs.
2. **Inside the business entities** the consumer creates or updates — track the ID alongside the data itself.

### How the `PROCESSED_MESSAGES` table approach works
1. The message handler **starts a database transaction**.
2. It **inserts the message's ID** into the `PROCESSED_MESSAGE` table.
3. The table's **primary key is `(subscriberId, messageId)`**, so if the message was already processed, the `INSERT` **fails** (primary-key violation).
4. On that failure, the handler **rolls back the transaction and ignores the message** — safely skipping the duplicate.

Because the duplicate check and the business update happen in the **same transaction**, tracking and processing stay consistent: either both commit or neither does.

## Key takeaways
- At-least-once delivery makes duplicates inevitable, so idempotency is a requirement, not an optimization.
- Enforce it with a **unique primary key on the message ID** and let the database reject duplicates atomically.
- Storing IDs **within business entities** avoids a separate table, while a **dedicated table** keeps tracking generic and reusable across consumers.

## See also
- The [Eventuate framework](https://eventuate.io) implements this pattern.
- Chris Richardson's [blog post on the pattern](https://microservices.io/post/microservices/patterns/2020/10/16/idempotent-consumer.html).

*Source: [microservices.io — Pattern: Idempotent Consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html)*