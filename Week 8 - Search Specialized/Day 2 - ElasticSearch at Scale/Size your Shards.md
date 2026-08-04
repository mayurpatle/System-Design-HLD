# Size Your Shards — Study Notes

**Source:** Elastic Docs → Deploy and manage → Production guidance → Run Elasticsearch in production → Performance optimizations → *Size your shards*
https://www.elastic.co/docs/deploy-manage/production-guidance/optimize-performance/size-shards
*(Applies to Elasticsearch 9.0+; largely unchanged from the 8.x version of the page.)*

**Framing:** A shard is the basic unit of storage. Every index is divided into one or more shards so data and workload spread across nodes. This page is about finding the middle of a two-sided failure mode — you can have too many shards, or shards that are too large, and both hurt.

---

## 1. The two failure modes

| Failure | Symptom |
|---|---|
| **Oversharding** — too many shards | Degraded search performance; in extreme cases the cluster becomes **unstable** |
| **Undersharding** — very large shards | Slower searches, and **much longer recovery** after a node failure |

The headline numbers, which are the two you actually memorize:

> **Aim for shard sizes between 10 GB and 50 GB.**
> **Keep documents per shard below 200 million.**

Everything else on the page is either the reasoning behind those, the operational knobs that enforce them, or the cleanup procedures when you've already broken them.

---

## 2. Shard distribution

Elasticsearch distributes shards using the **desired balance allocator**, which weighs heuristics across:

- data stream **writes**
- **shard counts**
- **disk usage**

Important nuance: **shard counts may end up deliberately uneven** in order to spread the actual *work* evenly. Equal shard counts per node is not the goal; equal load is.

An unbalanced cluster leads to performance degradation and instability — **watermark errors** being the common visible symptom. And a poorly specified sharding strategy causes **hot spotting**.

The division of responsibility: **Elasticsearch balances shards automatically, but you must configure indices with an appropriate number of shards and replicas** so that even distribution is possible in the first place. Auto-balancing can't fix a 1-shard index on a 6-node cluster.

If you use **data streams**, remember the extra layer: each data stream is backed by a *sequence of indices*, each of which may itself have multiple shards. Shard math multiplies fast.

---

## 3. Building a sharding strategy

**There is no one-size-fits-all strategy.** What works in one environment won't scale in another. A good strategy has to account for your infrastructure, use case, and performance expectations.

The prescribed method:

> **Benchmark your production data on production hardware, using the same queries and indexing loads you'd see in production.**

While testing configurations, use **Kibana's Elasticsearch monitoring tools** to watch stability and performance. Elastic points to their "quantitative cluster sizing" methodology talk for the full approach.

One reminder that's easy to skip past: **node performance is often limited by the underlying storage**, so storage tuning for indexing and search is a prerequisite, not an afterthought. Shard sizing won't rescue slow disks.

---

## 4. Sizing considerations — why the numbers are what they are

### 4.1 Searches run on a single thread per shard

Most searches hit multiple shards. **Each shard runs its portion of the search on one CPU thread.** A shard can run several concurrent searches, but a search spanning a large number of shards can **exhaust the node's search thread pool**, producing low throughput and slow searches.

This is the core mechanical reason oversharding hurts: shard count is directly a *concurrency demand* on every single query. Fan-out isn't free parallelism — past a point it's queueing.

### 4.2 Every index, shard, segment, and field carries overhead

- Every index and shard consumes memory and CPU. **In most cases a small set of large shards uses fewer resources than many small ones.**
- **Segments drive shard resource usage.** Most shards hold several segments, and Elasticsearch keeps some **segment metadata in heap** so it's fast to reach during search. As a shard grows, segments **merge into fewer, larger ones**, which *reduces* the heap held for metadata. (So shard growth is not monotonically more expensive — merging works in your favor.)
- **Every mapped field costs memory and disk.** By default, Elasticsearch auto-creates a mapping for every field in every document; you can turn that off and control mappings yourself.
- **Each segment needs a small amount of heap per mapped field** — including a copy of the field name, encoded as ISO-8859-1 where possible, otherwise UTF-16.

