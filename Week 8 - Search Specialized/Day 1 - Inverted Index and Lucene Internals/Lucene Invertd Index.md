# Lucene's Inverted Index — Study Notes

**Source:** *Elasticsearch from the Bottom Up, Part 1* — Alex Brasetvik, Elastic Blog (Sept 2013)
https://www.elastic.co/blog/found-elasticsearch-from-the-bottom-up

**Framing:** The article works upward from the lowest useful abstraction — the inverted index — to Elasticsearch shards and indices. The goal isn't Lucene's source code, but understanding the data structures well enough to predict *what searches will be fast, what will be slow, and why indexing behaves the way it does*.

---

## 1. The core data structure

An inverted index is a mapping from **term → list of documents containing that term** (optionally with positions inside each document). It is the inverse of a "forward index," which would map a document to the terms it contains.

Two physical pieces:

| Piece | What it holds | Why it matters |
|---|---|---|
| **Dictionary** | Every distinct term, kept **sorted** | Sorted order makes term lookup a binary-search-style operation |
| **Postings** | For each term, the documents (and optionally positions/frequencies) where it occurs | This is the bulk of the data; heavily compressed |

**Worked example from the article.** Three documents — "Winter is coming.", "Ours is the fury.", "The choice is yours." After lowercasing, stripping punctuation, and splitting on whitespace, you get a sorted dictionary (choice, coming, fury, is, ours, the, winter, yours) where each term points at the doc IDs it appeared in.

### How a query executes

1. Resolve each query term against the **dictionary** to find candidate terms.
2. Pull the corresponding **postings** lists.
3. Combine them set-wise:
   - `AND` → **intersection** of postings lists
   - `OR` → **union** of postings lists
4. More complex queries (phrase, proximity, fuzzy) follow the same shape — dictionary first, then postings/positions — just with more work at each stage.

---

## 2. The term is the unit of search — and this is the whole game

The single most important consequence in the article: **whatever you decide a "term" is at index time defines the set of queries you can answer efficiently at query time.** Analysis is not cosmetic preprocessing; it's a schema decision.

Because the dictionary is sorted, the operation that is cheap is **prefix lookup**:

- Find terms starting with `"c"` → **O(log n)** — walk to the right spot in the sorted dictionary.
- Find terms *containing* `"ours"` → **O(n)** — you must scan every term, since `yours` also contains it. Prohibitively expensive at real index sizes.

So the design pattern is: **transform every search problem into a string-prefix problem at index time.**

### The transformation catalogue

The article lists several tricks, ordered roughly from obvious to clever:

| Problem | Index-time transformation | Then search for |
|---|---|---|
| Suffix match — everything ending in `"tastic"` | Index the **reversed** term (`fantastic` → `citsatnaf`) | Prefix `citsat` |
| Arbitrary substring | Split into **n-grams**: `yours` → `^yo`, `you`, `our`, `urs`, `rs$` | The `our` / `urs` grams |
| Compound-word languages (German, Norwegian) | **Decompounding**: `Donaudampfschiff` → {donau, dampf, schiff} | `schiff` |
| Geo point search — e.g. (60.6384, 6.5017) | Encode as a **geohash** → `u4u8gyykk`; longer string = finer precision | Prefix of the geohash = a bounding box |
| Phonetic / name matching | **Metaphone**-style encoding: `Smith` → {SM0, XMT}, `Schmidt` → {XMT, SMT} | Shared phonetic code |
| Numeric & date **range** queries | Lucene emits multiple terms at varying precision in a **trie-like** structure: 123 → `1`(hundreds), `12`(tens), `123` | Range [100,199] = the `1`-hundreds term. Note this is *not* the same as prefix `"1"`, which would wrongly match 1234 |
| Fuzzy / "did you mean?" | Build a **Levenshtein automaton** and use it to walk the dictionary | Terms within edit distance k |

The geohash and numeric-trie tricks are the ones worth internalizing — they show that "prefix search" is a general-purpose primitive once you control the encoding.

---

## 3. Building the index: four competing priorities

When constructing inverted indexes, four things pull against each other:

