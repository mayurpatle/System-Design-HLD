# Deep-Dive Summary: Transactions in Apache Kafka (KIP-98)
**Based on the official Apache Kafka Design Document**

---

## 1. Executive Summary & Objective
**KIP-98 (Transactions in Apache Kafka)** introduced transactional capabilities to Kafka, allowing applications to process messages atomically across multiple partitions and topics. It enables the **Read-Process-Write** loop to be fully atomic, laying the structural foundation for **Exactly-Once Semantics (EOS)** in Kafka Stream applications.

The key goal is to guarantee that:
* Either all messages within a transactional batch are successfully committed and made visible to downstream consumers, or none of them are.
* Offsets committed during processing are part of the same transaction, preventing duplicate processing upon consumer failures.

---

## 2. Core Architectural Components

Kafka avoids high-overhead, two-phase distributed lock managers (like XA) by leveraging its own replicated log infrastructure. It introduces four core components:

| Component | Responsibility |
| :--- | :--- |
| **Transactional Coordinator** | A specialized module running inside the Kafka broker that manages the lifecycle of a transaction (tracks states, handles commits/abort markers). Similar to the Group Coordinator. |
| **Transaction Log (`__transaction_state`)** | An internal, replicated, compacted Kafka topic that stores the transaction status history (e.g., *Ongoing, PrepareCommit, CompleteCommit*). It acts as the source of truth for recovery. |
| **Producer ID (PID) & Epoch** | Every transactional producer is assigned a unique 64-bit PID and an incrementing Epoch number via the `InitProducerId` API. This prevents split-brain scenarios from "zombie" producers. |
| **Control Records (Markers)** | Special metadata messages (`COMMIT` or `ABORT`) written directly to the data partitions by the coordinator once a transaction finishes. |

---

## 3. The 6-Stage Transaction Lifecycle Workflow

The design operates through a coordinated sequence between the Producer, Transaction Coordinator, and Partition Leaders:

### Step 1: Finding the Transaction Coordinator
The producer issues a `FindCoordinatorRequest` specifying its unique string identifier, `transactional.id`. The cluster maps this ID to a specific partition of the `__transaction_state` topic. The leader of that partition becomes the **Transactional Coordinator** for that producer.

### Step 2: Initializing the Producer ID (`InitProducerId`)
The producer sends an `InitProducerIdRequest` to its coordinator. The coordinator maps the `transactional.id` to a permanent **Producer ID (PID)** and increments the **Producer Epoch**. 
> 💡 *Zombie Fencing:* If an old instance of the same producer attempts to write, brokers reject it because its epoch is outdated.

### Step 3: Beginning a Transaction
The producer calls `beginTransaction()` locally. No network call is made yet; the coordinator is unaware until the first message write attempt.

### Step 4: Consuming & Producing (The Read-Process-Write Loop)
As the producer processes data, it performs three primary actions:
* **`AddPartitionsToTxnRequest`**: Before writing to a new topic-partition, the producer registers the partition with its coordinator. The coordinator appends an `Ongoing` state log entry into the `__transaction_state` log.
* **`ProduceRequest`**: The producer writes the actual data records to the respective partition leaders. These records include the `PID`, `Epoch`, and incrementing `Sequence Numbers` (for deduplication).
* **`AddOffsetsToTxnRequest`**: To commit offsets inside the transaction, the producer registers the consumer group ID with its coordinator, which then routes an offset commit to the group coordinator.

### Step 5: The Two-Phase Commit Protocol

#### Phase 4a: Preparing to Commit/Abort
When the user calls `commitTransaction()` or `abortTransaction()`, the producer sends an `EndTxnRequest` to the coordinator. The coordinator appends a `PrepareCommit` or `PrepareAbort` entry to the transaction log. At this snapshot, the transaction's fate is sealed.

#### Phase 4b: Writing Control Markers
The coordinator writes a `COMMIT` or `ABORT` **Control Record** to every data partition that was registered during Step 4. 

### Step 6: Completing the Transaction
After all control markers are successfully written and acknowledged by the partition leaders, the coordinator appends a final `CompleteCommit` (or `CompleteAbort`) record to the transaction log, releasing the `transactional.id` for subsequent transactions.

---

## 4. Consumer Isolation Levels

To support transactions, Kafka consumers introduce the `isolation.level` configuration, changing how the **Log End Offset (LEO)** and **Last Stable Offset (LSO)** are evaluated.

* **`read_uncommitted` (Default):** The consumer reads all messages sequentially up to the high watermark, including aborted transactions or messages belonging to in-flight, ongoing transactions.
* **`read_committed`:** The consumer stops scanning when it encounters a message belonging to an open/ongoing transaction. It will only emit messages up to the **LSO**, which is the offset of the first active transaction in the partition log. Once that transaction commits or aborts via a Control Marker, the consumer advances past it.

---

## 5. Failure Recovery Scenarios

The KIP-98 design ensures strict resilience against unexpected failures:

* **Producer Crashes:** If a producer crashes mid-transaction, the coordinator will eventually hit a timeout (`transaction.timeout.ms`). The coordinator will then automatically step in, append a `PrepareAbort` record, write `ABORT` markers to all registered partitions, and close the transaction.
* **Coordinator Broker Crashes:** If the broker acting as the transaction coordinator dies, a new leader is elected for that partition of `__transaction_state`. The new coordinator scans the transaction log, reconstructs the in-memory state, and finishes any pending transactions stuck in `PrepareCommit` or `PrepareAbort` by re-sending the control markers to the data partitions.