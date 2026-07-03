# Cache Patterns

Reference note for system-design + interview prep. Five patterns, each with a code sketch, a real example, and the failure mode to raise in an interview. Drill at the bottom.

The one distinction interviewers probe first: **who owns the miss/write logic — the application or the cache tier?** Cache-aside and write-behind put the app in control; read-through and write-through push it into the cache layer.

---

## 1. Cache-Aside (Lazy Loading)

The application talks to both cache and DB. On a read, it checks the cache first; on a miss it loads from the DB, populates the cache, and returns. On a write, it updates the DB and **deletes** (invalidates) the key rather than updating it in place. The cache only ever holds data someone actually asked for — hence "lazy."

```java
public Product getProduct(String id) {
    String key = "product:" + id;
    Product p = redis.get(key);
    if (p != null) return p;              // hit
    p = db.findById(id);                  // miss → load from source
    if (p != null) redis.setex(key, TTL, p);
    return p;
}

public void updateProduct(Product p) {
    db.save(p);
    redis.del("product:" + p.getId());    // invalidate, don't overwrite
}
```

**Real example:** Product catalog / detail page. Overwhelmingly read-heavy, writes are infrequent (price or inventory edits), and a few seconds of staleness on a description is acceptable.

**Failure mode (interview):** Two things to raise.
1. **Cache stampede / thundering herd** — a hot key expires and N concurrent requests all miss and hit the DB at once. Mitigate with single-flight/request coalescing (one loader repopulates while others wait), probabilistic early expiration, or TTL jitter.
2. **The delete-vs-write race** — a read miss loads the *old* value and writes it to cache just after a concurrent update deleted the key, leaving a stale entry that survives until TTL. This is the classic cache-aside inconsistency window. Mitigate with delete-after-write ordering, short TTLs, or delayed double-delete.

---

## 2. Read-Through

The cache sits inline on the read path and owns the load logic. The application only ever calls `cache.get(key)`; on a miss the cache provider itself fetches from the source via a configured loader. Same data flow as cache-aside, but the miss-handling lives in the cache layer, not scattered across app code.

```java
// Caffeine loading cache — the loader IS the read-through
LoadingCache<String, Product> cache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(5))
    .build(id -> db.findById(id));        // invoked automatically on miss

Product p = cache.get(id);                // transparently loads if absent
```

**Real example:** Read-mostly reference data — feature flags, currency/country lookups, config — behind Caffeine or a Redis provider with a loader configured. The app code stays clean because there's no explicit miss branch.

**Failure mode (interview):**
1. **Mass-expiry stampede on cold start** — after a deploy or cache flush, every key is cold and the first wave of traffic floods the DB. `refreshAfterWrite` with an async loader plus TTL jitter smooths this.
2. **Hard dependency on the read path** — the cache is now inline, so if the cache tier is down with no fallback, reads fail outright. You need an explicit fallback-to-source path.

*Say the distinction out loud:* cache-aside = **app** owns the miss; read-through = **cache** owns the miss.

---

## 3. Write-Through

Every write goes through the cache, which synchronously writes to the DB before acknowledging. Cache and DB are kept consistent at write time, so reads (usually paired with read-through) always hit a warm, correct cache.

```java
public void save(Product p) {
    String key = "product:" + p.getId();
    redis.setex(key, TTL, p);             // write cache
    db.save(p);                           // write DB synchronously
    // ack only after both succeed (wrap in a tx / compensation)
}
```

**Real example:** User profile or account settings — you want the value readable and consistent from cache the instant after it's saved, and writes are rare enough that the extra latency doesn't matter.

**Failure mode (interview):**
1. **No write speedup** — every write pays cache-write + DB-write on the critical path. You're buying read consistency, not write performance.
2. **Write amplification of cold data** — caching on every write pollutes the cache with entries no one reads. Combine with TTL or only cache on read.
3. **Partial failure** — cache write succeeds but DB write fails (or vice versa) → divergence. Needs a transaction or compensating action, otherwise the two stores drift.

---

## 4. Write-Behind (Write-Back)

Write to the cache, acknowledge immediately, and flush to the DB asynchronously in batches. Optimizes for write latency and throughput by decoupling the ack from the durable write.

```java
public void record(Event e) {
    cache.put(e.getId(), e);              // ack immediately
    writeQueue.offer(e);                  // enqueue for async flush
}

@Scheduled(fixedDelay = 500)
void flush() {
    List<Event> batch = drain(writeQueue, 1000);
    if (!batch.isEmpty()) db.batchInsert(batch);   // coalesced write
}
```

**Real example:** High-write ingestion — view counters, like counters, telemetry, or a location-tracking pipeline that buffers points in Redis and batch-flushes to Cassandra. Batching turns thousands of tiny writes into a handful of large ones.

