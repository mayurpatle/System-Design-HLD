# KIP-98: Exactly-Once Delivery and Transactional Messaging — Detailed Summary

**Source:** Apache Kafka wiki (cwiki.apache.org) + the accompanying ~60-page Google-Docs design document. Authored by **Jason Gustafson, Guozhang Wang, Apurva Mehta, Sriram Subramanian** et al. **Shipped in Kafka 0.11.0.0 (mid-2017)** — the largest single feature Kafka had added to that point, and the foundation for Kafka Streams EOS (KIP-129) and everything "exactly-once" since. It introduces two intertwined capabilities: the **idempotent producer** and **transactions** (atomic multi-partition writes + transactional offset commits).

**The promise:** every acknowledged message write is persisted **exactly once — no duplicates, no loss — even across client retries, producer crashes, and broker failures**; and a set of writes spanning multiple partitions either all become visible or none do.

---

## 1. Motivation: why at-least-once wasn't enough

Pre-0.11 Kafka gave at-least-once by default: if a produce response was lost (network blip, broker failover), the producer's only safe move was to **resend — possibly writing a duplicate**, and with `max.in.flight > 1`, retries could also **reorder** batches. Downstream, every consumer had to be hand-built idempotent (dedup tables, upserts keyed on business IDs), which pushes correctness burden onto every application.

Worse, the canonical streaming pattern — **consume → process → produce** — has a second failure window that idempotence alone can't fix: a crash *between* producing output and committing input offsets means the input gets reprocessed → duplicated output (or, with the opposite ordering, lost output). Since a stream topology is a DAG of processors chained by topics, errors **compound multiplicatively across stages**.

The explicitly stated primary use case: make **Kafka Streams** a correct stream-processing engine — a transaction must atomically cover the input offsets, the state-store changelog writes, and the output messages. That goal drives every design tradeoff in the KIP (including the `sendOffsetsToTransaction` API).

## 2. Part One: The Idempotent Producer (duplicates from retries, solved)

**Key concepts:**

- **Producer ID (PID):** every producer gets a unique, broker-assigned ID via a new `InitPidRequest`. For non-transactional producers it's transparent and ephemeral (new PID per session).
- **Sequence numbers:** the producer stamps every batch with a **per (PID, topic-partition) monotonically increasing sequence number**, starting at 0.
- **Broker-side dedup:** each partition leader tracks the highest sequence per PID. An arriving batch is accepted only if its sequence is **exactly last+1**; a lower/equal sequence is a **duplicate → acknowledged but discarded**; a gap → `OutOfOrderSequenceException` (a real correctness signal, surfaced to the app).
- The (PID, epoch, base sequence) triple is **written into the log** as part of the new message format, so dedup state survives leader failover — the new leader rebuilds producer state from the log itself. That's what makes the guarantee hold *across broker failures*, not just per-connection.

Result: **exactly-once, in-order delivery per partition within a producer session**, with retries and multiple in-flight requests. Enabled by `enable.idempotence=true` (later made the default by KIP-185/KIP-679). Cost: a few bytes per batch — effectively free.

**Limitation (by design):** the plain idempotent producer's PID changes on restart, so it cannot dedup *across producer sessions*, and it says nothing about atomicity across partitions. That's what transactions add.

## 3. Part Two: Transactions (atomic multi-partition writes)

### 3.1 The cast of new concepts

- **TransactionalId (`transactional.id`):** a **user-provided, stable identity** that survives producer restarts. This is the crucial jump: it lets the *new* incarnation of a producer be linked to (and fence off) its *old* incarnation. With it comes a **producer epoch**: `InitPidRequest` with a TransactionalId returns the same PID with a **bumped epoch**, and brokers reject writes from older epochs.
- **Zombie fencing:** the failure it kills — a producer instance stalls (GC pause, network partition), a replacement spins up, then the "dead" one wakes and keeps writing. Its stale epoch makes every subsequent write/commit fail. Without fencing, exactly-once is impossible in any system with automatic restarts; this is KIP-98's answer to distributed systems' split-brain for writers.
- **Transaction Coordinator:** a module in every broker (analogous to the group coordinator); a producer's coordinator is chosen by hashing its TransactionalId onto a partition of the...
- **Transaction Log (`__transaction_state`):** an internal, replicated, **compacted** topic storing each TransactionalId's state: `{PID, epoch, timeout, state (Empty/Ongoing/PrepareCommit/PrepareAbort/CompleteCommit/CompleteAbort), set of participating partitions, timestamps}`. The coordinator's in-memory state is just a materialization of this log — **Kafka uses its own log as the transaction manager's durable store** (the "log as database" idea eating itself, elegantly).
- **Control records:** a new message class invisible to applications — **COMMIT / ABORT markers** written *into every data partition that participated* in a transaction, telling consumers/brokers the fate of the preceding transactional messages in that partition.

