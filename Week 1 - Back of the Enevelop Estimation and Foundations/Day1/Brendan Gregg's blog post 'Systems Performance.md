# Brendan Gregg — *Systems Performance: Latency*

**Primary sources:**
- [Visualizing System Latency](https://www.brendangregg.com/blog/2010-06-05/visualizing-system-latency.html) (blog / ACMQ article, 2010)
- [Latency Heat Maps](https://www.brendangregg.com/HeatMaps/latency.html) (companion page with full walkthrough)
- Related: *Systems Performance: Enterprise and the Cloud* (Ch. 1.6 Latency, Ch. 2 Latency Analysis & Heat Maps)

---

## Core thesis

- **Latency** (response time) is one of the most important performance metrics — but **averages lie**.
- Traditional tools (e.g. `iostat` average wait time) hide **outliers**, **multimodal distributions**, and **time-varying behavior**.
- **Latency heat maps** — time on the x-axis, latency on the y-axis, color for event density — reveal patterns that line graphs and averages miss.
- Gregg pioneered this visualization on Sun ZFS Storage Analytics (2008) and published the ACMQ/CACM article *Visualizing System Latency* (2010).

---

## Why averages fail

### Example: disk I/O on a simple workload

- `iostat` reported average latency between **3–9 ms**.
- Per-event tracing (`biosnoop` / eBPF) showed most I/O completed in **< 2 ms**.
- **Conclusion:** a few **high-latency outliers** inflated the average — the real problem was invisible in summary stats.

### What gets hidden

| Hidden pattern | What it means |
| --- | --- |
| **Outliers** | Rare slow I/O (50–150+ ms) that drag up averages and hurt tail latency |
| **Bimodal / multimodal** | Two or more “speeds” — e.g. cache hit vs cache miss, fast path vs slow path |
| **Time-varying modes** | Latency bands that **shift over seconds** — invisible in one aggregated histogram |
| **Stack-level differences** | DRAM hits (~0–21 µs) vs disk hits — visible as separate bands on a heat map |

---

## Latency heat maps explained

- Each **column** = one time slice (e.g. one second).
- Each **row** = a latency bucket.
- **Color intensity** = how many events fell in that (time, latency) cell.
- Essentially: an **animated histogram** stacked side-by-side over time.

### vs other visualizations

- **Line chart of average latency** — smooth, misleading, loses distribution.
- **Histogram** — shows distribution but loses **when** patterns occur.
- **Scatter plot** — shows every point but becomes cluttered at scale.
- **Heat map** — fixed bucket count, handles **millions of events**, reveals structure at scale.

---

## Key findings from *Visualizing System Latency*

Gregg’s ACMQ article used Sun Storage Analytics heat maps on real storage workloads. Nine figures covered:

1. NFS latency when enabling SSD cache devices
2. Synchronous writes to a striped disk pool
3. Single-disk latency from a striped pool
4. Synchronous write latency to a single-disk pool
5. Synchronous write latency to a two-disk pool
6. Synchronous writes to a mirrored pool
7. Sequential disk reads, stepping disk count
8. Repeated disk reads, stepping disk count
9. High-latency I/O

### Notable decoded pattern

- A **faint line at the top** of a single-disk write heat map (Figure 4) was later explained: **8 KB writes spanning two disk tracks** on 512-byte-sector drives — extra latency from **track-to-track seek** mid-I/O.

---

## DRAM vs disk — stack-level latency

From Gregg’s [DRAM Latency](https://www.brendangregg.com/blog/2009-02-06/dram-latency.html) follow-up:

- A **dark solid band at the bottom** of an NFS latency heat map = operations served from **DRAM** (ZFS ARC cache), typically **0–21 µs**.
- That band appears in **NFS latency** but not **disk latency** → the client got data **before** the request reached disk.
- Cross-checking with ZFS ARC hit-rate stats confirmed ~467 cache hits/sec matching the fast band.
- **Lesson:** measure latency at the **layer the application cares about**, not only at disks.

---

## Disk metrics ≠ application latency

From the [File System Latency](https://www.brendangregg.com/blog/2011-05-11/file-system-latency-part-1.html) series:

- Apps I/O through the **file system**, not directly to disks.
- File systems **buffer, cache, prefetch, and async-flush** — inflating or deflating disk activity vs app requests.
- `iostat` shows **all disk I/O** (every process + prefetch + flusher + metadata) — not what your app synchronously waits for.
- A 1-byte app write can become **128 KB+ of disk reads/writes** (read-modify-write on file system records).
- **File system latency at the VFS/application interface** is the right metric for app performance; disk metrics suit **capacity planning**.

---

## How to build latency heat maps

### 1. Capture per-event latency

| Platform | Tools |
| --- | --- |
| Linux (modern) | **eBPF** — `biosnoop.py` (bcc), `ext4dist`, `ext4slower` |
| Linux (older) | **perf** — `iosnoop` in perf-tools |
| Linux built-in | **ftrace**, **perf_events** |
| FreeBSD / Solaris | **DTrace** — `iosnoop` |

### 2. Convert trace → heat map

Use Gregg’s **`trace2heatmap.pl`** (Perl, on GitHub):

```bash
awk 'NR > 1 { print $1, 1000 * $NF }' out.biosnoop | \
  ./trace2heatmap.pl --unitstime=s --unitslabel=us --maxlat=2000 > heatmap.svg
```

### 3. Find outliers quickly

```bash
awk '$NF > 50' out.biosnoop    # I/O slower than 50 ms
```

---

## Production considerations

- **Per-event tracing has overhead** — understand cost before running in production.
- **Prefer in-kernel histogram aggregation** (eBPF / DTrace) over exporting every event to user space — can reduce data transfer by **~1000×**.
- Sun Storage Analytics (2008) recorded I/O heat maps **24×7 at 1-second granularity** with negligible overhead using in-kernel histograms + downsampling.
- High-overhead tools (`strace`, `tcpdump`, perf without eBPF aggregation) pass all events to user space — risky at high event rates.
- **Recommendation:** aggregate in kernel, export histogram buckets, not raw events.

---

## Latency Analysis Method (from *Systems Performance*)

Gregg’s structured approach to finding bottlenecks (also called **Time Division Method**):

1. **Measure** operation time (latency)
2. **Divide** into logical synchronous components
3. **Continue dividing** until the latency origin is identified
4. **Quantify** — estimate speedup if the problem is fixed

Works alongside other methods (USE Method for resource bottlenecks, workload characterization, drill-down analysis).

---

## Key takeaways

- **Never trust average latency alone** — always look at the **full distribution** and **how it changes over time**.
- **Heat maps** expose outliers, multimodal behavior, and temporal shifts that histograms and line charts miss.
- **Measure at the right layer** — application / VFS / file system for app pain; disks for capacity.
- **Caching creates multiple latency modes** — DRAM (~µs), SSD (~ms), disk (~ms–10ms+) appear as separate bands.
- **Dynamic tracing** (DTrace → eBPF) makes per-event and aggregated latency analysis practical in production.
- Gregg’s work on latency visualization influenced monitoring products industry-wide (Joyent Cloud Analytics, Datadog, etc.).

---

## Tools & references

| Resource | Link |
| --- | --- |
| Blog post | https://www.brendangregg.com/blog/2010-06-05/visualizing-system-latency.html |
| Latency heat maps guide | https://www.brendangregg.com/HeatMaps/latency.html |
| Heat maps overview | https://www.brendangregg.com/heatmaps.html |
| trace2heatmap.pl | https://github.com/brendangregg/HeatMap |
| Performance methodologies | https://www.brendangregg.com/methodology.html |
| Book | *Systems Performance: Enterprise and the Cloud*, 2nd Ed. — Brendan Gregg |

---

*Notes summarized from Brendan Gregg’s latency blog posts and companion pages. Read the full ACMQ article and heat map walkthrough for figures and examples.*
