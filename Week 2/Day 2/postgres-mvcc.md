

# Postgres MVCC, VACUUM & XID Wraparound

> One design decision drives this entire note: **Postgres never updates a row in
> place. It writes a new version and leaves the old one behind.** That choice buys
> lock-free concurrency (readers never block writers, writers never block readers)
> — and the entire bill for it comes due as VACUUM, freezing, and wraparound risk.
> Oracle made the opposite choice (update in place + UNDO segment), which is why
> Oracle has no VACUUM and no wraparound problem. Everything below is the
> consequence of Postgres' choice.

---

## 1. MVCC — how it actually works

**Multi-Version Concurrency Control:** every row (tuple) carries hidden system columns that record *which transaction created it* and *which transaction killed it*. A reader checks those against its **snapshot** to decide if the row is visible. No read locks, no write locks for visibility — just version bookkeeping.

### The hidden columns (the whole mechanism lives here)

```
A heap tuple = [ header | user data ]
                  │
   ┌──────────────┴───────────────────────────────────┐
   xmin  = XID of the transaction that INSERTed it
   xmax  = XID of the transaction that DELETEd/UPDATEd it (0 if alive)
   cmin/cmax = command IDs (visibility within the same transaction)
   ctid  = physical location (page, offset); UPDATE points old→new version
```

You can literally see them: `SELECT xmin, xmax, ctid, * FROM my_table;`

### What each DML really does

| Statement | What physically happens |
|---|---|
| **INSERT** | Write one new tuple, `xmin = my_xid`, `xmax = 0`. |
| **UPDATE** | **Not in place.** Mark the old tuple `xmax = my_xid`, then INSERT a brand-new tuple version with `xmin = my_xid`. Old version stays on disk. |
| **DELETE** | Mark the tuple `xmax = my_xid`. The row data physically stays until VACUUM. |

So an UPDATE-heavy table is constantly manufacturing dead tuples. That's the cost.

### Visibility rule (simplified)

A tuple is visible to my snapshot if:
- its **xmin** is committed *and* was committed before my snapshot was taken, **and**
- its **xmax** is empty, aborted, or belongs to a transaction not yet visible to my snapshot.

A **snapshot** captures: the current XID, plus the set of in-flight (not-yet-committed) XIDs at the moment it's taken. That's how two concurrent transactions each see a consistent point-in-time view of the same table without locking each other.

### Isolation levels = "when do I take a new snapshot?"

| Level | Snapshot behavior |
|---|---|
| **Read Committed** (default) | New snapshot **per statement** — each query sees the latest committed data. |
| **Repeatable Read** | One snapshot **per transaction** — stable view, no non-repeatable reads. |
| **Serializable** | Repeatable Read **+ SSI** (Serializable Snapshot Isolation): tracks read/write dependencies and aborts transactions that would produce a non-serializable schedule. |

### HOT updates (the optimization that limits the damage)

**Heap-Only Tuples:** if an UPDATE changes only columns that are **not indexed**, and the new version **fits on the same page**, Postgres chains the new version on the page and skips inserting new index entries. This dramatically reduces index bloat and write amplification. It's a big reason "don't index columns you update constantly" and "leave some `fillfactor` headroom on write-hot tables" are real tuning advice.

---

## 2. Why VACUUM exists

Because of MVCC, **dead tuples never clean themselves up.** Once a tuple is invisible to *every* live and future transaction, it's just garbage occupying a page. VACUUM is the garbage collector. It is **not optional** — it's load-bearing infrastructure.

### What VACUUM does (five jobs, not one)

1. **Reclaims dead tuples** → marks their space reusable for future inserts/updates *within the same table*. (Plain VACUUM usually does **not** return space to the OS; `VACUUM FULL` rewrites the whole table and does, but takes an `ACCESS EXCLUSIVE` lock — table is unusable during it.)
2. **Updates the Free Space Map (FSM)** so new tuples know where the reusable gaps are.
3. **Updates the Visibility Map (VM)** — marks pages "all-visible," which enables **index-only scans** and lets future vacuums skip clean pages.
4. **Freezes old tuples** to prevent XID wraparound (see §3 — this is the existential one).
5. **(via ANALYZE)** refreshes planner statistics.

