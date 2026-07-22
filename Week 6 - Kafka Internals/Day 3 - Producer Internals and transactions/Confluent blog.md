# Exactly-Once Semantics Are Possible: Here's How Kafka Does It — Detailed Summary

**Source:** Confluent blog, **Neha Narkhede** (with Guozhang Wang), June 30, 2017 — published the week Kafka 0.11 shipped. This is the *announcement and argument* companion to KIP-98: where the KIP is the mechanism, this post is the framing — what "exactly-once" precisely means, why the industry said it was impossible, why it isn't, and what the guarantee does and doesn't cover. It's the piece to read for the *vocabulary and boundaries* of EOS.

---

## 1. The provocation: "exactly-once is impossible"

The post opens by acknowledging the elephant: much of the distributed-systems community held that exactly-once delivery is one of the hardest problems in the field — some flatly called it impossible. The response isn't to deny the difficulty but to reframe it: exactly-once is achievable **when the messaging system and the applications cooperate** under a precisely scoped guarantee, and Kafka 0.11 ships that after 1+ year of engineering (design started at LinkedIn ~2014).

## 2. Messaging semantics, precisely defined

The failure that creates the whole problem: a producer sends, and the **ack never arrives**. Was the message written (broker crashed after write, before ack) or not (crashed before write)? The producer *cannot know*. Its policy choice defines the semantics:

- **At-least-once:** retry on missing ack → possibly duplicated in the log → duplicated downstream work and wrong results (the pre-0.11 Kafka default).
- **At-most-once:** don't retry → possibly lost.
- **Exactly-once:** even with retries, the message reaches the end consumer once. Explicitly called "the most desirable but most poorly understood" guarantee — misunderstood because it's not a broker-only property: rewind your consumer and you'll re-receive everything; **system + application must cooperate**.

## 3. The three failure classes any EOS design must survive

1. **Broker failure** — handled by replication: with n replicas, tolerate n−1 failures; a write acked by the leader is replicated to the ISR.
2. **Producer→broker RPC failure** — the ambiguous-ack problem above; forces retries; retries create duplicates. This is what **idempotence** kills.
3. **Client failure** — and its subtle twin question: *is it actually dead, or just paused/partitioned?* A **zombie producer** must be fenced (its writes discarded); a replacement consumer must resume from a safe point, which requires **consumed offsets kept in sync with produced output**. This is what **transactions + transactional.id** kill.

## 4. The three-layer solution (the post's core structure)

### Layer 1 — Idempotent producer (`enable.idempotence=true`)

Exactly-once, in-order, per partition. The mechanism is explicitly analogized to **TCP sequence numbers** — but with the crucial upgrade: TCP's guarantee lives only inside one transient in-memory connection, while Kafka's sequence numbers are **persisted in the replicated log itself**, so a new leader after failover still recognizes a resend as a duplicate. Overhead: a few numeric fields per batch — negligible.

### Layer 2 — Transactions (atomic multi-partition writes)

A producer sends a batch of messages spanning multiple partitions such that **all become visible to consumers or none ever do** — and, decisively, **consumer offsets can be committed inside the same transaction** as the processed output, which is what enables end-to-end exactly-once. Requirements: the transactional producer APIs, a unique **`transactional.id`** (providing *continuity of transactional state across restarts* — i.e., fencing), and a consumer-side choice via `isolation.level`: **read_committed** (see transactional data only after commit) vs **read_uncommitted** (legacy behavior, everything in offset order). Note the practical detail: a partition can freely interleave transactional and non-transactional messages.

### Layer 3 — Exactly-once stream processing (`processing.guarantee=exactly_once` in Streams)

The "real deal" and the reason the feature exists: a **one-config knob** that makes the whole **read-process-write** cycle — input offsets, state-store changelogs materialized to Kafka, and output records — atomic per processing unit. The post's formal statement: *for each received record, its processed results are reflected exactly once, even under failures.*

## 5. What exactly-once stream processing really means (the intellectual heart)

This is the part of the post nothing else in the reading list covers as carefully:

- **Correctness under crash-recovery:** the question is "does my app get the right answer if an instance crashes mid-processing?" Recovery must resume in **exactly the pre-crash state** — no missed inputs, no duplicated outputs. And it's not just duplicates: a duplicate in an upstream *count* stage produces a **wrong number** downstream — errors compound through a topology.
- **The deterministic definition:** for deterministic logic, EOS means the output of read-process-write equals what it would be **if each message were seen exactly once, as in a failure-free run**.
- **The non-deterministic refinement** (the post's most subtle idea): if processing consults an external, changing condition (a service, a DB updated out of band), two runs can *legitimately* differ. EOS then means the output belongs to the **set of legal outputs** — the outputs producible from legal values of the non-deterministic input. Failure-recovery must not produce something *outside* that set (like a double-count).
- **The scope disclaimer, stated plainly:** the guarantee covers Kafka Streams' **internal processing only** — Kafka in, Kafka state, Kafka out. RPC side effects to remote stores, or custom clients bypassing the framework, are **not** covered. The accompanying claim: this closed-loop design is exactly *why* Streams' guarantee is stronger than engines that materialize state in external systems — those can rewind Kafka offsets on recovery but **cannot roll back the external state**, so non-idempotent state updates go wrong.

## 6. "Does it actually work?" — the engineering-credibility section

- **Design rigor:** a 60-page design doc, nine months of public review that materially changed the design — e.g., consumer-side buffering of transactional reads was replaced by **smarter server-side filtering** (the aborted-transaction index) to avoid a big performance hit.
- **Leverage of Kafka's own primitives** (the elegant part): the transaction log **is a Kafka topic** (inheriting durability); the **transaction coordinator** runs in the broker and inherits Kafka's leader election for failover; Streams state and offsets are *already* Kafka topics, so folding them into transactions is natural. EOS is built *out of* the log abstraction, not bolted onto it.
- **Testing:** 15,000+ LOC of tests, nightly distributed **chaos tests** — full clusters, transactional produce/consume under load, hard-killing clients and servers, verifying nothing lost or duplicated.
- **Performance (the numbers worth quoting):** with 1 KB messages and 100 ms transactions, transactional producer throughput is only **~3% below** an acks=all in-order producer and ~20% below the loose default; idempotence overhead is **negligible**. Streams EOS: 15–30% degradation at a 100 ms commit interval, **~zero at 30 s** — transaction cost is **per transaction, not per message**, so `commit.interval.ms` is the throughput↔latency dial (read_committed consumers wait for commit). And the kicker: the **message-format v2 rework** (batch-level headers, varint/relative encoding — e.g., a 7×10-byte batch shrinks ~35%) made Kafka **faster for everyone**: up to +20% producer and +50% consumer throughput on small messages, *even with EOS off*.

## 7. "Is this magical pixie dust?" — the honest closing

**No.** EOS is end-to-end and the application must not break it: plain consumer-API users must still commit application state **concordant with offsets**. Stream processing is the near-magic case *because it's a closed system* — input, state, and output all live in Kafka, so one config suffices — and getting data *out* of Kafka with the same guarantee requires an exactly-once-capable connector. (Follow-ups pointed to: KIP-98, KIP-129, the design doc, and the "Transactions in Apache Kafka" deep-dive post.)

---

## 8. Important concepts (highlight reel)

- **The ambiguous ack** — the single primitive failure from which all delivery-semantics taxonomy flows; retry policy = semantics.
- **"TCP, but persisted in the log"** — the best one-line mental model for the idempotent producer, and the detail that distinguishes it: dedup state survives leader failover because it's replicated log data.
- **Zombies and the dead-vs-paused problem** — you can't distinguish a crashed client from a GC-paused one, so correctness requires *fencing* the old incarnation, not detecting death. `transactional.id` = continuity of identity across restarts.
- **Offsets inside the transaction** — the move that converts "atomic writes" into "atomic *processing*": consumed-position and produced-output become one fact.
- **read_committed / read_uncommitted** — visibility is a *consumer choice*; committed data has waited for the transaction to resolve (hence latency ↔ commit-interval coupling).
- **EOS for deterministic vs non-deterministic logic** — "same as a failure-free run" vs "within the set of legal outputs": the precise vocabulary for what's promised.
- **Closed-system principle** — the guarantee's power comes from its boundary: Kafka-in/Kafka-state/Kafka-out. External side effects need their own atomicity (connectors, outbox, idempotent sinks).
- **Amortized transaction cost** — overhead is per-transaction (~1 marker write per partition + coordinator records), so batching/commit-interval tuning is the whole performance game.
- **Format v2 as a free lunch** — the EOS release also sped up non-EOS Kafka; a case study in paying down design debt while adding features.

## 9. Real-world use cases — where it's used

**Directly:**

- **Kafka Streams / ksqlDB applications with one config:** fraud scoring, running balances, real-time aggregation and joins — anywhere a crash-restart must not double-count. This post is the canonical justification for `processing.guarantee=exactly_once(_v2)` in production.
- **Consume-transform-produce services** (plain clients): a payments-pipeline stage that reads `payment.authorized`, computes, and emits `ledger.entry` with offsets in the transaction — the double-charge killer. In a payments-domain portfolio build (PaySwitch/LedgerCore-style), quoting this post's deterministic-vs-non-deterministic framing and the closed-system boundary is exactly the level of precision interviewers probe for.
- **Kafka→Kafka replication/copy jobs** and **exactly-once connectors** carrying the guarantee to external sinks.
- **The engineering-culture template:** the design-review + chaos-testing + benchmark-publication process described here is a reusable blueprint for how to ship a correctness feature credibly — worth citing when asked "how would you validate a distributed-systems feature?"

**As boundaries you must know (where NOT to claim it):**

- Side-effecting into external systems (email sends, REST calls, DB writes) from a processor — **not covered**; use idempotent sinks, the outbox pattern, or exactly-once connectors.
- Cross-system distributed transactions — Kafka transactions are not XA.

## 10. TL;DR

The 2017 announcement post that defined the terms of Kafka EOS: delivery semantics are the producer's retry policy under the **ambiguous ack**; Kafka 0.11 layers **idempotence** (TCP-style sequence numbers, but persisted in the replicated log), **transactions** (atomic multi-partition writes with offsets committed alongside output, zombie-fenced via `transactional.id`, exposed to consumers through `read_committed`), and **exactly-once Streams** (one config making read-process-write atomic). Its lasting intellectual contributions: the precise definitions of EOS for **deterministic** ("as if failure-free") and **non-deterministic** ("within the set of legal outputs") processing, the **closed-system boundary** (Kafka-in/state/out — external side effects excluded, which is also *why* the guarantee is strong), and the receipts — chaos testing, ~3% transactional overhead, and a message-format rework that made Kafka faster even for non-EOS users. Read KIP-98 for the gears; read this for what the machine promises and where the promise ends.