1. **Search speed**
2. **Index compactness**
3. **Indexing speed**
4. **Latency until new changes become visible**

Search speed and compactness reinforce each other — a smaller index means less data scanned and a better chance the hot parts sit in page cache/memory. But both are paid for with **indexing speed**.

### Compression

Postings lists get large, so Lucene compresses aggressively. Two named techniques:

- **Delta encoding** — store gaps rather than absolute doc IDs: `[42, 100, 666]` becomes `[42, 58, 566]`. Gaps are smaller numbers than the values themselves.
- **Variable-byte encoding** — small numbers occupy a single byte instead of a fixed 4 or 8.

(Delta encoding is also *why* postings must be stored in sorted doc-ID order — that ordering is what makes the gaps small.)

### Immutability — the central design decision

Compact, tightly-packed structures cannot be efficiently mutated. Lucene's answer is not to try: **written index files are never updated.** This is the opposite of a B-tree, where in-place update is the point and you tune a fill factor to leave room for it.

Consequences, in order of practical importance:

- **Deletes are the one exception** — a deleted doc is *marked* in a separate deletion file, which is just a cheap-to-update bitmap. The index structures themselves are untouched. The document's data is still on disk; it's filtered out at query time.
- **An update = delete + full re-insert.** Which means **updating a document is more expensive than the original insert was.**
- **Therefore: don't store rapidly-changing values (counters, hot state) in Lucene.** There is no in-place field update. This is the single most actionable design rule in the section.

### Write path

New/updated documents are **buffered in memory** first. Eventually the buffered content is **flushed** to disk as complete files — and those files constitute one **segment**.

⚠️ Terminology trap: "flush" here is the *Lucene* meaning (write a segment). Elasticsearch's own flush operation is a bigger thing — a Lucene commit plus translog handling.

Flush timing is a tuning tradeoff between how fast changes must become visible, how much memory you can give the buffer, and available I/O. Bigger buffers are generally better for indexing throughput, right up to the point where your I/O can no longer keep up.

---

## 4. Segments

**A Lucene index = a set of immutable segments**, each essentially a self-contained mini-index.

### Query-time behaviour

A search runs **against every segment**, deletions are filtered out, and per-segment results are merged. This scales badly as segment count climbs — hence merging.

### Merging

Lucene periodically **merges** segments per a configurable merge policy as new ones appear. Merges are where **documents marked deleted are finally physically discarded**.

Counter-intuitive but important: **adding documents can shrink the index on disk**, because the addition triggers a merge that reclaims deleted docs.

In Elasticsearch the defaults are generally sane; merge behaviour is tunable via merge settings, and merges can be forced (the article references the old `optimize` API — the modern equivalent is force-merge).

### In-memory segment construction

Historical evolution worth knowing:

- **Lucene < 2.3** — every added document was literally its own tiny segment, all merged at flush time.
- **Later** — a `DocumentsWriter` builds larger in-memory segments from a batch of documents.
- **Lucene 4+** — one `DocumentsWriter` per thread, enabling **concurrent flushing**. Previously indexing stalled waiting for a flush to finish.

### The cache side-effect

**Field and filter caches are per-segment.** So every new segment — whether from a flush or a merge — **invalidates caches** and can cause a visible search-latency spike until they're rebuilt. Elasticsearch's warmer API exists to pre-warm caches before a new segment is exposed to search.

### Refresh — where near-real-time search comes from

The most common trigger for segment creation in Elasticsearch is the **automatic refresh, defaulting to once per second**. A new segment becomes searchable as soon as it's created — that one-second cadence *is* Elasticsearch's "near real time."

A refresh is cheaper than a commit (no wait for a confirmed durable write), but it is not free: it makes a segment, invalidates caches, and may kick off a merge.

**Tuning rule:** for bulk / batch reindexing, raise `refresh_interval` or disable auto-refresh entirely, then refresh manually when the load is done. Otherwise you burn throughput creating and merging tiny segments nobody is querying yet.

---

## 5. From Lucene index to Elasticsearch index

