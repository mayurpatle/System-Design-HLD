# Redis as an LRU Cache + Redis Persistence

Notes from two Redis doc chapters: **Key eviction** ("Using Redis as an LRU cache") and **Redis persistence**. For the general theory behind LRU/LFU/etc., see `cache-eviction-policies.md` — this note is Redis-specific: the actual directives, the *approximation* tricks, and the durability trade-offs.

---

# Part 1 — Using Redis as an LRU Cache (Key Eviction)

## maxmemory — the ceiling
`maxmemory` sets how much memory Redis may use for the dataset. Set it in `redis.conf` or at runtime with `CONFIG SET maxmemory 100mb`. `maxmemory 0` means **no limit** — the default on 64-bit builds (32-bit builds have an implicit ~3GB cap).

## How eviction actually runs
The loop is important to state precisely in an interview: a client runs a write → Redis checks whether memory now exceeds `maxmemory` → if so it evicts keys per the policy until back under the limit → next command, repeat. So Redis **continuously crosses the boundary** — briefly over, then evicts back under. A single command that allocates a lot (e.g. a big set intersection stored to a new key) can overshoot the limit noticeably for a moment.

## The eviction policies (`maxmemory-policy`)
Eight policies, split by *scope* (all keys vs. only keys with a TTL) and *strategy* (LRU / LFU / random / TTL):

| Policy | Scope | Evicts |
|---|---|---|
| `noeviction` | — | Nothing; returns errors on writes that need memory (reads and `DEL` still work). On a replicated setup this applies to the primary. |
| `allkeys-lru` | all keys | Least recently used |
| `allkeys-lfu` | all keys | Least frequently used (Redis 4.0+) |
| `allkeys-random` | all keys | A random key |
| `volatile-lru` | keys with TTL | Least recently used among those with an expire set |
| `volatile-lfu` | keys with TTL | Least frequently used among those with an expire set |
| `volatile-random` | keys with TTL | A random key with an expire set |
| `volatile-ttl` | keys with TTL | The one with the shortest remaining TTL |

Key gotcha: the `volatile-*` policies **fall back to `noeviction` behavior if no keys have a TTL** — i.e. writes start erroring even though the cache looks evictable. Also, setting a TTL on every key *costs memory*, so `allkeys-lru` is more memory-efficient than `volatile-lru` when your goal is just "evict under pressure." The docs note that mixing a cache and a persistent keyset in one instance (the usual reason to reach for `volatile-*`) is usually better solved by running **two Redis instances**.

## LRU here is *approximated* — the senior-level point
Redis does **not** keep a true global LRU linked list (too expensive at scale). Instead, on each eviction it **samples `maxmemory-samples` keys at random** (default **5**) and evicts the best victim from that sample. Each object header carries a **24-bit LRU clock** (second resolution) updated on access. Raise `maxmemory-samples` to 10 to approach true LRU at some CPU cost; tune it live with `CONFIG SET maxmemory-samples 10`. This sampling approach is *the* thing to name in an interview — most production caches don't run textbook LRU.

## LFU mode (Redis 4.0+)
LFU is approximated the same way, but with a **Morris counter** — a probabilistic counter using just a few bits per object to estimate access frequency — combined with a **decay** so old-but-idle keys lose their frequency over time. This fixes classic LFU's two failures: stale hot keys hogging the cache, and brand-new keys getting evicted before they build a count. Tuned via `lfu-log-factor` (how fast the counter saturates) and `lfu-decay-time` (how fast it ages).

## Choosing a policy
- `allkeys-lru` — expect a **power-law / Zipfian** distribution (a hot subset dominates). Best pick if unsure.
- `allkeys-lfu` — popularity is **stable and skewed** and you want frequency to win over recency.
- `allkeys-random` — **cyclic** access where all keys are scanned, or a **uniform** distribution (recency/frequency carry no signal).
- `volatile-ttl` — you want to hand Redis a hint about eviction order by assigning **different TTLs** to cache objects.

You can change the policy at runtime and watch the effect.

## Monitoring (from `INFO stats`)
- `evicted_keys` — high value → the policy is evicting the wrong things too often; try `allkeys-lru`.
- `keyspace_hits` / `keyspace_misses` — the ratio you're actually optimizing.
- `expired_keys` — high with low evictions → your TTLs may be too short.
- `used_memory_dataset` — memory used for cached data; if above `maxmemory`, the gap is roughly the overhead.
- Per-key: `OBJECT IDLETIME key` (seconds since access, LRU) and `OBJECT FREQ key` (counter, LFU).