### Autovacuum — the background daemon

Triggers per table when roughly:
```
dead_tuples  >  autovacuum_vacuum_threshold (default 50)
              + autovacuum_vacuum_scale_factor (default 0.2) × reltuples
```
i.e. **~20% of the table churned** kicks off an autovacuum. On large, hot tables that default is often *too lazy* — you lower the scale factor per-table so vacuum runs more often on less garbage.

### Bloat = VACUUM falling behind

If dead tuples accumulate faster than VACUUM reclaims them (or VACUUM is *blocked* — see §4), the table and its indexes physically grow with dead weight. Symptoms: table size ≫ live data, slower seq scans, degraded index scans, more buffer cache pressure. Fixes: tune autovacuum, `REINDEX`, or `VACUUM FULL`/`pg_repack` for severe cases.

---

## 3. XID wraparound — the existential failure mode

### The setup

XIDs are **32-bit** → only ~**4.29 billion** (2³²) values exist. Visibility comparison is **circular (modulo 2³¹)**: for any given XID, roughly **2.1 billion** other XIDs count as "in the past" (older/visible) and **2.1 billion** as "in the future" (newer/invisible).

```
        XID space is a CIRCLE, not a line:

                 ┌──────── future (newer) ────────┐
        current XID ●                              ● ~2.1B ahead
                 └──────── past (older) ───────────┘
                              ~2.1B behind

   Problem: as new XIDs are consumed, the "current" point moves around
   the circle. A very old tuple's xmin eventually falls more than 2.1B
   behind → it flips to the "future" side → the tuple SUDDENLY BECOMES
   INVISIBLE. Live data appears to vanish. Catastrophe.
```

### The fix: freezing

VACUUM **freezes** sufficiently old tuples — marks them so they're treated as "infinitely in the past" / always visible, regardless of XID arithmetic. A frozen tuple is immune to wraparound. Postgres tracks the oldest un-frozen XID per table as `relfrozenxid` (visible via `age(relfrozenxid)`).

### The knobs

| Setting | Default | Meaning |
|---|---|---|
| `vacuum_freeze_min_age` | 50,000,000 | Tuples older than this get frozen during a vacuum. |
| `autovacuum_freeze_max_age` | 200,000,000 | Table's oldest XID age that **forces an anti-wraparound autovacuum** — runs *even if autovacuum is disabled*. |
| `vacuum_failsafe_age` | 1,600,000,000 | Emergency mode (PG 14+): vacuum skips index cleanup and just freezes as fast as possible. |

### What happens if you ignore it

As the database's oldest XID age climbs toward 2.1B, Postgres escalates:
- **Loud warnings** in the log (~tens of millions of XIDs remaining): "database must be vacuumed within N transactions."
- **Refusal to accept new write transactions** when only a few million remain (exact threshold varies by version) — the server effectively goes read-only / requires single-user-mode manual VACUUM to recover.

