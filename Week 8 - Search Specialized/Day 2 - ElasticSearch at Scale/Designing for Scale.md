# Designing for Scale — Study Notes

**Source:** *Elasticsearch: The Definitive Guide*, Ch. 43 "Designing for Scale" (Gormley & Tong, published free by Elastic in their docs)

**Premise:** Elasticsearch scales from a laptop to hundreds of nodes with almost no change in experience, and small→large growth is nearly automatic. But large→very large needs deliberate design, and a handful of decisions made on day one are effectively irreversible. The chapter organizes everything around **how data flows through your system**, and treats two flow shapes as the important cases: **time-based data** (logs, event streams — relevance driven by recency) and **user-based data** (one corpus naturally partitioned per customer/tenant).

> ⚠️ This chapter is ES 1.x/2.x era. The *reasoning* is still excellent and still the standard mental model, but several APIs and defaults have changed. See the currency section at the end before applying any of it literally.

---

## 1. The shard is the unit of scale

A shard is a Lucene index; an Elasticsearch index is a collection of shards. Your application addresses the index, and ES routes requests to the right shards.

**The trap:** a one-shard index has no scale factor. Add a second node to a single-shard cluster and *nothing happens* — there's nothing to move. And you can't add shards after the fact, because shard count is baked into the routing formula:

```
shard = hash(routing) % number_of_primary_shards
```

Change the divisor and every document's location changes. So the only escape is a full reindex into a bigger index — expensive, and always needed at the worst possible moment.

### Overallocation

Create the index with more primaries than you currently need. Two primaries on one node behave identically to one primary from the application's point of view. Add a second node and ES **relocates one shard automatically, with zero downtime** — indexing and search keep working throughout. You doubled capacity by copying bytes across the network.

### Why shard-splitting isn't the answer

The chapter explains why ES (at the time) refused to support splitting a shard in place:

- It's essentially a reindex, far heavier than relocating a shard.
- It's **exponential** — 1→2→4→8. You can't add 50% capacity.
- It needs room for a second copy of the index, and by the time you realize you need to scale, you usually don't have that room.

Reindexing into a correctly-sized new index was the sanctioned workaround. *(This is one of the parts that has since changed — see currency notes.)*

---

## 2. "Kagillion shards" — the opposite mistake

The natural overcorrection: *"I don't know how big this gets, so I'll create 1,000 shards to be safe."*

Shards are not free:

| Cost | Why |
|---|---|
| **File handles, memory, CPU** | Each shard is a full Lucene index underneath |
| **Query fan-out** | Every search hits a copy of *every* shard. Fine when they're on separate nodes; bad when they're contending for the same node's resources |
| **Relevance quality** | **Term statistics are per-shard.** Scatter a little data across many shards and IDF estimates get noisy — scoring degrades |

That third point is the one people miss. Over-sharding doesn't just cost resources; it makes your **ranking worse**.

**The rule:** a little overallocation is good, a kagillion shards is bad. There's no absolute threshold — 100 rarely-touched shards may be fine while 2 heavily-loaded shards may be too many. It depends on size and usage.

**Scale in phases.** Build enough capacity to reach the next phase. Arriving there buys you time to design for the phase after. Don't try to provision for a future you can't yet describe.

---

## 3. Capacity planning: measure, don't guess

How many shards do you need? Unanswerable in general (hardware, document size and complexity, analysis chain, query shapes, aggregations, data model all matter) — but easy to answer **for your specific case**:

1. Stand up a **single node** on the hardware you'd use in production.
2. Create an index with your **real settings and analyzers**, one primary shard, no replicas.
3. Load **real documents** (or as close as you can get).
4. Run **real queries and aggregations**.
5. Push that single shard until it **breaks** — where "breaks" is your definition (50 ms p99? 5 seconds? your call).

Then extrapolate: `total data + growth headroom ÷ single-shard capacity = number of primary shards`.

**Important caveat from the chapter:** capacity planning should not be your *first* move. Look for inefficiency first — bad queries, insufficient RAM, swap left enabled. The book calls out users who jump straight to GC tuning and thread-pool adjustments while leaving wildcard queries in place.

