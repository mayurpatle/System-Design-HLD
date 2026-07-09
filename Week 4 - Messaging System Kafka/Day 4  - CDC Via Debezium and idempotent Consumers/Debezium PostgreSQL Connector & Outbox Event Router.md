# Debezium: PostgreSQL Connector & Outbox Event Router — Detailed Summary

*Based on Debezium 3.6 stable documentation.*
*Sources: [PostgreSQL connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html) · [Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)*

---

## Part 1 — PostgreSQL Connector

### What it does
The Debezium PostgreSQL connector captures row-level changes (INSERT, UPDATE, DELETE) from a PostgreSQL database and streams them to Kafka as change event records. On first connection it takes a **consistent snapshot** of all schemas, then **continuously streams** committed changes from the write-ahead log (WAL). By default, each table's events go to its own dedicated Kafka topic.

### Architecture: two cooperating parts
1. **Logical decoding output plug-in** — PostgreSQL's logical decoding (introduced in 9.4) extracts committed changes from the transaction log. You must enable logical decoding and choose a plug-in:
   - **`pgoutput`** — the standard, native plug-in in PostgreSQL 10+. Maintained by the PostgreSQL community, always present, no extra install needed. Recommended where installing plug-ins isn't allowed. Note: it does **not** capture values for generated columns.
   - **`decoderbufs`** — a Protobuf-based plug-in maintained by the Debezium community.
2. **Java code (Kafka Connect connector)** — reads the plug-in's output via PostgreSQL's streaming replication protocol (over the JDBC driver) and turns it into Debezium change events.

By default the connector auto-creates the configured **replication slot** on startup if it doesn't already exist (given sufficient privileges); in production you can create it manually for more control.

### Key limitations
- Logical decoding **does not support DDL changes** — the connector cannot report schema-change (DDL) events to consumers.
- Because logical decoding publishes changes at commit time, transient inconsistencies are possible (e.g., uncommitted changes if the primary dies mid-replication, or read-after-write gaps).
- `pgoutput` misses generated-column values.
- Debezium supports **UTF-8 encoded databases only**.

### Snapshots
Because WAL segments are usually purged over time, the connector can't rebuild full history from the log alone, so it performs an **initial consistent snapshot** the first time it starts.

**Default initial snapshot workflow:**
1. Start a transaction at the isolation level set by `snapshot.isolation.mode`.
2. Read the current position in the server transaction log.
3. Scan tables/schemas, emit a `READ` event (`op = r`) per row to the table's Kafka topic.
4. Commit the transaction.
5. Record snapshot completion in connector offsets.

If it fails before step 5, it restarts the snapshot from scratch. After completion, streaming resumes from the log position read in step 2.

**`snapshot.mode` options:**

| Mode | Behavior |
|------|----------|
| `initial` (default) | Snapshot when no Kafka offsets exist; otherwise stream from the stored LSN. |
| `always` | Snapshot on every start. Useful when WAL segments were deleted, or after a new primary is promoted. |
| `initial_only` | Snapshot once, then stop (no streaming). |
| `no_data` | Never snapshot; stream from stored LSN or from slot-creation point. Use only when all needed data is still in the WAL. |
| `when_needed` | Snapshot only if offsets are missing or the recorded log position is no longer available. |
| `configuration_based` | Controlled via `snapshot.mode.configuration.based.*` properties. |
| `custom` | Inject your own `io.debezium.spi.snapshot.Snapshotter` implementation. |

**Ad hoc snapshots** — re-capture table data on demand (e.g., after config changes, topic deletion, or data corruption) by sending an `execute-snapshot` signal to a signaling table. Types: `incremental` or `blocking`. You can target specific tables and even filter rows with `additional-conditions`.

**Incremental snapshots** — capture tables in configurable **chunks** (default 1024 rows), sorted by primary key, using **watermarks** to track progress. Advantages:
- Runs **in parallel** with live streaming (neither blocks the other).
- **Resumable** after interruption without re-capturing from the start.
- Can be triggered **on demand** and repeated.

To handle races where a streamed UPDATE/DELETE arrives before the chunk's `READ` event, Debezium uses a **snapshot window** with a de-duplication buffer: within the window, buffered `READ` events whose primary key matches an incoming streamed event are discarded (the streamed event wins). Triggered via a signaling table (SQL `INSERT`) or a Kafka signaling topic; can also be stopped mid-run via a `stop-snapshot` signal.

**Blocking snapshots** — the connector temporarily stops streaming, snapshots the specified tables like an initial snapshot, then resumes streaming.

### Streaming changes
The connector acts as a PostgreSQL replication client. On each commit, a server process calls the decoding plug-in, which serializes the changes; the connector transforms them into *create/update/delete* events tagged with the change's **LSN (Log Sequence Number)**, which serves as the Kafka Connect **offset**. On graceful shutdown, offsets are flushed and streaming resumes from the last committed position on restart.

> Note: The plug-in doesn't report which columns form the primary key — the connector gets that from JDBC metadata. If a PK definition changes, there's a brief window where an event may have an inconsistent key. The docs recommend a read-only → drain → stop → alter PK → restart procedure to avoid this.