The article opens this section with Wheeler's line about solving problems with another level of indirection — which is exactly what's happening.

The containment hierarchy:

```
Elasticsearch index
   └── shards (+ zero or more replicas)   ← each is a complete Lucene index
          └── segments                     ← each immutable, a mini-index
```

Key implications:

- A search over an ES index fans out to **all shards → all their segments**, and merges results. Same when searching multiple ES indices.
- Therefore **searching two 1-shard indices ≈ searching one 2-shard index** — either way, two Lucene indices get searched.
- **The shard is the unit of scaling.** Documents route to a shard by default via a hash of the document ID (round-robin in effect).
- **Shard count is fixed at index creation and cannot be changed afterwards.** This is the decision you cannot walk back.

### Routing and partitioning strategies

Combining index patterns, aliases, and document/search routing gives you a lot of control over data layout. Two illustrative patterns from the article:

- **Time-based data** (logs, events, tweets): create an index per day/week/month. Searches can be restricted to relevant time ranges, and — crucially — **old data is expunged by dropping the whole index**, which is cheap, unlike deleting documents from a live index.
- **Per-user data** ("search your own messages"): route all of one user's documents to the same shard, so a query only has to touch one shard.

---

## 6. Transactions and the translog

**Lucene has a notion of transactions. Elasticsearch does not.** All ES operations land on one timeline, which isn't guaranteed consistent across nodes because flush timing varies per node.

The reasoning is explicit: coordinating isolation and visibility of segments and caches across indices across nodes in a distributed system is very hard, so Elasticsearch deliberately **trades that guarantee for speed**.

Durability instead comes from the **transaction log (translog)**: documents to be indexed are **appended** to a log. Appending to a log file is far cheaper than building a segment, so ES gets a durable record of the write in addition to the in-memory buffer — which would otherwise be lost on a crash. Consistency requirements can be raised per-operation (e.g. requiring replicas to have indexed the doc before the call returns).

---

## 7. Condensed takeaways

1. **Analysis defines searchability.** How you tokenize and transform text at index time dictates which queries can be answered efficiently. Get terms right, or nothing else helps.
2. **Prefix search is the primitive.** Reverse, n-gram, decompound, geohash, numeric-trie, phonetic-encode — all of it is bending problems into prefix lookups.
3. **Index in memory, flush to disk as segments.**
4. **Segments are immutable. Deletes are marks, not removals.**
5. **An index is many segments; every search hits every segment and merges.**
6. **Segments merge periodically** — that's when deleted docs actually go away.
7. **Field and filter caches are per-segment**, so new segments cost you cache warmth.
8. **No transactions in Elasticsearch.** Durability comes from the translog.

---

## 8. Practical rules this implies

- Don't put fast-changing counters or mutable state in Lucene — every update is a delete-plus-reinsert.
- For bulk indexing, raise or disable `refresh_interval`, then refresh once at the end.
- Pick shard count carefully at creation time; you can't change it later.
- Use time-based indices for log-like data so deletion is an index drop rather than a mass delete.
- If you need substring or fuzzy search, pay for it in the analyzer (n-grams etc.) — it will not be cheap as a query-time operation.
- Expect a latency blip after merges and refreshes; that's cold per-segment caches, not a bug.

---

## Notes on currency (2013 article, read in 2026)

The article dates from 2013 and describes Lucene 4-era behaviour. The *structural* model — inverted index, immutable segments, merges, translog, shard = Lucene index — is still accurate. A few things have moved on since:

- The `optimize` API was replaced by **force-merge**.
- The **filter cache** was reworked into the query cache / request cache model; doc values largely replaced the old field-data cache for sorting and aggregations.
- Term dictionaries now use an **FST (finite state transducer)** representation, which is what makes the prefix and Levenshtein-automaton traversals genuinely cheap.
- Numeric ranges moved from the trie-terms approach described here to **BKD trees / points**, which is a different (and better) mechanism for the same goal.
- Lucene also handles **vector search (HNSW)** now, sitting alongside the inverted index rather than replacing it.

Worth reading Part 2 of the series for the distributed/cluster side.