---

## 4. Replicas: availability, read throughput, and load balancing

Replicas exist primarily for **failover** — if the node holding a primary dies, a replica is promoted.

Two things that are commonly misunderstood:

- **Replicas do not add indexing capacity.** At index time a replica does the same work as the primary; documents are indexed on the primary and then on every replica. More replicas = more write work, not less.
- **Replicas add *search* capacity only if you also add hardware.** Replicas can serve reads, so a search-heavy index benefits — but only if the replicas land on additional nodes. Adding replicas to the same boxes buys nothing.

### Replicas as a load-balancing knob

Search latency is bounded by your **slowest node**, so uneven shard distribution is a direct performance problem.

Worked example: 2 primaries + 1 replica = 4 shards. On 3 nodes that's 2/1/1 — one node doing double the work. Bump to **2 replicas** → 6 shards → an even 2/2/2 across three nodes. Bonus: you can now lose two nodes and still hold a full copy of the data.

Also noted: it doesn't matter whether a node holds primaries or replicas. They do the same work in different roles; there's no need to balance primaries specifically.

---

## 5. Multiple indices as a capacity escape hatch

Nothing restricts you to one index. A search fans out to all shards of an index; searching several indices just involves more shards.

> **Searching 1 index of 50 shards ≡ searching 50 indices of 1 shard.** Both hit 50 shards.

This is the escape hatch when you've under-provisioned: rather than reindexing everything into a bigger index, **create a new index for new data and search across both**.

### The two-alias pattern

Done with aliases, this is invisible to the application. You need **two** aliases, because search and indexing have different reach:

```
tweets_search  →  tweets_1          (search alias — can point at many indices)
tweets_index   →  tweets_1          (write alias — must point at exactly one)
```

When capacity runs out, create `tweets_2` and atomically flip:

```json
POST /_aliases
{ "actions": [
  { "add":    { "index": "tweets_2", "alias": "tweets_search" }},
  { "remove": { "index": "tweets_1", "alias": "tweets_index"  }},
  { "add":    { "index": "tweets_2", "alias": "tweets_index"  }}
]}
```

**Why two:** a search request can target multiple indices; an **indexing request can target only one**. So the search alias grows to cover both, while the write alias moves.

Gotcha: `GET` by ID also targets a single index, so document retrieval gets awkward in this setup — use an `ids` query or a multi-get across both indices.

---

## 6. Time-based data

Logs and activity streams behave nothing like a classic document corpus. The chapter's characterization:

- Document count grows **rapidly and often accelerates**.
- Documents are **almost never updated**.
- Searches overwhelmingly target **recent** data.
- **Documents lose value as they age.**

Index design should mirror that.

### Index per time frame

The naive approach — one big index plus periodic delete-by-query for anything older than 90 days — is **very inefficient**, because deletes are only *marks*; nothing is reclaimed until the containing segment is merged away.

Instead: **one index per time frame** — `logs_2014`, `logs_2014-10`, `logs_2014-10-24`. Granularity follows your volume, and you can change it whenever you like. Purging becomes `DELETE /logs_2013*` — ES just removes directories.

The real advantage is **you never have to make a hard up-front decision**. One shard per week today, five shards per day later. Every new time frame is a fresh chance to right-size.

Aliases smooth the seams: `logs_current` points at the index accepting writes; `last_3_months` spans the recent set. Both get updated as time rolls forward.

### Index templates

You don't have to pre-create indices — Logstash derives the index name from the event timestamp (`@timestamp` of 2014-10-01 → `logstash-2014.10.01`) and ES autocreates it. But autocreated indices would otherwise get defaults you don't want.

An **index template** matching a name pattern (`logstash-*`) applies settings, mappings, and even aliases to every index created under that pattern, manual or automatic. `order` controls precedence when templates overlap. Tomorrow's index can be given a different shard count than today's simply by updating the template.

Note the asymmetry: a template can *add* new indices to an alias, but **removing aged-out indices from that alias is still manual**.

### Retiring data — the graceful-aging ladder

Deletion is final, so the chapter lays out intermediate stages:

