---

## title: Storage Engines — B+ Tree vs LSM Tree
tags: [system-design, databases, storage-engines, b-plus-tree, lsm-tree]
day: 8
status: in-progress
created: 2026-06-23

# Storage Engines: B+ Tree vs LSM Tree

> The whole topic collapses into one trade-off: **where do you pay the cost — on the
> write, on the read, or on disk?** B+ trees update in place (cheap reads, expensive
> random writes). LSM trees append and compact later (cheap writes, amplified reads
> and background I/O). Everything below is just the consequences of that one choice.

---

## 1. Diagrams (hand-drawn → photograph → embed)

Draw these on paper/whiteboard, photograph, drop into the vault, and link below.

### 1.1 B+ Tree with a node split

Draw a B+ tree of order 4 (max 3 keys/node) and insert a key that overflows a leaf so it splits and pushes a separator up.

```
Before insert of 25 (leaf [20 22 24] is full):

                 [ 20 ]
                /      \
        [10 15]         [20 22 24] [30 ...]

After inserting 25 → leaf splits, median 24 (or 22) promoted:

                 [ 20 | 24 ]
                /     |      \
        [10 15]   [20 22]   [24 25] → [30 ...]
                              \________linked leaf chain (range scans)
```

Things the drawing must show:

- **Leaves linked in a doubly-linked list** (this is what makes range scans cheap — you walk siblings, no tree re-traversal).
- All data lives in **leaves only**; internal nodes are just routing keys.
- The split: leaf overflows → split in two → median separator copied up to the parent → parent may itself split and cascade to the root (root split = tree grows one level taller).

![[bplus-tree-split.jpg]]   

### 1.2 LSM Tree with 3 SSTable levels + compaction in progress

```
   WRITE PATH                          READ PATH (newest → oldest)
   ─────────                           ──────────────────────────
   put(k,v) → WAL (durability)         memtable?  → check
            → Memtable (in RAM,        L0 SSTables (may overlap!) → bloom filter
              sorted skiplist)         L1 SSTables (non-overlapping) → bloom filter
                  │ flush when full    L2 SSTables (~10x size of L1) → bloom filter
                  ▼
   ┌─ L0 ─┐  [sst][sst][sst]   ← flushed memtables, key ranges OVERLAP
   ┌─ L1 ─┐  [.....][.....][.....]   ← non-overlapping, sorted runs
   ┌─ L2 ─┐  [..........][..........][..........]   ← ~10x larger than L1

   COMPACTION IN PROGRESS (leveled):
     pick 1 SSTable from L1  +  all overlapping SSTables from L2
       → merge-sort, drop tombstones & superseded versions
       → write fresh non-overlapping SSTables back into L2
       → delete the inputs
```

Things the drawing must show:

- **WAL + memtable** on the write side; flush arrow from memtable to L0.
- **L0 overlaps** (each flush is independent), **L1+ are non-overlapping sorted runs**.
- Each level **~10× the previous** (the level multiplier).
- A compaction arrow: L1 file + overlapping L2 files → merged → rewritten into L2, with **tombstones and stale versions discarded**.
- A **bloom filter** tag on each SSTable (this is what saves reads).

![[lsm-tree-compaction.jpg]]   

---

## 2. One-Page Comparison — Read / Write / Space Amplification

