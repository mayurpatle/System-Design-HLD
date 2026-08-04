# Memory-Mapped Files and Lucene

## Purpose

This guide explains the **OS page-cache story behind Lucene**: how a file on disk can be accessed like memory, why the JVM heap is not the whole memory budget, and why a search node can be fast after warm-up but slow and erratic under cache pressure.

> **Short version:** `mmap` makes a file addressable through virtual memory. It does **not** load the whole file into RAM. When Lucene first touches an unmapped page, the kernel brings that page into the shared OS page cache. Reusing a cached page is fast; repeatedly faulting pages in and evicting them is slow.

---

## 1. Mental model

### What is a memory-mapped file?

A memory-mapped file is a mapping between a range of a process's **virtual address space** and a range of bytes in a file. After the mapping exists, program code reads file bytes through normal memory-style loads instead of explicitly asking the kernel to copy a requested range into an application buffer.

Creating a mapping is mostly metadata work. Physical RAM is generally committed **lazily**, page by page, when code actually accesses the mapped address.

```text
Lucene process virtual address space

  0x0000 ...  [JVM heap] [stack] [native/JIT] [mapped Lucene segment files]
                                               |          |
                                               | address  | address
                                               v          v
                                            terms.tim  postings.doc
                                                \          /
                                                 \        /
                                                  v      v
                                          OS page cache (physical RAM)
                                                   |
                                             cache miss only
                                                   v
                                             SSD / storage
```

The mapping itself is not a private Java-heap copy. File-backed cache pages can be shared by multiple processes that read the same file.

### The distinction people most often miss

| Resource | What it represents | Does mapping consume it immediately? |
|---|---|---|
| Virtual address space | Address ranges the process may use | Yes, approximately the mapped file range |
| Physical RAM | Actual page frames holding active data | Usually only on access |
| OS page cache | Reclaimable RAM used for file data | Grows as mapped/read file pages are used |
| JVM heap | Objects managed by the Java GC | No; mapped index bytes are not ordinary heap objects |

On a 64-bit process, a large virtual mapping is ordinarily acceptable; it still needs physical RAM for the **working set**, however. On a constrained address space or a container with tight memory limits, the distinction remains operationally important.

---

## 2. Virtual memory, pages, and page faults

Virtual memory gives each process its own address space. Hardware page tables translate a virtual address to a physical RAM frame, normally in page-sized chunks (often 4 KiB, though page sizes and huge-page behavior vary by platform).

### First access to an index byte

```text
Query needs byte at file offset 8,392,104
            |
            v
Lucene reads mapped virtual address
            |
            v
Is page-table entry present and accessible?
   | yes                               | no
   v                                   v
CPU reads RAM                  CPU raises page fault
                                        |
                                        v
                            Kernel locates file-backed page
                              | cached             | not cached
                              v                    v
                       install mapping       read from storage into
                       (minor fault)          page cache (major fault*)
                              \                    /
                               \                  /
                                v                v
                                retry instruction; Lucene continues
```

`*` The exact accounting of “major” and “minor” varies by operating system and measurement tool. The useful operational distinction is whether the fault can be satisfied from RAM or needs storage I/O.

### Fault types

- **Minor/soft fault:** the data is already resident (for example, in page cache) but the process needs a page-table mapping. It has overhead, but no device read.
- **Major/hard fault:** the required file page is not resident and storage I/O is required. This is the cold-cache latency that can dominate a query.
- **Not all latency is a page fault:** a buffered `read()` can also block on cache miss. `mmap` changes the access interface and copy/system-call profile; it does not turn a cold disk into RAM.

### Read-only versus writable mappings

Lucene's immutable segment files are a particularly good fit for read-only mappings. For a writable shared mapping, modified pages become **dirty** and the OS writes them back later; a caller may need an explicit durability operation. This is a different set of correctness trade-offs from Lucene's usual immutable-segment read path.

---

## 3. The OS page cache

The page cache is kernel-managed RAM that keeps recently used file contents. It is not “wasted” memory: the OS can reclaim clean cached pages when applications need RAM.

```text
RAM on a search host
+---------------------------------------------------------+
| JVM heap       | JVM/native overhead | OS page cache    |
| objects, query | direct buffers,     | hot Lucene files |
| state, caches  | threads, metadata   | postings, terms  |
+---------------------------------------------------------+
                    ^                   ^
                    |                   |
               must both fit in the machine/container budget
```

### Why it helps Lucene

Lucene search commonly involves selective, non-sequential access: term dictionaries, skip data, postings, doc values, norms, stored fields, points, and—where used—vector graph data. The page cache lets the OS retain recently accessed portions without Lucene having to copy and manage an entire index in the Java heap.