That last item is usually negligible, but it becomes real when you have **high segment counts × high field counts × long field names**. Worth knowing: field naming conventions have a (small) heap cost at scale.

### 4.3 Automatic balancing happens *within a data tier*

Nodes are grouped into **data tiers**. Within a tier, Elasticsearch spreads an index's shards over as many nodes as it can. Adding a node or losing one triggers automatic rebalancing across the tier's remaining nodes.

The scope matters: balancing is **intra-tier**, not cluster-wide.

---

## 5. Best practices

### 5.1 Delete indices, not documents

Deleted documents are **not immediately removed from disk** — they're marked as deleted on each affected shard and keep consuming resources until a periodic **segment merge** removes them.

Deleting a whole index, by contrast, lets Elasticsearch **drop it from the filesystem immediately** and reclaim resources at once.

### 5.2 Use data streams and ILM for time series data

Data streams store time series data across multiple time-based backing indices; **ILM** manages them automatically.

The key mechanism is **automatic rollover** — a new write index is created when the current one crosses a threshold on any of:

- `max_primary_shard_size`
- `max_age`
- `max_docs`
- `max_size`

ILM can then delete indices when they're no longer needed.

**ILM also makes the sharding strategy mutable over time**, which is the real win given that shard count is fixed at index creation:

| Goal | Lever |
|---|---|
| Fewer shards on new indices | Change `index.number_of_shards` in the data stream's matching **index template** |
| Larger shards / fewer backing indices | Raise the ILM policy's **rollover threshold** |
| Indices spanning shorter intervals | Accept more shards, but offset by lowering `min_age` in the **delete phase** so old indices go sooner |

> **Every new backing index is an opportunity to further tune your strategy.**

That sentence is the whole philosophy: with rollover, sharding decisions stop being permanent.

### 5.3 The size targets, in detail

There is **no hard limit** on a shard's physical size, and a shard can in theory hold just over two billion documents (see §7.2). But experience says:

- **10 GB – 50 GB** works well for many use cases…
- …**as long as documents per shard stays below ~200 million.**

The tradeoff restated from both ends: searching a thousand 50 MB shards is substantially more expensive than searching one 50 GB shard holding the same data — but very large shards also slow searches and take longer to recover.

Larger shards may be fine depending on your network and use case; smaller shards may suit certain use cases (Enterprise Search-style workloads, for instance).

**Enforce it via ILM rollover:**

```
max_primary_shard_size: 50gb     # ceiling — avoid shards >50GB
min_primary_shard_size: 10gb     # floor  — avoid shards <10GB
```

**Check current sizes:**

```
GET _cat/shards?v=true&h=index,prirep,shard,store&s=prirep,store&bytes=gb
```

`pri.store.size` gives the combined size of an index's primary shards.

### 5.4 Fixing an oversized shard (important operational detail)

**Shards are immutable — their size is fixed in place.** You cannot resize a shard. The index must be **copied** with corrected settings, which means first ensuring you have enough free disk for the copy.

Two valid routes:

1. **Split Index API** — increase the number of primary shards.
2. **Create a destination index with corrected settings, then Reindex** into it.

**Explicitly insufficient:** restoring a snapshot, or cloning the index. Neither changes shard sizing — they reproduce the existing shard layout.

Afterwards: delete the source index, and optionally **create an alias** on the destination bearing the source index's name so existing clients keep working.

### 5.5 Master node heap: 1 GB per 3,000 indices

The number of indices a master node can manage scales with its heap. Exact requirements depend on mapping size and shards per index, but the rule of thumb:

> **Fewer than 3,000 indices per GB of heap on master nodes.**

So 4 GB of master heap → keep the cluster under ~12,000 indices. The same guidance applies to **non-dedicated** master-eligible nodes: reserve at least 1 GB of heap per 3,000 indices in the cluster.

**Critical caveat:** this defines an **absolute maximum**, not a performant configuration. Hitting the limit guarantees nothing about search or indexing performance. Data nodes still need adequate resources, and the single-thread-per-shard and per-shard-overhead considerations still apply.

```
GET _cat/nodes?v=true&h=heap.max      # heap per node
GET _cat/shards?v=true                # shards per node
```