### Topic names
Default convention: **`topicPrefix.schemaName.tableName`** (prefix set by `topic.prefix`). Example — prefix `fulfillment`, schema `inventory`, table `customers` → `fulfillment.inventory.customers`. Customize with the Topic Routing SMT.

### Transaction metadata
When enabled, Debezium emits **BEGIN/END** boundary events (to `<topic.prefix>.transaction` by default) containing `status`, `id` (format `txID:LSN`), `ts_ms`, and for END events `event_count` and per-collection `data_collections` counts. Data change events are also **enriched** with a `transaction` block: `id`, `total_order` (absolute event position in the transaction), and `data_collection_order`. Metadata is only captured for transactions that occur after the connector is deployed.

### Data change events
Every event has a **key** and a **value**, each optionally carrying its own `schema` + `payload` (self-describing, so consumers can handle schema evolution — or use a schema registry / Avro to shrink message size).

- **Key** — describes the primary key (or the columns set via `message.key.columns`).
- **Value envelope** — `before`, `after`, `source`, `op`, and timestamps `ts_ms` / `ts_us` / `ts_ns`.

**`op` values:** `c` = create, `u` = update, `d` = delete, `r` = read (snapshots only), `t` = truncate, `m` = message.

The **`source`** block carries: Debezium version, connector type/name, db, schema, table, snapshot flag, `txId`, `lsn`, `sequence` (JSON array: last committed LSN, current LSN), and `ts_ms` (time the change hit the database). Comparing `source.ts_ms` with the top-level `ts_ms` gives the source-to-Debezium lag.

**Event types:**
- **create** (`op=c`) — `before` is null, `after` holds the new row.
- **update** (`op=u`) — same schema; `before`/`after` reflect old/new values (availability of `before` depends on REPLICA IDENTITY).
- **primary-key update** — emitted as a **DELETE** (old key) + a **tombstone**, then a **CREATE** (new key).
- **delete** (`op=d`) — `before` holds the deleted row (subject to REPLICA IDENTITY), `after` is null. Followed by a **tombstone** event (same key, null value) to support Kafka log compaction. A table with no PK and REPLICA IDENTITY `DEFAULT`/`NOTHING` yields no `before`; set `FULL` to get it.
- **truncate** (`op=t`) — null key, no `before`/`after`; one event per truncated table. No ordering guarantee across partitions. Filter out via `skipped.operations`.
- **message** (`op=m`) — generic logical-decoding messages inserted into the WAL via `pg_logical_emit_message` (only via `pgoutput` on PostgreSQL 14+); key is a struct with a `prefix` field.

### REPLICA IDENTITY
A per-table PostgreSQL setting controlling how much "before" information logical decoding exposes for UPDATE/DELETE:
- **`DEFAULT`** — before-image contains only the primary-key columns (changed PK columns for updates). No PK → no UPDATE/DELETE events emitted (create only).
- **`NOTHING`** — no previous-value information at all.
- **`FULL`** — previous values of **all** columns.
- **`INDEX <name>`** — previous values of the columns in the named index.

### Data types
The connector maps PostgreSQL types to Kafka Connect schema types (basic, temporal, decimal, HSTORE, domain, network address, PostGIS, pgvector, TSVECTOR). Notable cases: temporal/TIMESTAMP handling, decimal precision modes, **toasted values** (large out-of-line values that may appear as unchanged placeholders when not modified), and default-value handling. Custom converters are supported for bespoke mappings.

### Setup essentials
- Configure PostgreSQL for logical decoding and pick a plug-in (specify via `plugin.name`).
- Create a dedicated **replication user** with least-privilege grants (avoid `superuser`).
- Configure the server (`wal_level = logical`, replication slots) and host-based replication permissions (`pg_hba.conf`).
- When using `pgoutput`, grant privileges so Debezium can create the **publication** (or create it manually).
- Watch **WAL disk-space consumption**: an inactive/slow slot holds WAL and can fill the disk.
- Multiple connectors on the same server each need their own replication slot and publication.

### Operations & resilience
- **Monitoring** via JMX MBeans for snapshot and streaming metrics.
- **Failure handling:** the connector is restart-tolerant and resumes from the last recorded LSN after crashes, Kafka/Kafka-Connect outages, or PostgreSQL unavailability. If stopped too long such that needed WAL was purged, a new snapshot may be required.

---

## Part 2 — Outbox Event Router (SMT)

### The problem it solves
The **outbox pattern** reliably propagates data between (micro)services while avoiding inconsistency between a service's own database state and the events other services consume. Instead of dual-writing to the DB and a message broker, the service writes business changes **and** an outbox record in the **same local transaction**. Debezium then captures the outbox table and publishes the events — guaranteeing the event is emitted if and only if the transaction committed.

**Implementation = two pieces:**
1. Configure a Debezium connector to capture changes on the **outbox table**.
2. Apply the **Outbox Event Router SMT** (`io.debezium.transforms.outbox.EventRouter`).

The connector should capture **only** the outbox table. Multiple outbox tables are allowed only if they share the same structure. (This SMT is **not** compatible with MongoDB — MongoDB has a separate MongoDB Outbox Event Router SMT.)