**Definitions (say these out loud, they're interview gold):**

- **Read amplification** — bytes (or I/Os) read from storage per logical read. A point lookup that touches 5 places has read amp ~5.
- **Write amplification** — bytes written to storage per byte of logical data. Write 1 KB, but the engine eventually writes 20 KB → write amp 20.
- **Space amplification** — bytes on disk per byte of live data. 1.1 means 10% overhead; 2.0 means half your disk is dead/stale data.


| Dimension     | **B+ Tree (Postgres)**                                                                                                                                                                                                                                                                                               | **LSM Tree (RocksDB, leveled)**                                                                                                                                                                                                                                                                            |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Read amp**  | Low. Point lookup = tree height in page reads, typically **3–4 pages** for a multi-million-row index; upper levels almost always in the buffer cache, so effectively ~1 disk read.                                                                                                                                   | Higher *in theory* — must check memtable + L0 + every level. **Bloom filters (~1% false positive)** let reads skip almost every level they don't contain the key, so real-world read amp is close to "1 SSTable + a few bloom checks." Range scans are worse: must merge across overlapping runs.          |
| **Write amp** | Medium, and sneaky. The index update itself is in-place, but Postgres also writes the **WAL**, and `full_page_writes` dumps the **entire 8 KB page** to WAL on the first touch after a checkpoint. Random-key inserts (e.g. UUIDv4) trigger frequent **page splits** → more dirty pages → more full-page WAL writes. | High and by design. With leveled compaction the **level multiplier defaults to 10**, and a key can be rewritten roughly once per level it descends. Reported write amp is commonly **~10–30×**. Tiered/universal compaction trades this down to **~4–10×** at the cost of space.                           |
| **Space amp** | ~10% baseline from the default **index fillfactor of 90**, *plus* bloat over time: dead tuples from MVCC and split-induced fragmentation can push real on-disk size to **1.5–2×+** before a `REINDEX`/`VACUUM`.                                                                                                      | Leveled compaction is the space-efficient mode: Facebook's RocksDB team tuned it to **~1.1× (≈10% overhead)** because only the largest level dominates and it's kept non-overlapping. Tiered/universal compaction balloons to **~2×+** because overlapping SSTables hold many stale versions until merged. |


### Real numbers to cite (memorize 2–3)

- **RocksDB write amplification, leveled compaction: ~10–30×.** Driven by `max_bytes_for_level_multiplier = 10` (each level ~10× the previous), so a key is re-written roughly once per level on its way down.
- **RocksDB space amplification ~1.1× with leveled compaction** — from Dong et al., *"Optimizing Space Amplification in RocksDB"* (CIDR 2017), where the team explicitly traded a bit more write amp for ~10% space overhead. Tiered/universal compaction instead lands near **2×** space amp with **lower** write amp — the classic RUM-conjecture triangle (you can't minimize Read, Update, and Memory all at once).
- **Postgres B-tree page = 8 KB, index fillfactor = 90% by default.** Splits leave roughly 90/10 on the rightmost (ascending) leaf and ~50/50 for random inserts.
- **Postgres B-tree height for millions of rows ≈ 3–4 levels**, so a point lookup is 3–4 logical page reads, nearly all cache hits after warm-up.
- **Key ordering matters enormously in Postgres.** Sequential keys (`bigserial`, UUIDv7/ULID) append to the rightmost leaf → few splits, little bloat. Random keys (UUIDv4) scatter inserts → frequent splits, index bloat, and extra WAL/full-page write amplification. This is *the* practical reason teams switched from UUIDv4 to UUIDv7/ULID.

### The mental model

```
            Reads cheap                    Writes cheap
                │                               │
   B+ TREE ◄────┤                               ├────► LSM TREE
   in-place     │                               │      append + compact
   updates      │                               │
                └──── you pick a corner ────────┘
                      of the RUM triangle
```

---

## 3. Drill — "When would you choose Cassandra over Postgres?"

Speak each answer **out loud in ≤60s**, citing *engine mechanics*, not vibes. Time yourself.
Core thesis to anchor every answer: **Cassandra = LSM + masterless, leaderless, partitioned-by-design → wins on write-heavy, horizontally-scaled, multi-region, available-over-consistent workloads. Postgres = B+ tree + single-primary ACID → wins on transactions, joins, flexible queries, and strong consistency.**

### Prompt 1 — "Write-heavy ingestion, 200k writes/sec, append-only events."

**Cassandra.** Its LSM engine turns every write into a memtable append + WAL — no in-place page update, no read-before-write, so writes are near sequential and cheap. Postgres' B+ tree would page-split and full-page-WAL itself to death at random keys, and a single primary caps write throughput. Cassandra spreads those writes across the token ring so I add nodes to add write capacity linearly.

### Prompt 2 — "We need multi-region active-active with no single point of failure."

**Cassandra.** It's masterless — every node is a peer, writes go to any replica, tunable consistency (`LOCAL_QUORUM` per DC) keeps it available during a region partition. That's AP-leaning by design. Postgres is single-primary: cross-region you're doing async streaming replication with read replicas, and failover means promoting a standby with a window of data loss. If the workload can't tolerate a write outage when the primary dies, Cassandra's topology wins.

### Prompt 3 — "Financial ledger: transfers must be atomic and never double-spend."

**Postgres, not Cassandra.** This is exactly where the B+ tree + MVCC + real ACID transactions matter — multi-row atomic commits, `SERIALIZABLE`/`SELECT ... FOR UPDATE`, foreign keys, constraints. Cassandra has no general multi-partition transactions; lightweight transactions are Paxos-based, slow, and single-partition. I'd be reinventing a transaction manager in the app layer. Strong consistency + correctness beats raw write throughput here.

### Prompt 4 — "Query patterns keep changing — ad-hoc joins, aggregations, analyst access."

**Postgres.** Its B+ tree indexes plus a cost-based planner let me add indexes and run arbitrary joins/`GROUP BY` after the fact. Cassandra forces query-first modeling: you design the partition key around *the* read pattern, and a query the table wasn't modeled for either needs a denormalized second table, a materialized view, or an `ALLOW FILTERING` full-scan that's an anti-pattern. Flexible, evolving queries → relational engine.

### Prompt 5 — "Time-series / IoT: huge volume, mostly recent reads, data ages out via TTL."

**Cassandra** (or a Cassandra-style LSM store). LSM loves high-volume sequential-ish writes, partition-per-device + clustering-by-time gives tight recent-range reads, and **TTL deletes are just tombstones dropped during compaction** — no expensive `DELETE` + `VACUUM` like Postgres. The LSM compaction machinery that's "overhead" elsewhere is exactly what reclaims expired time-series data for free here.

**Drill log (fill in):**

- [x] Round 1 — prompt: ____  | time: ___s | stumbled on: ____
- [ ] Round 2 — prompt: ____  | time: ___s | stumbled on: ____
- [ ] Round 3 — prompt: ____  | time: ___s | stumbled on: ____
- [ ] Round 4 — prompt: ____  | time: ___s | stumbled on: ____
- [ ] Round 5 — prompt: ____  | time: ___s | stumbled on: ____

---

## 4. Deliverable Checklist — Day 8

- [x] Hand-drawn **B+ tree with a node split** — photographed & embedded (`bplus-tree-split.jpg`)
- [x] Hand-drawn **LSM tree, 3 SSTable levels + compaction in progress** — photographed & embedded (`lsm-tree-compaction.jpg`)
- [ ] **One-page amplification comparison** with real RocksDB & Postgres numbers ✅ (above)
- [ ] **"When each wins" summary** ✅ (the thesis + 5 drilled answers above)
- [ ] 5× 60-second verbal drill completed and logged

---

## 5. Five-second recall (review before sleep)

- B+ tree = **update in place** → cheap reads, splits + WAL on random writes.
- LSM = **append + compact later** → cheap writes, read amp tamed by **bloom filters**.
- RocksDB leveled: write amp **~10–30×**, space amp **~1.1×**; tiered flips it (lower write amp, **~2×** space).
- Postgres: **8 KB page, 90% fillfactor, 3–4 level height**; sequential keys good, **UUIDv4 = split + bloat**.
- **RUM conjecture**: Read, Update, Memory — pick two.
- Cassandra (LSM, masterless) wins write-heavy / multi-region / available; Postgres (B+ tree, ACID) wins transactions / joins / strong consistency.