### 5.6 Stay within cluster shard limits

Hard limits enforced by the cluster:

- **1,000 non-frozen shards per node**
- **3,000 frozen shards per dedicated frozen node**

Provision enough nodes of each type for the shard count you need.

### 5.7 Allow enough heap for field mappers and overheads

Mapped fields consume heap on every node, and **extra** heap on data nodes. The page gives an actual budgeting procedure:

**Step 1 — mapping metadata in cluster state.** Every node holds a copy of the cluster state, which includes field mappings for every index. Measure its heap cost:

```
GET _cluster/stats?human&filter_path=indices.mappings.total_deduplicated_mapping_size*
```

**Step 2 — per-node heap and field overhead.** Data nodes carry *additional* per-mapped-field heap beyond the cluster-state copy. Non-data nodes may report zero:

```
GET _nodes/stats?human&filter_path=nodes.*.name,nodes.*.indices.mappings.total_estimated_overhead*,nodes.*.jvm.mem.heap_max*
```

**Step 3 — add baseline and workload headroom.** Beyond those two, allow for Elasticsearch's baseline usage plus indexing, searches, and aggregations. **0.5 GB of extra heap suffices for many reasonable workloads** — less for very light ones, more for heavy ones.

**Worked example from the docs:**

| Component | Heap |
|---|---|
| Cluster state field information | 1.0 GB |
| Estimated per-field overhead on the data node | 1.0 GB |
| Other overheads | 0.5 GB |
| **Total required** | **2.5 GB** |
| Node's configured heap max | 4 GB → **sufficient** |

If heap is insufficient: **avoid unnecessary fields**, scale up the cluster, or redistribute shards.

### 5.8 Avoid node hotspots

Too many shards on one node makes it a hotspot — especially bad when a single node holds many shards of a **high-indexing-volume** index.

Explicit cap:

```json
PUT my-index-000001/_settings
{ "index": { "routing.allocation.total_shards_per_node": 5 } }
```

### 5.9 Avoid unnecessary mapped fields

Dynamic mapping creates a mapping for **every field in every document** by default. Each mapped field means on-disk data structures for search/retrieval/aggregation, plus in-memory details. Much of that is wasted when the field is never queried or aggregated.

Recommended alternatives:

- **Explicit mapping** instead of dynamic mapping — don't create fields you'll never use.
- **`copy_to`** to consolidate fields that are typically used together, at index time.
- **Runtime fields** for rarely-used fields — computed at query time, no index-time cost.

Diagnostics:

- **Field usage stats API** — which fields are actually being used.
- **Analyze index disk usage API** — disk consumed per mapped field.

Note the docs' reminder: unnecessary fields cost **memory as well as disk**, so disk analysis alone understates the problem.

---

## 6. Reducing shard count on an already-oversharded cluster

Five remedies, roughly in order of how disruptive they are:

### 6.1 Make indices cover longer time periods
If retention allows, **avoid `max_age` in the rollover action** — use `max_primary_shard_size` instead, so you don't create empty indices or many tiny shards. If you must use `max_age`, **increase it** (daily → weekly → monthly).

### 6.2 Delete empty or unneeded indices
Rolling over on `max_age` can inadvertently create **indices with zero documents** — no benefit, but real resource cost. Find them and delete them:

```
GET _cat/count/my-index-000001?v=true
DELETE my-index-000001
```

### 6.3 Force merge during off-peak hours
For indices you no longer write to, **force merge** combines small segments into larger ones, cutting shard overhead and improving search speed. But force merges are **resource-intensive** — run them off-peak.

```
POST my-index-000001/_forcemerge
```

### 6.4 Shrink an existing index
For read-only indices, the **shrink index API** reduces shard count. ILM also has a **shrink action** for the warm phase.

### 6.5 Combine smaller indices
Use **reindex** to merge indices with similar mappings. For time series, roll several short-period indices into one longer-period index, then delete the originals:

```json
POST _reindex
{
  "source": { "index": "my-index-2099.10.*" },
  "dest":   { "index": "my-index-2099.10" }
}
```

---

## 7. Troubleshooting the two classic errors

