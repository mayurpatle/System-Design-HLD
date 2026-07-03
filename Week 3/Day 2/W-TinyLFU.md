# W-TinyLFU — Eviction Comparison

Notes from the Caffeine **Efficiency** wiki page (linked off the caffeine GitHub), which backs the W-TinyLFU work in the TinyLFU paper (Einziger, Friedman, Manes; arXiv 1512.00727). Scope here is deliberately narrow: **just the eviction-policy comparison** — how W-TinyLFU stacks up against the other serious contenders.

## The baseline and the framing

LRU is popular because it's simple and has a good hit rate in common scenarios, but in typical workloads it isn't optimal and can do poorly on cases like full scans. The comparison restricts itself to the **top three O(1) eviction policies**, deliberately excluding Clock-based policies — those trade an O(n) worst case for concurrency-friendliness, so they're a different tradeoff class.

The three compared: **ARC**, **LIRS**, and **W-TinyLFU**. Caffeine ships W-TinyLFU because it gets a high hit rate at a low memory footprint.

## The three policies



### ARC — Adaptive Replacement Cache

Keeps a queue for items seen **once** and a queue for items seen **multiple times**, plus **non-resident "ghost" queues** that track keys already evicted. The split between the two resident queues is tuned dynamically to the workload. Simple to implement, but the ghost queues mean it effectively **needs the cache size doubled** to hold the evicted-key metadata. It's also **patented by IBM** — you can't use it without a license, which alone rules it out for a general-purpose OSS library.

### LIRS — Low Inter-reference Recency Set

Orders blocks by **inter-reference recency (IRR)** — the gap between successive accesses — and labels entries **LIR** (low IRR, worth keeping) or **HIR** (high IRR). Evicted HIR entries are kept around as **non-resident HIR**, so a key can be promoted back to LIR shortly after a miss. Strong hit rate, but **complex to implement** and only reaches peak efficiency when the **cache size is roughly tripled** to hold the non-resident bookkeeping.

### W-TinyLFU — Window TinyLFU (what Caffeine uses)

A small **admission LRU (the window)** that evicts into a large **Segmented LRU (the main space)** — but only if the candidate is accepted by the **TinyLFU admission filter**. TinyLFU uses a **frequency sketch** to probabilistically estimate an entry's historic usage; on a contested admission it compares the window victim's estimated frequency against the main victim's and keeps the more popular one. The window exists so **recency bursts** (new-but-hot items) aren't wrongly rejected by a pure frequency filter. The **window-vs-main split is set adaptively via hill climbing** — larger window when the workload is recency-biased, smaller when frequency-biased.

Key structural win: the sketch is a **4-bit Count-Min Sketch at ~8 bytes per entry**, and — unlike ARC and LIRS — **it does not retain evicted (non-resident) keys**. So no 2×/3× size inflation.

## Side-by-side


| Policy        | Core idea                                                                          | Non-resident keys? | Effective size cost | Catch                                                         |
| ------------- | ---------------------------------------------------------------------------------- | ------------------ | ------------------- | ------------------------------------------------------------- |
| ARC           | Two resident queues (seen once / seen many) + ghost queues, dynamically balanced   | Yes                | ~2×                 | IBM-patented; needs a license                                 |
| LIRS          | Rank by inter-reference recency; LIR vs HIR, promote from non-resident HIR         | Yes                | ~3×                 | Complex to implement                                          |
| **W-TinyLFU** | Admission-window LRU → Segmented LRU, gated by a frequency-sketch admission filter | **No**             | ~1× + 8 B/entry     | Frequency sketch needs aging; admission-filter attack surface |




## How the comparison was run

Every policy is measured against **Bélády's optimal** (the clairvoyant, future-knowledge algorithm) as the theoretical upper bound on hit rate. A spread of trace types is used so the result isn't tuned to one workload shape:

- **Wikipedia** — WikiBench trace, ~10% of real user requests to Wikipedia.
- **Glimpse** — from the LIRS authors; a **looping** access pattern (the classic LRU-killer).
- **Database** — from the ARC authors; an ERP application on a commercial database.
- **Search** — from the ARC authors; disk-read accesses from a large commercial search engine serving web queries.
- **OLTP** — from the ARC authors; references to a CODASYL database over a one-hour window.
- **Adaptivity** — a synthetic shift between a **recency-skewed** trace (Corda) and a **frequency-skewed** one (5× the LIRS loop), specifically to show W-TinyLFU **reconfiguring its window/main balance** as the pattern changes.

(The wiki presents the actual hit-rate results as per-trace plots rather than a numeric table — the takeaway is the shape: W-TinyLFU tracks the best of ARC/LIRS across all of them rather than winning one and losing another.)

## Conclusion (the line to remember)

W-TinyLFU reaches a **near-optimal hit rate** and is **competitive with both ARC and LIRS** across the whole trace set — while staying **simpler, holding no non-resident entries, and keeping a low memory footprint**. Versus plain LRU it's a substantial, consistent improvement across varied workloads, which is exactly what you want from a *general-purpose* cache rather than one tuned to a single pattern.

## Interview angle

The sharp point isn't "W-TinyLFU has the highest hit rate" — ARC and LIRS are competitive on hit rate. It's the **cost of that hit rate**: ARC needs ~2× and is patent-encumbered, LIRS needs ~3× and is fiddly to implement, and both keep non-resident keys. W-TinyLFU matches them at ~1× plus 8 bytes/entry and no ghost entries. So the real comparison axis is **hit rate per unit of memory and complexity**, and that's where it wins. The follow-up to be ready for: *how does the frequency sketch avoid staleness?* — periodic aging/reset that halves all counters after a sample window, so the estimate tracks a changing working set instead of ossifying around old hot keys.

---

Source: Caffeine wiki → Efficiency (`github.com/ben-manes/caffeine/wiki/Efficiency`). Primary paper: *TinyLFU: A Highly Efficient Cache Admission Policy*, arXiv 1512.00727.