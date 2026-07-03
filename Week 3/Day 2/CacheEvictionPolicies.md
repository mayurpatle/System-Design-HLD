# Cache Eviction Policies — Trade-offs, When to Use, Real Examples

Reference note for system design + interviews. Eviction = *which entry to drop when the cache is full*. This is distinct from cache **patterns** (cache-aside, write-through — see `cache-patterns.md`) and from **expiry** (TTL). For the W-TinyLFU deep dive and the ARC/LIRS comparison, see `w-tinylfu-eviction-comparison.md`.

The whole subject reduces to one question: **what does past access tell you about future access?** Each policy is a different bet.

- Recency bet → LRU, MRU, Clock
- Frequency bet → LFU, TinyLFU
- Both → SLRU, ARC, LIRS, W-TinyLFU
- No bet (cheap) → FIFO, Random

---

## The policies

### LRU — Least Recently Used
Evict the entry accessed longest ago. Bets on **temporal locality**: recently used → likely used again soon. O(1) with a hash map + doubly linked list (move-to-front on access, evict from tail).

- **Pros:** great default, matches most real workloads, simple, O(1).
- **Cons:** **not scan-resistant** — a single large sequential read (full table scan, batch job) evicts the whole working set. Every access mutates the list, so under heavy concurrency the list becomes a lock/contention hotspot.
- **When:** general-purpose cache, unknown workload, recency-heavy access. The safe default.
- **Real:** Redis `allkeys-lru` / `volatile-lru`; Guava cache; browser HTTP cache; the baseline everyone compares against.

```java
// O(1) LRU — the canonical interview implementation
class LRUCache<K,V> extends LinkedHashMap<K,V> {
    private final int cap;
    LRUCache(int cap){ super(cap, 0.75f, true); this.cap = cap; } // accessOrder=true
    protected boolean removeEldestEntry(Map.Entry<K,V> e){ return size() > cap; }
}
```

### LFU — Least Frequently Used
Evict the entry with the fewest accesses. Bets on **popularity**: hot stays hot (Zipfian / 80-20 traffic).

- **Pros:** excellent for stable, skewed popularity; keeps genuinely hot items that LRU would drop during a lull.
- **Cons:** **no aging by default** → yesterday's viral item hogs the cache forever ("cache pollution"). New items start at count 0 and get evicted before they can prove themselves (**one-hit-wonder / cold-start** problem). Needs per-entry counters + a min-frequency structure to stay O(1).
- **When:** popularity is stable and skewed; CDN hot-object retention. Almost always want the **aged** variant.
- **Real:** Redis `allkeys-lfu` — uses a probabilistic logarithmic counter (Morris counter) **with time decay**, so it's really "LFU with aging," precisely to fix the two cons above.

### FIFO — First In, First Out
Evict the oldest *inserted* entry, ignoring usage entirely.

- **Pros:** dead simple, no per-access bookkeeping, cheap.
- **Cons:** ignores how hot an entry is — happily evicts your most-used item because it was inserted first. Suffers **Bélády's anomaly** (a bigger cache can produce *more* misses).
- **When:** metadata cost must be near-zero; entries are roughly equivalent; dedup windows.
- **Real:** simple ring buffers, some hardware structures, message-dedup windows.

### Random Replacement (RR)
Evict a uniformly random entry.

- **Pros:** zero metadata, zero bookkeeping contention, trivially concurrent, **hard to attack adversarially**. Often within a few % of LRU's hit rate.
- **Cons:** no locality guarantees; can unluckily evict a hot item.
- **When:** extreme concurrency where LRU's list contention dominates; hardware; when simplicity wins.
- **Real:** many CPU caches (pseudo-random); Redis `allkeys-random`; sampled eviction (Redis actually *approximates* LRU/LFU by sampling N random keys and evicting the best — a pragmatic middle ground).

### MRU — Most Recently Used
Evict the *most* recently used entry. Counterintuitive, but correct when the item you just touched is the one you *won't* need again soon.

- **When:** **cyclic/looping scans larger than the cache** — in a repeated sequential sweep, the just-read block is the furthest from being reused. LRU is pessimal here; MRU is near-optimal.
- **Real:** database sequential scan buffers, some file-scan workloads.

### Clock / Second-Chance
An LRU *approximation*: a circular buffer with a reference bit per entry. On access, set the bit; on eviction, sweep the hand, clearing bits and skipping set ones, evicting the first with a clear bit. Avoids mutating a linked list on every read.

- **Pros:** LRU-like hit rate **without** per-access list updates → far better under concurrency and at OS scale.
- **Cons:** approximation, not true LRU.
- **When:** you want LRU behavior but can't pay LRU's per-access write cost.
- **Real:** Linux page cache (active/inactive list variant); PostgreSQL buffer manager (clock-sweep).

### SLRU — Segmented LRU
Split the cache into a **probation** segment and a **protected** segment. New entries enter probation; a second hit promotes them to protected; protected evictions demote to probation. Gives **scan resistance** — a one-time scan fills probation and never pollutes protected.