The kernel also sees access across processes and can apply its own read-ahead and eviction policies. It does not know query intent perfectly, so application-level preloading or warm-up may still help when the working set is predictable.

### Warm cache and cache thrashing

```text
Warm, steady working set
  active pages fit in page cache
  query -> mapped load -> RAM -> CPU-bound-ish latency

Oversubscribed / thrashing working set
  query A loads pages, evicts B's pages
  query B loads pages, evicts A's pages
  repeated storage reads -> I/O-bound, high-tail-latency behavior
```

The important capacity number is not simply total index size. It is the concurrent **active working set** plus the JVM, native memory, the operating system, and other workloads. A read-mostly node can deliberately use a modest, sufficient heap and leave substantial RAM available for the page cache—but only after measuring its own workload and respecting platform/container limits.

---

## 4. How Lucene uses memory mapping

Lucene provides filesystem directory implementations. `MMapDirectory` maps index files into virtual memory, exposing them through Lucene's `IndexInput` abstraction. It is especially attractive for large, immutable, random-access index files on local storage.

```text
IndexWriter creates immutable segment files
                 |
                 v
DirectoryReader opens a commit / segment
                 |
                 v
MMapDirectory maps appropriate files (virtual ranges only)
                 |
                 v
Query walks terms and postings
                 |
                 v
First touched pages fault into page cache; reused pages stay hot when RAM permits
```

### Practical details

- **Mappings can be chunked.** A very large file need not be one monolithic Java buffer; Lucene has a configurable maximum mapping chunk size.
- **A mapping consumes virtual address space equal to its mapped range.** This is explicitly noted in Lucene's `MMapDirectory` documentation.
- **The best directory depends on the environment.** Local filesystem, operating system, JDK version, storage, file lifecycle, and workload matter. `MMapDirectory` is not a blanket claim that every Lucene deployment should map every file.
- **Lucene segment immutability reduces hazards.** New data is written into new segments and old segments are retired only after readers release them, which is friendlier to concurrent read mappings than in-place mutation.
- **Deletion/replacement semantics matter on Windows.** Historically, an open memory-mapped file could remain difficult to delete or replace until it was unmapped; modern Lucene/JDK paths offer mechanisms to release mappings, but deployment behavior must be tested on the target platform.

### A simplified search example

For the query `lucene mmap`:

1. Lucene consults the term dictionary to find each term's postings location.
2. It reads compressed postings blocks and skip data to advance candidate document IDs.
3. It reads scoring-related structures (for example norms or doc values) as needed.
4. Some touched pages may already be cached; others fault in.
5. Results are ranked and returned.

The query is therefore not necessarily a large sequential scan. Its latency may depend on a modest number of scattered pages. When those pages are hot, performance is excellent; when many are cold, random I/O and fault waits can dominate.

---

## 5. `mmap` versus `read()` / `write()`

Both paths normally benefit from the same OS page cache. The key difference is **how the application reaches those cached bytes**.

| Concern | Buffered `read()` | Memory-mapped (`mmap`) |
|---|---|---|
| Application interface | Explicit read call fills a supplied buffer | Program accesses mapped address range |
| Kernel crossing | Usually each read request | Mapping setup, then ordinary loads; faults invoke kernel only when needed |
| Data copies | Often page cache → user buffer | Avoids a separate application-buffer copy for read access |
| Random small reads | Repeated calls/buffer management can be costly | Natural addressable random access; fault cost remains on cold pages |
| Sequential streaming | Often very good; explicit buffering/read-ahead can be clear | Can be good, but may pollute cache or fault many pages unnecessarily |
| Error timing | Read returns error at the call | I/O/fault-related trouble can surface during memory access; failure behavior can be less local |
| Write durability | `write()` plus `fsync`/equivalent discipline | Dirty pages and `force()`/mapping semantics; careful design required |
| File lifecycle | Closing handle usually releases it | Mapping lifetime needs explicit attention, especially on some platforms/JDKs |

### A common correction

It is wrong to say “`mmap` bypasses the page cache.” Conventional file I/O and normal file mappings usually use the **same cache**. It is also wrong to say “`mmap` always avoids copies in every layer”: the practical advantage is that the application need not request and maintain a separate user-space copy of data it is merely reading. Kernel, hardware, and filesystem work still happen.

---

## 6. Performance characteristics

### Where mappings tend to shine

- Large read-mostly files.
- Fine-grained or random access.
- Multiple readers sharing file-backed cached pages.
- Workloads whose hot index pages fit in RAM.
- Index formats like Lucene's immutable segments, where direct random access is valuable.

### Where they can disappoint