### What the SMT does
It transforms a raw Debezium change event (with `before`/`after`/`source`/`op`, where `after` contains the outbox row) into a clean, routed outbox message: it routes to a topic based on an aggregate column, sets the message key, promotes the payload to the message value, and attaches the event ID as a header.

### Expected outbox table structure (default config)

| Column | Type | Role |
|--------|------|------|
| `id` | uuid (not null) | Unique event ID → emitted as the `id` **message header** (useful for de-duplication). |
| `aggregatetype` | varchar (not null) | Appended to the topic name via `${routedByValue}`. E.g. `customers` → `outbox.event.customers`. |
| `aggregateid` | varchar (not null) | Used as the **message key** — maintains ordering within a Kafka partition. |
| `type` | varchar (not null) | Event type (e.g. `OrderCreated`). |
| `payload` | jsonb | The event payload — becomes the Kafka message value. |

Any additional columns can be routed into the message header, envelope, or used for partitioning.

### Example
An outbox row with `aggregatetype = "Order"`, `aggregateid = "1"`, a JSON `payload`, and `id = 4d47e19...` becomes:
- **Topic:** `outbox.event.order`
- **Key:** `"1"`
- **Header:** `id=4d47e190-0402-4048-bc2c-89dd54343cdc`
- **Value:** the `payload` JSON.

### Basic configuration
```
transforms=outbox,...
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
```
Because a connector also emits heartbeats, tombstones, transaction/schema metadata, etc., apply the SMT **selectively** — either via an **SMT predicate** or the `route.topic.regex` option — so it only processes true outbox rows.

### Payload serialization
- **JSON (default)** — source column should be JSON (`jsonb`). Escaped-JSON strings can be expanded into "real" JSON by setting `table.expand.json.payload=true` and using `JsonConverter` (the SMT infers the schema from the document).
- **Avro** — store the payload as binary (`bytea`) and use `io.debezium.converters.BinaryDataConverter` to pass the bytes through unchanged. Because that converter can't serialize heartbeat/metadata events, configure a **delegate converter** (`value.converter.delegate.converter.type`) — e.g. Kafka `JsonConverter`, or an Avro converter backed by **Apicurio** or **Confluent Schema Registry**. Avro helps with schema governance and backward-compatible evolution.

### Emitting additional fields
Syntax: **`column:placement:alias`**, where `placement` ∈ `header`, `envelope`, `partition`.
- `eventType:header:type` → adds header `type` = value of `eventType`.
- `eventType:envelope:type` → adds the field inside the message envelope.
- `partitionColumn:partition` → controls the target partition (alias is ignored for `partition`).

When additional fields are placed in the envelope, the message value becomes an envelope wrapping both the payload and the extra fields (rather than just the raw payload).

### Key configuration options

| Option | Default | Purpose |
|--------|---------|---------|
| `table.op.invalid.behavior` | `warn` | Reaction to an UPDATE on the outbox table (`warn`/`error`/`fatal`). Outbox tables are INSERT-only queues; DELETEs are auto-filtered. |
| `table.field.event.id` | `id` | Column for the unique event ID (→ `id` header). |
| `table.field.event.key` | `aggregateid` | Column used as the Kafka message key. |
| `table.field.event.timestamp` | *(Debezium ts)* | Column to use as the message timestamp. |
| `table.field.event.payload` | `payload` | Column holding the payload. |
| `table.expand.json.payload` | `false` | Expand escaped JSON string into real JSON. |
| `table.json.payload.null.behavior` | `ignore` | How to treat nulls when expanding JSON (`ignore` / `optional_bytes`). |
| `table.fields.additional.placement` | — | Route extra columns to header/envelope/partition (`col:placement:alias`). |
| `table.field.additional.missing` | `error` | Behavior when a configured additional field is absent (`error` / `ignore`). |
| `table.field.event.schema.version` | — | Sets the Kafka Connect schema version. |
| `route.by.field` | `aggregatetype` | Column whose value forms part of the topic name. |
| `route.topic.regex` | `(?<routedByValue>.*)` | Regex used by the RegexRouter over `route.topic.replacement`. |
| `route.topic.replacement` | `outbox.event.${routedByValue}` | Target topic-name template. |
| `route.tombstone.on.empty.payload` | `false` | Emit a tombstone when payload is empty/null. |
| `tracing.span.context.field` | `tracingspancontext` | Field carrying tracing span context. |
| `tracing.operation.name` | `debezium-read` | Span operation name. |
| `tracing.with.context.field.only` | `false` | Only trace events that carry a serialized context field. |

### Distributed tracing
The Outbox SMT supports distributed tracing (span context propagation) via the tracing options above.

---

## How the two fit together
Run the **PostgreSQL connector** pointed at a single `outboxevent` table, apply the **Outbox Event Router SMT** (with a predicate so it only processes outbox rows), and each business transaction that writes an outbox record produces exactly one clean, routed, keyed Kafka message on `outbox.event.<aggregatetype>` — giving reliable, transactionally-consistent inter-service event delivery.