**1. Migrate to weaker hardware.** There's normally one **hot** index (today's) taking all writes and nearly all queries — it should sit on your best boxes. You tell ES which those are by tagging nodes with arbitrary attributes:

```
./bin/elasticsearch --node.box_type strong
```

Then pin allocation per index: today's index to `strong`, yesterday's moved to `medium` by updating `index.routing.allocation.include.box_type`. The tag name is arbitrary; the mechanism is shard allocation filtering. **This is the direct ancestor of hot/warm/cold tiering.**

**2. Optimize (force-merge) to one segment.** Yesterday's log data is static, so merging each shard down to a single segment makes it cheaper and faster to query. Two operational cautions:

- **Don't do it while the index is still on the strong boxes** — the I/O burst will swamp the nodes ingesting today's logs. Move first, merge second.
- Merging with replicas present merges the primary *and* every replica — wasted work. Drop replicas to 0, merge, restore replicas. Accept the temporary durability risk (or snapshot first).

**3. Close.** Older still, and almost never queried: `_close` the index. It stays in the cluster and consumes only disk. Reopening is far faster than restoring from backup. **Flush before closing** so the translog is empty — recovery on reopen is quicker.

**4. Archive.** Snapshot to shared disk or S3, then delete from the cluster.

---

## 7. User-based data (multi-tenancy)

The usual growth story: one index for one application, then other teams want in.

### Index per user — the default answer

ES supports multitenancy naturally: one index per user, all in the same cluster. Cross-user search is just a multi-index search. Per-index shard/replica counts let you size each tenant independently, and allocation filtering can pin busy tenants to stronger hardware.

**Explicit warning:** don't apply the default shard count to every tenant index. Think about how much data each actually holds — often one shard is right, and anything more is waste.

**Most users should stop here.** The rest of the section is for the exceptional case.

### Shared index — when you have thousands of small tenants

The motivating example: hosting search for thousands of email forums, where a few are huge and most are tiny. A dedicated single-shard index per tiny forum is pure overhead.

**Step 1 — filter.** One big index, `forum_id` indexed as a keyword, queries filtered on it. Works, and filter caching keeps it fast. But the posts of one forum are **scattered across all ten shards**, so every query still fans out to all ten.

**Step 2 — custom routing.** Routing defaults to `_id`, but you can override it. Route by `forum_id` and all of a forum's documents land on **one shard**; pass the same routing value at search time and the query touches only that shard. Fan-out collapses from ten to one.

You still need the filter — a shard holds many forums' posts, so routing narrows *which shard*, not *which documents*.

Multiple forums: comma-separated routing values plus a `terms` filter.

### Step 3 — faking index-per-user with aliases

Threading routing values and filters through every request is clumsy. Bind them to an **alias** instead:

```json
PUT /forums/_alias/baking
{
  "routing": "baking",
  "filter": { "term": { "forum_id": "baking" }}
}
```

Now `baking` behaves exactly like a dedicated index. Writes to it get the routing applied implicitly; reads hit one shard and are filtered automatically. The application never knows it's sharing.

### Step 4 — "one big user" graduation path

Eventually one forum gets popular and its shard becomes a hotspot. It needs its own index — and the alias indirection makes that migration clean:

1. Create `baking_v1` with an appropriate shard count for expected growth.
2. Migrate the documents out of the shared index (scan-and-scroll + bulk).
3. **Atomically repoint the alias** from `forums` to `baking_v1`. The switch is instantaneous; the application keeps talking to `baking` and notices nothing.
4. Delete the old documents from the shared index (delete-by-query with the original routing value).

The dedicated index drops the filter and routing entirely — default `_id`-based sharding is now correct.

**The payoff:** cheap resource sharing for the long tail, with a zero-downtime promotion path for whichever tenant outgrows it.

---

## 8. Scale is not infinite — the cluster state

Most scaling problems are solved by adding nodes. One resource isn't: **cluster state**.

Cluster state holds cluster-level settings, node membership, all indices with their settings/mappings/analyzers/aliases, and the shard→node allocation map.

Two properties make it a hard limit:

- It **exists in memory on every node**, including client/coordinating nodes. That's *how* any node can route a request straight to the data — every node knows where everything lives.
- **Only the master may update it**, and every update is **published to every node**. Bigger state = slower publication.

Reads don't change it. CRUD doesn't change it — *unless a request introduces a new field requiring a mapping update*, in which case the node holding the primary forwards the new mapping to the master, which republishes cluster-wide. Normally the state is static and not a bottleneck.

### Mapping explosion — the canonical failure

The chapter's example is a page-view counter implemented with **one field per unique referer URL**:

```json
POST /counters/pageview/home_page/_update
{ "script": "ctx._source[referer]++", "params": { "referer": "http://..." }}
```

Verdict: *catastrophically bad.* Millions of fields, all in cluster state, and each new referer triggers a republish to the whole cluster.

**Fix: model it as data, not schema.** Nested objects with a name field and a value field:

```json
"counters": [
  { "referer": "http://www.foo.com/links?bar=baz", "count": 2 },
  { "referer": "http://www.linkbait.com/article_3", "count": 10 }
]
```

More documents, but ES is built for documents. **Cluster state stays small and agile.** This is the single most transferable lesson in the chapter: *anything unbounded and user-supplied belongs in field values, never in field names.*

### When one cluster isn't enough

If node/index/mapping count genuinely outgrows a single cluster, split into multiple clusters and search across them.

---

## 9. Condensed decision checklist

- **Never create a one-shard index you might need to grow.** Overallocate a little; shard count is fixed at creation.
- **Don't overcorrect.** Shards cost handles, memory, CPU, query fan-out — and hurt relevance via per-shard term statistics.
- **Determine shard capacity empirically**: one node, one shard, real data, real queries, push to failure, extrapolate.
- **Fix inefficiency before capacity planning.** Wildcard queries and swap first; GC tuning last.
- **Replicas ≠ write capacity.** They buy failover, and read throughput only alongside new hardware. Use replica count to even out shard distribution across nodes.
- **Two aliases** (search + write) let you add indices for capacity without touching application code.
- **Time-based data → index per time frame.** Delete indices, never documents.
- **Age data down a ladder**: hot hardware → weaker hardware → force-merge → close → snapshot → delete.
- **Multi-tenant: index per tenant by default**, shared index + routing + filtered aliases only for a long tail of small tenants, with an alias-swap promotion path.
- **Guard the cluster state.** Unbounded field names are the classic cluster-killer; use nested key/value documents.

---

## 10. What has changed since (ES 2.x → current)

The architectural reasoning holds up; the mechanics have moved on considerably.

| In the chapter | Current reality |
|---|---|
| 5 primary shards by default | **1 primary shard** by default since ES 7 |
| Shard splitting is "a bad idea," reindex instead | **Split API** (ES 6+) and **Shrink API** now exist and are supported. Overallocation matters less than it did |
| `_optimize?max_num_segments=1` | **`_forcemerge`** |
| Manual `box_type` node tagging + per-index allocation filtering | Formalized as **data tiers** (`data_hot`, `data_warm`, `data_cold`, `data_frozen`) |
| Manual index-per-day + alias juggling | **Data streams**, the **rollover API**, and **ILM** automate the whole hot→warm→cold→delete ladder, including force-merge, shrink, freeze, and snapshot steps |
| Manual alias removal from `last_3_months` | ILM handles lifecycle transitions |
| `filtered` query with `query` + `filter` | Removed — use **`bool`** with a `filter` clause |
| `_all` field, mapping types, `_default_` mappings | All removed (types gone in ES 7) |
| Tribe nodes for cross-cluster search | Removed — **cross-cluster search (CCS)** replaces them |
| Cluster state fully republished on change | Publication is now **diff-based/incremental**, so the cost is lower — but the mapping-explosion advice stands, and `index.mapping.total_fields.limit` (default 1000) now enforces a hard guardrail |
| Delete-by-query as a core API | Removed in 2.x, reintroduced as `_delete_by_query` in 5.x |

Also worth knowing as modern rules of thumb that this chapter predates: keep shards roughly **10–50 GB**, and target **well under 20 shards per GB of JVM heap** per node.