### 7.1 `this action would add [x] total shards, but this cluster currently has [y]/[z] maximum shards open`

Caused by `cluster.max_shards_per_node`, which caps open shards cluster-wide.

**Short-term** — if you're confident it won't destabilize things, raise it temporarily:

```json
PUT _cluster/settings
{ "persistent": { "cluster.max_shards_per_node": 1200 } }
```

**Long-term** — add nodes to the oversharded data tier, or reduce shard count per §6. Then **reset the setting to null**.

```
GET _cluster/stats?filter_path=indices.shards.total     # current shard count
```

The docs are emphatic that raising the limit is a stopgap, not a fix. Treat it as buying time.

### 7.2 `Number of documents in the shard cannot exceed [2147483519]`

Each shard is a separate Lucene index and inherits Lucene's **`MAX_DOC` limit of 2,147,483,519** — that's `(2^31) − 129`.

Two subtleties that matter:

- The limit applies to **`docs.count` + `docs.deleted`** as reported by the Index stats API. **Deleted documents count against it.**
- This differs from the **Count API**, which excludes nested documents and doesn't count deletions. Don't diagnose with the Count API.

**Mitigation** (off-peak):

```
POST my-index-000001/_forcemerge?only_expunge_deletes=true
```

This runs asynchronously and is monitorable via the Task Management API. It only helps if a **meaningful portion of the shard is deleted documents** — if `docs.count` alone is already near the limit, expunging deletes won't save you, and you need to **split or reindex into more shards**.

Other options: delete unneeded documents, split, or reindex.

**Prevention:** roll over or re-shard **before** shards approach the recommended per-shard document count. The 200M recommendation exists precisely so you never come near the 2.1B hard ceiling — there's roughly a **10× safety margin** built into the guidance.

**Check proximity to the limit:**

```
GET my-index-000001/_stats?level=shards&filter_path=indices.*.shards.*.routing,indices.*.shards.*.docs
```

---

## 8. Quick-reference table

| Thing | Value |
|---|---|
| Target shard size | **10–50 GB** |
| Target docs per shard | **< 200 million** |
| Hard Lucene per-shard doc limit | **2,147,483,519** (`docs.count` + `docs.deleted`) |
| Indices per GB of master heap | **< 3,000** |
| Non-frozen shards per node | **1,000** (`cluster.max_shards_per_node`) |
| Frozen shards per dedicated frozen node | **3,000** |
| Extra heap for baseline + workload | **~0.5 GB** |
| ILM rollover ceiling | `max_primary_shard_size: 50gb` |
| ILM rollover floor | `min_primary_shard_size: 10gb` |
| Per-index hotspot cap | `index.routing.allocation.total_shards_per_node` |

---

## 9. Practitioner's read

A few things worth pulling out that the page states plainly but doesn't emphasize:

1. **Single-thread-per-shard is the mechanism behind most oversharding pain.** If you internalize one causal chain, make it: more shards → more per-query fan-out → more search-thread-pool demand → queueing → latency. It reframes shard count as a concurrency budget rather than a storage decision.

2. **Shards are immutable, so sizing mistakes cost a full data copy.** Split or reindex — and neither snapshot-restore nor clone will help. Plan for the disk headroom *before* you need it.

3. **ILM converts an irreversible decision into a recurring one.** Shard count is fixed at index creation, which historically made it the scariest number in Elasticsearch. Rollover means you re-decide at every new backing index. If you're running time series data without data streams and ILM, this is the highest-leverage change available.

4. **Field count is a heap problem, not just a disk problem.** Mapping metadata lives in cluster state on every node, plus per-segment-per-field overhead on data nodes. Dynamic mapping on user-controlled data is the classic way to blow this up.

5. **The 200M document recommendation is a safety margin, not a performance cliff.** The real ceiling is Lucene's 2.1B. Sitting an order of magnitude below it means rollover latency, deleted-doc accumulation, and traffic spikes can't push you into a wall you can only exit via reindex.

6. **`cluster.max_shards_per_node` is a guardrail you should never be raising routinely.** If you find yourself bumping it more than once, the answer is nodes or fewer shards.