- Cold data on slow or remote storage: every first-touch still waits for I/O.
- A working set too large for available cache: eviction and repeated faults cause tail-latency spikes.
- Streaming once through a huge file: explicit sequential I/O may offer more predictable buffering and cache advice.
- Many short-lived mappings or complex concurrent file mutation/deletion.
- 32-bit or address-constrained processes.
- Systems under memory pressure that swap anonymous process memory: this is much worse than merely evicting clean file-cache pages.

### Measure, do not assume

Useful signals include:

- Search latency percentiles (especially p95/p99) and throughput.
- Storage latency, queue depth, read IOPS, and utilization.
- Major/minor fault rates, with platform-specific interpretation.
- Available RAM, reclaim activity, and page-cache residency of important files.
- JVM heap usage, GC pauses, direct/native memory, thread stacks, and container memory limits.
- Segment count and query mix: merges, broad filters, aggregations, and vector search can shift the working set.

Benchmark both **cold** and **warm** cases. A warm-cache benchmark answers “how fast is the algorithm with its data in RAM?” A cold-cache test answers “what does the user feel after restart, deployment, node movement, or working-set turnover?”

---

## 7. JVM implications

### `MappedByteBuffer`

Java can create mappings through `FileChannel.map`, returning a `MappedByteBuffer`. It is a direct buffer backed by a file mapping rather than a normal heap byte array.

```java
try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
    MappedByteBuffer bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    int header = bytes.getInt(0);  // may trigger a page fault on first access
}
```

Important API points:

- `load()` is a **best-effort** attempt to bring mapped content into physical memory; it may cause faults and I/O. It is not a durable pin against later eviction.
- `isLoaded()` is a hint, not a guarantee—the OS can change residency immediately afterward.
- `force()` requests write-back of changes for writable mappings. It does not matter for a read-only mapping.
- A mapped region must remain valid. Truncating or otherwise modifying a file concurrently can cause later access failures; Java documents the behavior as unspecified for inaccessible regions.

### GC and unmapping

Mapped index bytes are outside the ordinary Java heap, so increasing `-Xmx` does not make them resident in page cache. But Java objects that represent mappings, direct buffers, query state, and Lucene readers still have lifetimes managed by the JVM.

Historically, deterministic unmapping was awkward because `MappedByteBuffer` had no simple public `unmap()` method, and reclamation could wait for GC. Lucene has evolved its own directory implementation and optional unmap support; current behavior depends on the Lucene and JDK versions, native-access settings, and platform. Treat this as a deployment compatibility topic, not a detail to hand-wave away—especially if segments are frequently merged/deleted on Windows.

### Heap sizing implication for Lucene services

An oversized heap can reduce RAM available for the OS page cache and increase GC costs. An undersized heap can cause allocation pressure, cache churn, and GC pauses. The right target is a **balanced process budget**, not “maximize heap” or “minimize heap” by slogan.

```text
Machine / cgroup memory limit
    = JVM heap
    + JVM native/direct/metaspace/thread memory
    + OS/kernel needs
    + page cache for active Lucene files

If the first three consume nearly all memory,
the last line—the search-data cache—cannot do its job.
```

---

## 8. Practical examples

### Example A: warm keyword-search shard

A shard has 80 GB of index files, but ordinary traffic repeatedly touches 12 GB of term dictionaries, postings, and doc values. The host has enough RAM after accounting for the JVM and system needs. After warm-up, much of that 12 GB remains cached; queries largely execute with RAM-speed data access and CPU work dominates.

### Example B: multi-tenant cache pressure

Twenty shards each have a different 10 GB active set, but only 64 GB remains for page cache after the JVM and other services. Traffic alternates between tenants. The OS continuously evicts pages for one tenant to serve another, so p99 latency rises even when heap utilization and GC appear healthy. More heap would not solve this; the page-cache working set is the constrained resource.

### Example C: vector search

Vector search may traverse graph and vector-data structures with irregular access. If the graph/vector pages are resident, it can be CPU-bound. If they are not, the pointer-chasing pattern can trigger many cache misses and become I/O-bound. Capacity planning must include these structures' active page footprint, not just the Java heap.

### Warm-up guidance

Warm-up can be useful after restart or relocation, but do it deliberately:

1. Identify the files/pages actually used by representative queries.
2. Warm gradually so foreground traffic and storage are not overwhelmed.
3. Verify the node still has enough headroom to retain the working set.
4. Re-test after index, query, segment, hardware, or JVM changes.

Blindly reading every byte of every index can waste I/O and evict more valuable cache pages.

---

## 9. Advantages and disadvantages