### 3.2 The data flow of a transaction (worth being able to whiteboard)

1. **`initTransactions()`** → find coordinator (`FindCoordinatorRequest`), then `InitPidRequest(transactional.id)`: get PID, **bump epoch** (fencing any zombie), and the coordinator **rolls forward or aborts any transaction the previous incarnation left dangling**.
2. **`beginTransaction()`** — local state change in the producer.
3. As the producer sends to each new output partition, it first tells the coordinator via **`AddPartitionsToTxnRequest`**; the coordinator durably records the partition set in the transaction log (this is how it later knows where markers must go). Data then flows **directly to partition leaders** as normal produce traffic — stamped with PID, epoch, sequence, and a **transactional bit**.
4. **`sendOffsetsToTransaction(offsets, groupId)`** → coordinator records the `__consumer_offsets` partition as a participant; a `TxnOffsetCommitRequest` writes the offsets to the group coordinator, but they stay **invisible to the group until the transaction commits**. This one API is what fuses "consumed" and "produced" into a single atomic fact.
5. **`commitTransaction()` / `abortTransaction()`** → the classic **two-phase commit, made cheap by the log**:
   - **Phase 1:** coordinator writes **PREPARE_COMMIT/PREPARE_ABORT** to the transaction log. *This write is the decision* — once persisted, the outcome is guaranteed regardless of crashes.
   - **Phase 2:** coordinator writes **COMMIT/ABORT control markers** (via `WriteTxnMarkerRequest`) into every participating data partition and the offsets partition; then writes CompleteCommit/CompleteAbort to the transaction log.
   Unlike textbook 2PC, the "participants" don't vote — the coordinator's log is the single source of truth, and phase 2 is idempotent and retried until done, so coordinator failover (new coordinator replays `__transaction_state`) resumes cleanly.
6. **Transaction timeout (`transaction.timeout.ms`, default 60 s):** if a producer vanishes mid-transaction, the coordinator **proactively aborts**, bumps the epoch, and writes abort markers — so a dead producer can't block consumers forever.

### 3.3 The consumer side: read_committed and the LSO

- **`isolation.level=read_committed`:** the consumer buffers/skips messages from aborted transactions and only surfaces committed (and non-transactional) data. Brokers help via an **aborted-transaction index** returned with fetches, so consumers know which (PID, offset-range) chunks to drop.
- **Last Stable Offset (LSO):** the offset below which every transaction is decided (committed or aborted). read_committed consumers can only read **up to the LSO** — an *open* transaction therefore blocks read_committed consumers on that partition past its first message. This is the mechanism behind the operational rule "keep transactions short"; a hung transaction (until timeout) stalls downstream consumers.
- Ordering note: transactions are **not serialized against each other by commit time** — messages appear in offset order, and markers just resolve their visibility; consumers see interleaved transactions' messages in log order once committed.
- `read_uncommitted` (default) sees everything, including data whose transaction later aborts — fine for lag monitors and analytics that tolerate it.

### 3.4 Message format changes (the hidden half of the KIP)