This is a real, repeatable production outage — **txid wraparound has publicly taken down major services** (e.g. Mailchimp/Mandrill's 2019 multi-day outage was attributed to transaction-ID wraparound). It is the canonical "Postgres at scale will bite you if you don't understand the storage engine" story, which is exactly why interviewers ask about it.

---

## 4. The long-running-transaction failure mode (where it all connects)

This is the question behind the question. It links MVCC, VACUUM, and wraparound into one mechanism.

### The mechanism: the xmin horizon

VACUUM may only remove a dead tuple if it's invisible to **every** snapshot that still exists. The **oldest snapshot still in use** defines the **xmin horizon** — VACUUM cannot clean (or freeze) anything newer than it.

A single **long-running transaction holds that horizon in the past.** While it lives:
- Dead tuples created *after* it started **cannot be vacuumed** → bloat grows **across the whole database**, not just the tables that transaction touched.
- Tuples **can't be frozen** past the horizon → `relfrozenxid` age keeps climbing → you march toward **wraparound** with no way to stop it until the transaction ends.

```
   T_old BEGIN (snapshot taken) ───────────────────────────► still open!
        │                                                    │
        ▼                                                    ▼
   xmin horizon pinned here ──── VACUUM can clean nothing right of here ────►
        (every dead tuple to the right survives, table bloats, XID age rises)
```

### The usual culprits

- **`idle in transaction`** sessions — app opened a transaction, did one query, then went to lunch / a network hiccup left it open.
- Genuinely **long analytical queries** or batch jobs in one transaction.
- **Abandoned prepared (2PC) transactions** — `pg_prepared_xacts`.
- **Replication slots** with a disconnected/lagging consumer — `pg_replication_slots` pins the horizon too.
- **`hot_standby_feedback = on`** — a long query on a replica pushes the horizon back on the **primary**.

### How to diagnose (memorize this checklist)

```sql
-- longest-running / idle-in-transaction backends
SELECT pid, state, now() - xact_start AS xact_age, query
FROM pg_stat_activity
WHERE state <> 'idle'
ORDER BY xact_start ASC;          -- oldest first

-- tables closest to wraparound
SELECT relname, age(relfrozenxid) AS xid_age
FROM pg_class
ORDER BY xid_age DESC LIMIT 10;

-- other horizon-holders
SELECT * FROM pg_prepared_xacts;
SELECT slot_name, active, xmin, catalog_xmin FROM pg_replication_slots;
```

### Mitigations

- `idle_in_transaction_session_timeout` — auto-kill sessions that sit in an open transaction.
- `statement_timeout` — cap runaway queries.
- Keep transactions **short**; never wrap user think-time / external API calls inside a transaction.
- Monitor the **oldest transaction age** and **max `age(relfrozenxid)`** as first-class alerts.
- Drop or monitor stale **replication slots**; weigh `hot_standby_feedback` trade-offs.

---

## 5. Postgres vs Oracle — why only Postgres has this problem

| | **Postgres** | **Oracle** |
|---|---|---|
| Old row versions live... | **in the heap itself** (alongside live data) | in a separate **UNDO/rollback segment** |
| Cleanup mechanism | **VACUUM** (must reclaim dead tuples in-table) | UNDO auto-reclaimed; no VACUUM |
| Wraparound risk | **Yes** — 32-bit XID + in-heap versions ⇒ must freeze | No equivalent (uses SCNs, undo) |
| Trade-off | Simpler engine, lock-free reads/writes, but GC pressure | No GC pressure, but UNDO management & ORA-01555 "snapshot too old" |

Postgres treats every change as **a new truth and keeps the old one until someone cleans up**; Oracle **rewrites in place and keeps a short-term diary (UNDO)**. Same MVCC promise, opposite storage philosophy.

---

## 6. Five-second recall (review before sleep)

- MVCC = **no in-place update**. UPDATE = mark old `xmax` + insert new version. Visibility via **xmin/xmax** vs your **snapshot**.
- Readers never block writers; writers never block readers — that's the whole payoff.
- **VACUUM** isn't optional: it reclaims dead tuples, updates VM/FSM, and **freezes** old XIDs. Autovacuum fires at ~**20% churn**.
- **XID = 32-bit**, circular mod 2³¹ (~**2.1B** visibility horizon). Too-old XID would flip "visible → invisible" = data appears to vanish ⇒ **freezing** prevents this. `autovacuum_freeze_max_age = 200M` forces anti-wraparound vacuum.
- **Long-running / idle-in-transaction** pins the **xmin horizon** → VACUUM can't clean or freeze → **bloat + march toward wraparound**. Kill it with `idle_in_transaction_session_timeout`, watch `pg_stat_activity` and `age(relfrozenxid)`.
- Oracle dodges all this with **UNDO + in-place updates** — different storage choice, same MVCC promise.