| Advantages | Costs and risks |
|---|---|
| Lets the OS manage a shared, adaptive file cache | Performance is highly sensitive to RAM working-set fit |
| Efficient random access without copying every read into heap buffers | Cold-page faults introduce high, sometimes irregular latency |
| Reduces heap pressure for large read-only index data | Mapping consumes virtual address space |
| Natural fit for immutable Lucene segments | File deletion/replacement and unmap lifetimes need platform/JDK care |
| Enables hot pages to be shared across readers/processes | Can obscure I/O behind a seemingly ordinary memory load |
| May reduce syscall and buffer-management overhead | Not automatically best for sequential scans or heavy in-place writes |

---

## 10. Interview notes

### Strong answers in one or two sentences

**What is `mmap`?**  
It maps a file range into virtual memory. Accessing an address causes the OS to provide the corresponding file page, usually lazily through the page cache.

**Does `mmap` load the entire file into RAM?**  
No. The address range is mapped immediately; physical pages are normally populated on demand and may be evicted later.

**Why is mmap good for Lucene?**  
Lucene reads immutable index structures with fine-grained, random-access patterns. Mapping lets it use the OS page cache directly and avoid keeping the full index as Java-heap buffers.

**What is a page fault in this context?**  
It is the CPU trap when a mapped virtual page is not presently usable. If the file page is cached, the kernel can install a mapping; otherwise it must retrieve data from storage.

**Why can a search node have high latency with low heap use?**  
The bottleneck may be the page cache, not the heap: active index pages may be absent from RAM, forcing storage reads and cache thrashing.

**Does mmap eliminate disk I/O?**  
No. It makes I/O demand-driven. A first access to a cold page still requires the storage path.

**How does `read()` differ?**  
Both normally use the page cache. `read()` copies bytes into a caller-provided buffer through an explicit call; `mmap` lets code access a mapped range and faults pages as needed.

**What are mmap's major drawbacks?**  
Unpredictable cold-fault latency, pressure on virtual address space, complex file-lifetime semantics, and poor behavior when RAM cannot hold the active set.

---

## 11. Misconceptions to avoid

- **“Mapped bytes count as JVM heap.”** No. They affect virtual address space and page-cache residency, not ordinary heap occupancy.
- **“Free RAM is the only useful RAM.”** No. Reclaimable page cache is valuable; an OS can reuse it when applications need memory.
- **“More `-Xmx` always speeds up Elasticsearch/Solr.”** No. It can starve the page cache that serves Lucene data.
- **“A mapping guarantees zero-copy everywhere.”** No. It mainly avoids a separate user-space read buffer for mapped access; storage and kernel work still exist.
- **“A page fault always means disk I/O.”** No. A minor fault can be resolved from already-resident memory.
- **“`mmap` is always faster.”** No. It is a trade-off whose value depends on access pattern, storage, RAM, OS, JDK, and operational behavior.

---

## 12. Key takeaways

1. `mmap` maps file bytes into **virtual** memory; it does not pre-load the whole file into **physical** RAM.
2. The **OS page cache** is the real cache for mapped Lucene data. Treat it as part of the search engine's memory design.
3. A cold mapped access incurs a **page fault**; if the page is absent, the OS reads it from storage.
4. Lucene benefits because immutable index segments support random, read-mostly access without allocating full file contents on the Java heap.
5. Warm-cache performance can be excellent; inadequate cache capacity causes I/O-bound behavior and high-tail latency.
6. `mmap` and buffered `read()` usually share the OS page cache; the key differences are API shape, copying, syscall cadence, and failure/lifecycle semantics.
7. Tune the **whole memory budget**: heap, native memory, kernel needs, and page cache. Heap metrics alone are insufficient.
8. Test on the target OS/JDK/storage stack, and measure cold and warm behavior before choosing or tuning a directory implementation.

---

## Further reading

- Apache Lucene, [`MMapDirectory` API documentation](https://lucene.apache.org/core/9_9_1/core/org/apache/lucene/store/MMapDirectory.html) — mapping behavior, virtual-address-space note, and configuration details.
- Oracle Java, [`MappedByteBuffer` documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/MappedByteBuffer.html) — `load()`, `isLoaded()`, `force()`, and validity caveats.
- OpenSearch, [*Lucene HNSW performance: A deep dive into the OS page cache*](https://opensearch.org/blog/lucene-hnsw-performance-a-deep-dive-into-the-os-page-cache/) — an applied page-cache explanation for vector search.

> **Scope note:** Exact fault accounting, page size, eviction/read-ahead behavior, and unmap/file-lock behavior vary by operating system, filesystem, JDK, Lucene version, and deployment configuration. Validate these details in the environment you operate.