To carry PID/epoch/sequence/transactional-bit and control markers, KIP-98 (with KIP-101's needs) forced **message format v2 — the RecordBatch format still used today**: batch-level headers holding PID, producer epoch, base sequence, a transactional flag, a control-batch flag, and **relative offsets/timestamps + varint encoding** inside the batch (which also made messages smaller). Understanding v2 batches *is* understanding how EOS data survives on disk.

## 4. Guarantees — precise statement

- **Idempotence alone:** exactly-once, in-order per partition, per producer session.
- **Transactions:** atomicity across partitions **and across the consume/produce boundary**; duplicates from producer restarts eliminated via TransactionalId + epoch; interleaved zombie writes fenced.
- **End-to-end exactly-once** = transactional producer + `sendOffsetsToTransaction` + read_committed consumers **within the Kafka domain** (Kafka→Kafka). For external sinks, exactly-once still requires the sink to cooperate (store offsets atomically with output, or dedup) — KIP-98 provides the primitives, not magic.
- **What it does NOT do:** no cross-system XA/distributed transactions; no change to durability physics (still page-cache + replication, not fsync-per-write — a point raised and accepted in the design discussion); no exactly-once for consumers that side-effect into external systems without their own atomicity.

## 5. Performance considerations

Design goal: pay only for what you use. Idempotence costs a few bytes per batch. Transactions amortize: the extra RPCs (AddPartitions, offset commit, markers) are **per transaction, not per message**, so throughput cost shrinks as you batch more into each transaction (100 ms-ish commit intervals are typical for Streams EOS); the real tradeoff is added **end-to-end latency** (consumers wait for commit) and LSO-blocking risk from long transactions.

---

## 6. Important concepts (highlight reel)

- **PID + per-partition sequence numbers** — broker-side dedup that survives leader failover because producer state lives *in the log*.
- **TransactionalId vs PID** — user-stable identity mapped to broker identity; the mapping + **epoch bump** is what makes **zombie fencing** work. Fencing is the single most interview-quotable idea in the KIP.
- **Transaction Coordinator + `__transaction_state`** — transaction manager whose durable state is itself a compacted Kafka log; recovery = replay.
- **Two-phase commit, log-style** — the decision *is* the PrepareCommit record; markers are idempotent follow-through, not votes. Compare/contrast with textbook 2PC blocking: here participants can't veto, and the coordinator log makes the protocol non-blocking across coordinator failover.
- **Control records / markers** — transaction outcomes are delivered *in-band, per partition*, which is why consumers can filter aborted data with only local information + the aborted-txn index.
- **LSO & read_committed** — visibility line for undecided data; explains both the consumer guarantee and the "short transactions" operational rule.
- **`sendOffsetsToTransaction`** — the API that makes "processed" atomic with "produced"; offsets are just another partition in the transaction. Consume-process-produce becomes one atomic unit.
- **Message format v2** — PID/epoch/sequence/transactional-bit in the batch header; control batches; the on-disk enabler of all of the above.
- **Guarantee boundaries** — exactly-once *within Kafka*; external systems need cooperative sinks. Being precise about this boundary is what separates a senior answer from a buzzword answer.

## 7. Real-world use cases — where it's used

**Direct applications:**

- **Kafka Streams EOS (`processing.guarantee=exactly_once_v2`)** — the flagship consumer of KIP-98: every task commits input offsets + changelog updates + output records in one transaction. Any Streams/ksqlDB deployment doing aggregations, joins, or windowing on money- or inventory-critical data runs on this machinery. (exactly_once_v2 / KIP-447 later cut the producer-per-partition overhead, but the substrate is KIP-98.)
- **Payment and ledger pipelines:** a processor consuming `payment.authorized`, debiting a running balance (state store), and emitting `ledger.entry` must never double-apply on rebalance/crash — exactly the consume-transform-produce atomicity KIP-98 exists for. In a payments-domain portfolio project (a PaySwitch/LedgerCore-style build), a transactional consume→process→produce stage with read_committed downstream consumers is the textbook demonstration of this KIP — and "how do you prevent double-charging when the processor crashes mid-batch?" is its interview form.
- **Order/inventory sagas in event-driven microservices:** emitting `order.validated` + `inventory.reserved` to two partitions atomically, so downstream services never observe half the outcome. (Note: for DB-write + Kafka-publish atomicity, the community answer remains the **outbox pattern + CDC**, because Kafka transactions don't span external databases — knowing *when KIP-98 is the wrong tool* is part of mastering it.)
- **Flink's Kafka sink (and Spark's) in exactly-once mode** — external stream engines checkpoint with Kafka transactions: pre-commit on checkpoint barrier, commit on checkpoint completion. KIP-98's producer API is the plug they attach to.
- **Multi-topic fan-out with integrity:** replication/copy jobs (e.g., a transactional MirrorMaker-style copier) that must not duplicate records across retries.

**As transferable design patterns:**

- **Idempotence via (identity, epoch, sequence)** — the same triple you'd use to build any at-least-once→exactly-once bridge: API request dedup keys, outbox dispatchers, WAL appliers.
- **Fencing tokens for split-brain writers** — epochs from KIP-98/KIP-101 are the canonical implementation of the "fencing token" idea (also in ZooKeeper/etcd lock recipes); any leader-elected worker you build (schedulers, cron leaders, partition owners) should carry one.
- **Coordinator state as a replayable log** — designing a stateful coordinator whose truth is a compacted changelog rather than a database is a pattern you can lift wholesale into your own systems.
- **Visibility watermarks (LSO)** — separating "written" from "readable" via a stable-offset watermark generalizes to any staging/commit pipeline design.

## 8. TL;DR

KIP-98 (Kafka 0.11, 2017) upgraded Kafka from at-least-once to exactly-once in two layers. **Idempotent producer:** broker-assigned PID + per-partition sequence numbers, persisted in the new v2 batch format, let brokers discard retry duplicates even across leader failover — exactly-once per partition per session, nearly free. **Transactions:** a stable `transactional.id` + epoch (zombie fencing), a transaction coordinator whose state lives in the compacted `__transaction_state` log, `AddPartitionsToTxn` bookkeeping, and a log-based two-phase commit that stamps COMMIT/ABORT **control markers** into every participating partition; `sendOffsetsToTransaction` pulls the consumer's offsets into the same transaction, and `read_committed` consumers (bounded by the **Last Stable Offset**) see only decided data. Together: atomic consume-process-produce and multi-partition writes — the machinery behind Kafka Streams EOS, Flink's exactly-once Kafka sink, and every "we can't double-charge the customer" pipeline — with the honest boundary that exactly-once ends at Kafka's edge, where cooperative sinks or the outbox pattern take over.