- **When:** as a building block, or directly when you need scan resistance cheaply.
- **Real:** the main region inside TinyLFU/Caffeine; InnoDB's buffer pool uses a young/old-sublist LRU with midpoint insertion (same idea — protect the hot set from scans).

### ARC — Adaptive Replacement Cache
Balances recency and frequency **adaptively** using two resident lists (seen-once / seen-many) plus non-resident "ghost" lists that remember evicted keys, auto-tuning the split.

- **Pros:** excellent, self-tuning, scan-resistant.
- **Cons:** needs ~2× metadata (ghost keys); **IBM-patented** (blocks OSS use).
- **Real:** ZFS ARC.

### LIRS — Low Inter-reference Recency Set
Ranks by the gap between successive accesses (inter-reference recency). Strongly **scan- and loop-resistant**.

- **Cons:** complex; needs ~3× bookkeeping for peak efficiency.
- **Real:** influences several DB buffer-pool designs.

### W-TinyLFU — Window TinyLFU
Admission-window LRU → large Segmented LRU, gated by a **frequency-sketch admission filter** (4-bit Count-Min Sketch, ~8 B/entry, with aging). Near-optimal hit rate, low footprint, **no ghost entries**, adapts window/main split via hill climbing.

- **When:** general-purpose in-process cache where you want best-in-class hit rate cheaply. Effectively the modern default on the JVM.
- **Real:** **Caffeine** (and therefore Spring's default cache and countless JVM services). See `w-tinylfu-eviction-comparison.md`.

### TTL / time-based (expiry, not capacity)
Not a victim-selection policy — evicts by **age/freshness** regardless of space. Usually combined with one of the above.

- **When:** correctness depends on freshness (stale data is wrong, not just cold).
- **Real:** DNS caches, session stores, CDN `max-age`, Redis `volatile-ttl`.

### Size/cost-aware (GDSF, GDS)
Weighs frequency against **object size and fetch cost** — evict big/cheap-to-refetch objects before small/expensive ones.

- **When:** entries vary wildly in size or re-fetch cost — i.e. **CDNs / web caches**, not uniform key-value.
- **Real:** Varnish, Squid, CDN edge caches.

---

## When to use what

| Situation | Reach for | Avoid |
|---|---|---|
| Unknown / general workload | LRU (or W-TinyLFU if available) | — |
| Stable, skewed popularity (Zipf, 80/20) | LFU **with aging** / W-TinyLFU | plain LFU |
| Scan-heavy / analytical / batch reads | SLRU, ARC, LIRS, W-TinyLFU (scan-resistant) | plain LRU |
| Looping access larger than the cache | MRU | LRU (pessimal here) |
| Extreme concurrency, LRU lock contention | Random, Clock, sampled eviction | strict LRU |
| OS/page-level, per-access cost matters | Clock / Second-Chance | true LRU |
| Objects vary in size & fetch cost (CDN) | GDSF / cost-aware | LRU/LFU by count |
| Freshness-critical data | TTL (+ LRU/LFU underneath) | capacity-only policy |
| Embedded / hardware / near-zero metadata | FIFO, Random | ARC/LIRS |
| Best hit rate, low footprint, in-process (JVM) | W-TinyLFU (Caffeine) | ARC (patent), LIRS (complexity) |

---

## Real systems → policy

- **Redis** — configurable *maxmemory-policy*: `noeviction`, `allkeys-lru`, `allkeys-lfu`, `volatile-lru`, `volatile-lfu`, `volatile-ttl`, `allkeys-random`, `volatile-random`. LRU/LFU are **sampled approximations** (default 5 keys), and LFU uses a decaying log counter.
- **Memcached** — slab allocator with a **segmented LRU** (HOT / WARM / COLD queues) per slab class, giving scan resistance without full ARC complexity.
- **Caffeine (Java)** — W-TinyLFU. The default you inherit via Spring Cache on the JVM.
- **Linux page cache** — Clock-like, two-list (active/inactive) LRU approximation.
- **InnoDB buffer pool** — LRU split into young/old sublists with midpoint insertion → scan resistance for `SELECT *`-style sweeps.
- **PostgreSQL** — clock-sweep buffer replacement (LRU approximation).
- **ZFS** — ARC.
- **CPU L1/L2/L3** — pseudo-LRU or random (hardware can't afford true LRU).
- **CDNs (Varnish / CloudFront / Squid)** — LRU + TTL, sometimes cost/size-aware for large objects.

---

## Interview framing

Don't answer "which policy is best" — answer **"what's the access pattern?"** Recency-biased → LRU family. Frequency-biased with a stable hot set → LFU-with-aging or TinyLFU. Both, or adversarial scans → a hybrid (W-TinyLFU / ARC / SLRU). Then name the cost axis: strict LRU has **per-access write contention**, LFU without aging **pollutes**, ARC/LIRS **buy hit rate with 2–3× metadata**, and Random/FIFO **buy simplicity with a hit-rate hit**. The senior-level move is recognizing that Redis and most production caches don't run *true* LRU/LFU at all — they **sample a handful of keys and pick the best victim**, trading a sliver of accuracy for O(1) and no global lock. If asked to implement one, they mean LRU: hash map + doubly linked list, O(1) get/put.