**Failure mode (interview):**
1. **Data-loss window** — if the cache node dies before a flush, un-flushed writes are gone. If the data must survive, back the buffer with something durable (Kafka, a WAL, or AOF persistence) rather than volatile memory.
2. **Read-your-writes staleness** — the DB lags the cache, so anything reading the source directly sees old data.
3. **Back-pressure** — if the DB can't keep up with the incoming write rate, the queue grows unbounded. You need bounded queues, drop/spill policy, or flow control.

---

## 5. Refresh-Ahead

Proactively refresh popular keys *before* they expire, serving the current value while an async reload happens in the background. Hot data effectively never causes a miss. It's predictive — you're betting a key that was recently read will be read again.

```java
LoadingCache<String, Feed> cache = Caffeine.newBuilder()
    .refreshAfterWrite(Duration.ofSeconds(30))   // async refresh trigger
    .expireAfterWrite(Duration.ofMinutes(5))     // hard expiry backstop
    .build(id -> db.loadFeed(id));

// Access after 30s: returns the current (slightly stale) value now,
// and kicks off a background reload so the next read is fresh.
```

**Real example:** Home timeline / trending feed for hot users — keep the hottest N keys continuously warm so p99 read latency stays flat and the DB never sees a miss spike for celebrity content.

**Failure mode (interview):**
1. **Wasted refresh work** — refreshing keys that won't be read again burns DB and CPU. Only refresh recently-accessed keys, or you're paying prediction cost for nothing.
2. **Wrong prediction = double cost** — if the guess is wrong you pay for the refresh *and* still take a miss later.
3. **Refresh storms** — many hot keys refreshing on the same schedule spike the source. Jitter the refresh windows.

---

## Quick comparison

| Pattern | Miss/write owner | Optimizes for | Consistency | Watch out for |
|---|---|---|---|---|
| Cache-aside | App | Read latency | Eventual | Stampede, delete-vs-write race |
| Read-through | Cache | Read latency + clean code | Eventual | Cold-start stampede, hard dep |
| Write-through | Cache | Read consistency after write | Strong-ish | Slow writes, cold-data pollution |
| Write-behind | App | Write throughput/latency | Weak | Data-loss window, back-pressure |
| Refresh-ahead | Cache | p99 on hot keys | Slightly stale | Wasted work, refresh storms |

---

## Drill: "Design caching for X" — pick a pattern, justify in 60 seconds

Speak these out loud. Each names the pattern, gives one-line reasoning, and pre-empts the follow-up.

**1. Twitter home feed**
Refresh-ahead over per-user materialized timelines, with cache-aside as the base. Reads dominate by orders of magnitude and a few seconds of staleness is fine, so I materialize each user's timeline in Redis and keep the hottest ones warm with refresh-ahead so celebrity/high-traffic feeds never take a cold miss. Follow-up I'd raise myself: the celebrity fan-out problem — for accounts with millions of followers I don't fan out on write, I merge their posts in at read time. Risk to flag: trending-topic stampede, handled with request coalescing.

**2. Product page**
Cache-aside with a short TTL and invalidate-on-write. Read-mostly with occasional price/inventory edits, so I cache the assembled page (or product aggregate), delete the key on any update, and lean on TTL as a safety net. I'd split TTLs: minutes for the description, seconds or event-driven invalidation for inventory/price where staleness is customer-visible. Failure I'd name: the delete-vs-write race, mitigated with delete-after-write ordering.

**3. Login session**
Cache-as-record-of-truth, write-through if it must also persist. The session lives in Redis with TTL equal to the session expiry, so every request reads it in one fast hop and expiry is automatic. If policy requires durable session storage I go write-through so the session is immediately readable after login. Failure I'd name: a Redis outage logs everyone out — so I run it replicated with persistence, and treat token re-issue as the fallback.

**4. Leaderboard**
Write-behind on a Redis sorted set. Score updates are high-frequency and reads need ranked slices fast, so I increment scores directly in a Redis ZSET (O(log n) updates and range/rank queries) and asynchronously persist to a durable store in batches. Reads never touch the DB. Failure I'd name: durability — a Redis crash loses un-persisted score deltas, so I back it with periodic snapshots or an append log and reconcile on recovery.

**5. Audit log**
Write-behind, but backed by a durable log (Kafka), not volatile cache — and I'd challenge the premise that a *cache* is even the right tool. Audit is write-heavy and read-cold, so there's nothing to cache on the read side; the caching-shaped part is buffering writes for batched inserts. Pure in-memory write-behind's data-loss window is unacceptable for compliance data, so the buffer is a durable append log (Kafka) that batch-flushes to the store. The nuance to voice: recognizing when "add a cache" is the wrong answer scores as much as any pattern choice.