---

# Part 2 — Redis Persistence

Redis serves from RAM, so a restart wipes everything unless data is persisted to disk. Redis offers **RDB** (snapshots), **AOF** (write log), **both** (hybrid), or **none** (pure cache). The whole chapter is about one axis: **tolerance for data loss vs. performance cost.**

## RDB — point-in-time snapshots
Periodically dumps the whole dataset to a compact binary `.rdb` file. Mechanism: Redis `fork()`s a child process that writes the snapshot using **copy-on-write**, so the parent keeps serving clients and never does the disk I/O itself. Configured with **save points** — `save <seconds> <changes>` — e.g. snapshot if ≥1000 keys changed in 60s; multiple save points stack. File location via `dbfilename` / `dir`.

**Pros:** compact single file → ideal for backups and disaster recovery (archive hourly/daily, ship to S3 or another data center); **faster restarts** with large datasets than replaying AOF; supports **partial resync** on replicas after restart/failover; minimal steady-state impact since the parent only forks.

**Cons:** **data-loss window** — you lose everything since the last snapshot (typically minutes), so it's *not* the choice when minimizing loss on a power outage matters. `fork()` on a large dataset with a slow CPU can **stall the server for milliseconds up to ~a second**.

## AOF — append-only file
Logs **every write command** in the Redis protocol format; on restart Redis replays the log to rebuild the dataset. The file is rewritten in the background when it grows too large (auto-trigger when it roughly doubles since the last rewrite).

The durability knob is **`appendfsync`**:
- `always` — fsync after every write. Safest, slowest.
- `everysec` — fsync once per second (default). fsync runs on a background thread; worst case you lose ~1 second of writes. The usual sweet spot.
- `no` — let the OS decide when to flush. Fastest, least durable.

**Since Redis 7.0** AOF is **multi-part**: a base file (an RDB-format snapshot, via `aof-use-rdb-preamble`) plus incremental files, all living in the directory set by `appenddirname`. This cuts memory and I/O during rewrites.

**Pros:** far more durable than RDB; tunable durability without sacrificing the rewrite cadence; the log is inspectable.
**Cons:** larger files than RDB; slower restarts (replay vs. load); somewhat more CPU/I/O overhead.

## Hybrid (RDB + AOF) — the common production choice
Enable both. On restart Redis loads the **AOF**, since it's guaranteed to be the most complete. You get AOF's write-by-write durability during normal operation *and* RDB's fast restart + easy backups. Recommended for most durable deployments.

## No persistence
Perfectly valid for a **pure cache** where losing everything on restart is acceptable (the data is a copy of a source of truth). Often paired with an eviction policy from Part 1 and no `save` points.

## Backup notes
- Ship an RDB snapshot **off the machine / out of the data center at least daily**.
- Backing up AOF (7.0+): copy/tar the `appenddirname` directory — but **not during a rewrite**, or the backup may be invalid. Disable auto-rewrite first (`CONFIG SET auto-aof-rewrite-percentage 0`), don't trigger `BGREWRITEAOF`, and confirm `aof_rewrite_in_progress` is `0` in `INFO persistence`.

---

## Interview framing

Two clean stories to have ready.

**Eviction:** "Redis doesn't run true LRU — it samples a handful of keys per eviction and picks the oldest, using a 24-bit access clock in each object header. LFU is similar but uses a decaying Morris counter to avoid stale-hot-key pollution. You pick `allkeys-lru` for Zipfian traffic, `allkeys-random` for cyclic scans, `volatile-ttl` when you want to hint eviction order — and you *don't* mix a cache and a persistent keyset in one instance."

**Persistence:** "It's a data-loss-vs-performance dial. RDB forks and writes a compact snapshot with copy-on-write — great for backups and fast restarts, but you can lose minutes on a crash. AOF logs every write with an `appendfsync` policy — `everysec` is the sweet spot at ~1s worst-case loss. Production usually runs both: AOF for durability, RDB for fast restart and backups, and Redis loads the AOF on restart because it's the most complete. And the fork cost is the thing to watch on large datasets."

---
Sources: Redis docs — *Key eviction* (`redis.io/docs/latest/develop/reference/eviction/`) and *Redis persistence* (`redis.io/docs/latest/operate/oss_and_stack/management/persistence/`).