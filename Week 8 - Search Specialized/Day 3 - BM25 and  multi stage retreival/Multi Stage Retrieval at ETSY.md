# Production Multi-Stage Relevance at Etsy — Study Notes

**Source:** *How Etsy Uses LLMs to Improve Search Relevance* — Yuqing Zhang, Congzhe Su, Susan Liu · Etsy Code as Craft · 16 Jan 2026
https://www.etsy.com/codeascraft/how-etsy-uses-llms-to-improve-search-relevance

**Why this one:** It's the most current production write-up of a cascaded relevance stack, and it maps almost line-for-line onto the Lin/Nogueira/Yates survey — multi-stage reranking (§3) plus knowledge distillation (§3.5.1), except built out of LLMs rather than BERT rerankers. It's also honest about a tension most posts hide (see §7).

> **Scope caveat up front:** this covers the *reranking and filtering* half of the funnel. Etsy states explicitly that post-retrieval filtering is the first stage where the relevance model applies, and that pushing it upstream into retrieval is future work. For candidate generation, pair this with Etsy's *Deep Learning for Search Ranking* post or Pinterest/Airbnb's embedding-based retrieval write-ups.

---

## 1. The problem: engagement is a biased proxy for relevance

Etsy's search models historically leaned on **engagement signals** — clicks, add-to-carts, purchases — as stand-ins for relevance.

The flaw is stated plainly: these signals are objective but **biased toward popularity**. Popular listings accumulate clicks even when they're not the best match for a specific query. Optimizing engagement therefore optimizes a **popularity feedback loop**, not intent satisfaction.

The fix is not to discard engagement but to add a second, orthogonal axis: **semantic relevance** — how well a listing aligns with the intent expressed in the query. The framework has three parts:

1. **High-quality data** — human-curated "golden" labels for evaluation, plus human-aligned LLM labels to scale training across millions of pairs.
2. **A family of relevance models** with different accuracy/latency/cost tradeoffs.
3. **Model-driven applications** — offline evaluation *and* real-time production integration.

**The marketplace angle** (not incidental — it's part of the business case): favoring relevance over popularity gives visibility to **small and new sellers** who lack established shops' engagement history. Etsy notes 89% of their sellers are businesses of one.

---

## 2. The label schema: three shades of relevance

Derived from user research, applied to query–listing pairs:

| Label | Definition |
|---|---|
| **Relevant** | Matches *all* parts of the query, accounting for meaning and proper nouns |
| **Partially relevant** | Matches part of the query, or is thematically related but not a full match |
| **Irrelevant** | No meaningful connection; presence in top results **would make search feel broken** |

That last phrasing is the useful one — the Irrelevant class is defined by *user-perceived breakage*, not by a similarity threshold. That's what makes it actionable as a hard filter.

---

## 3. Data: anchored by humans, scaled by LLMs

The naive move would be pure **LLM-as-a-judge**. Etsy names two reasons that fails:

- **Domain shift** — off-the-shelf LLMs don't capture Etsy users' vocabulary and preferences.
- **Performance–cost tradeoff** — big models reason better but are too expensive at scale; small models are cheap but less accurate.

Their formulation: **humans define what good looks like; LLMs scale it.** LLMs amplify human judgment rather than replacing it.

### Human golden labels

- Query–listing pairs sampled from search logs via a **mix of random/stratified sampling** (broad coverage) and **targeted sampling** (hard cases).
- **Two Etsy admins label each pair**, with an ongoing review process to break ties *and to revise the guidelines themselves*.
- Quality control tracks **row-level disagreement rate** — how often annotators disagree on the same pair.

### The guidelines are a living document

The best detail in the post: relevance definitions drift with culture. **"Face masks" before 2020 meant costume and fashion masks; after 2020 it means protective masks.** A frozen labeling guideline silently decays. Etsy continuously refines theirs through user research and annotation feedback.

*(This is the practical face of what the Lin survey calls relevance being an assessor's opinion rather than ground truth — and it's why golden sets need maintenance, not just creation.)*

### The LLM annotator

- **o3**, with **few-shot chain-of-thought** prompting, orchestrated in **LangGraph**.
- Prompt instructions derived from the human annotation guidelines.
- Input features are rich and **multimodal**: title, images, text description, attributes, variations, and extracted entities.
- **Self-consistency sampling** applied to improve reliability.
- **Validated against the human golden set before use.** Only after alignment is confirmed does it generate large-scale training data.

That validation gate is the load-bearing step: it's what makes the whole downstream cascade traceable back to human judgment.

---

## 4. The model cascade: three-tier distillation

```
                    ┌──────────────────────────────────────┐
   human golden  ──▶│ validate                             │
   labels           └──────────────────────────────────────┘
                                    │
        ┌───────────────────────────▼───────────────────────────┐
        │  LLM ANNOTATOR  —  o3, few-shot CoT, self-consistency  │
        │  Most accurate · most expensive · offline only         │
        └───────────────────────────┬───────────────────────────┘
                                    │  generates training data (SFT)
        ┌───────────────────────────▼───────────────────────────┐
        │  TEACHER  —  Qwen 3 VL 4B, fine-tuned                  │
        │  High-throughput batch annotation                      │
        │  Role: daily evaluation + A/B test measurement         │
        └───────────────────────────┬───────────────────────────┘
                                    │  distillation
        ┌───────────────────────────▼───────────────────────────┐
        │  STUDENT  —  BERT-based TWO-TOWER                      │
        │  Real-time inference · <10ms added latency             │
        │  Role: live search stack                               │
        └────────────────────────────────────────────────────────┘
```

| Tier | Model | Optimized for | Where it runs |
|---|---|---|---|
| **Annotator** | o3 + CoT | **Accuracy** / human alignment | Offline, training-data generation |
| **Teacher** | Qwen 3 VL 4B (SFT) | **Throughput** | Batch — millions of pairs daily |
| **Student** | BERT two-tower | **Latency** | Online — real-time search |

Each tier exists because the one above it can't meet a specific constraint:

- The **annotator** aligns best with golden labels but is too costly for recurring large-scale inference.
- The **teacher**, SFT'd on annotator output, preserves human alignment while labeling **millions of pairs daily** — good enough for recurring evaluation and monitoring, but **too slow to serve search results**.
- The **student**, distilled from the teacher, judges nearly as accurately while adding **under 10ms of latency**.

**Two-tower matters here.** It's the same representation-based architecture from the pre-BERT era (and DPR-style dense retrieval): query and listing encode independently, so listing embeddings are **precomputable offline** and online cost collapses to an inexpensive comparison. That's how you get sub-10ms. A cross-encoder — which would be more accurate — cannot precompute anything and is why the teacher stays offline.

**Evaluation discipline:** all three tiers are scored **against the same golden dataset**, using **multi-class macro F1** plus per-class F1. One yardstick across the cascade means degradation is traceable to a specific tier rather than diffused across the pipeline.

---

## 5. Application A — measurement

The **teacher** powers evaluation:

- **Daily**: sample search requests, run offline inference, aggregate predicted labels into summary metrics. Reviewed regularly; sudden relevance declines trigger diagnosis.
- **In A/B tests**: relevance metrics are computed per variant, with **control and treatment sampled separately to preserve statistical power**, and are part of the ship/no-ship decision. The bar is that changes must be **neutral-to-positive on semantic relevance**.
- **Infrastructure**: **vLLM** for high-throughput inference — millions of query–listing pairs daily at low cost.

The organizational point is bigger than the technical one: **semantic relevance became a launch gate**, sitting alongside engagement metrics rather than under them. That's what stops the popularity loop from reasserting itself one experiment at a time.

---

## 6. Application B — production integration (four insertion points)

The **student** model is embedded in the live search stack at four distinct places:

| # | Integration | What it does | Character |
|---|---|---|---|
| 1 | **Filtering** | Drops listings predicted **Irrelevant** before downstream ranking | Hard cut, pre-ranking |
| 2 | **Feature enrichment** | Feeds predicted relevance scores as **features to the ranking model** | Soft signal |
| 3 | **Loss weighting** | Adjusts the ranking model's **training weights** by predicted relevance | Training-time |
| 4 | **Relevance boosting** | Promotes highly relevant listings via **heuristic rules** in final results | Post-ranking |

This is the most transferable part of the post. One model gets **four different levers** spanning the whole pipeline — a pre-ranking filter, an inference-time feature, a training-time weight, and a post-ranking boost. Most teams build a relevance model and wire it in exactly one way. The spread is what lets them tune aggressiveness without retraining.

Note the ordering relative to the classic funnel: filtering happens **after retrieval, before ranking** — i.e. it's a *pre-ranking* stage in the cascade sense, exactly the "lightweight ranking" tier in the retrieval → pre-rank → rank → rerank taxonomy.

---

## 7. Results — and the honest finding

**Headline:** the share of **fully relevant** listings rose from **58% to 62%** between August and October 2025.

Qualitatively, for a query like **"fall decor"**, the system now concentrates on seasonal decor and deprioritizes loosely related items such as clothing that previously surfaced.

### The tension worth taking seriously

> In online experiments, **engagement metrics often decline even as semantic relevance improves.**

Etsy reports this directly and notes other e-commerce platforms have observed the same. Their hypothesis: they're applying **uniform relevance treatments despite contextual variation** across query types.

This is the single most valuable sentence in the post. It says the two axes are **genuinely in conflict, not merely different**, and that "more relevant" is not automatically "better business outcome." Anyone building relevance filtering on top of an engagement-optimized ranker should expect this and plan for it — because the naive read is "the relevance model is broken," and it usually isn't.

Also worth noting what 58% → 62% implies: **even after all this, nearly 4 in 10 top results are not fully relevant.** Production search relevance is a grind, not a step change.

---

## 8. Stated next steps

- **Relevance–engagement dynamics** — move from uniform treatment to **adaptive strategies tailored by query type**. (Broad/exploratory queries plausibly tolerate looser relevance than specific ones.)
- **Finer-grained partial relevance** — inspired by Amazon's **ESCI** framework (Exact / Substitute / Complement / Irrelevant), adding subcategories for complements and substitutes. Better evaluation precision, and potentially new UX surfaces ("customers also need…").
- **LLM facilitation of annotation** — when LLM judgments are **self-consistent**, they align better with humans, which signals an *easy* pair. So: let LLMs handle easy cases, **route human effort to hard ones**. Self-consistency as a difficulty proxy is a clean trick.
- **Simplify the cascade** — three tiers gives flexibility but costs operational complexity; they may merge tiers.
- **Push relevance upstream into retrieval** — currently the model only acts post-retrieval, so anything the retriever misses is unrecoverable.

That last one is the real ceiling. **A reranker can only reorder what retrieval hands it.** Filtering improves precision; it can do nothing for recall.

---

## 9. Transferable lessons

1. **Engagement and relevance are different axes, and optimizing one can hurt the other.** Measure both; make both launch criteria.
2. **Cascade by constraint, not by ambition.** Each tier exists because the tier above violates a specific cost or latency budget. Annotator → accuracy, teacher → throughput, student → latency.
3. **Gate the cascade on human labels.** Validate the LLM annotator against golden data *before* it generates training data, and score every tier on the same golden set. Otherwise drift is invisible.
4. **Labeling guidelines are living artifacts.** The "face masks" example generalizes: any relevance definition tied to culture, season, or product trends decays. Budget for guideline maintenance, not just annotation.
5. **Two-tower for anything online.** Precomputable document representations are what make sub-10ms feasible; keep cross-encoders offline.
6. **Ship one model into several integration points.** Filter, feature, loss weight, boost — four levers, one model, very different risk profiles.
7. **Self-consistency is a free difficulty signal.** Where the model agrees with itself, trust it; where it doesn't, escalate to a human.
8. **Filtering caps out at precision.** If retrieval recall is the bottleneck, no amount of reranking fixes it.

---

## 10. Where this sits against the other reading

| Concept | Survey / theory source | Etsy's production instance |
|---|---|---|
| Multi-stage reranking (§3) | Lin et al. | Retrieve → student filter → rank → boost |
| Knowledge distillation (§3.5.1) | Lin et al. | o3 → Qwen 3 VL 4B → BERT two-tower |
| Representation-based / bi-encoder (§5) | Lin et al. | Two-tower student, precomputed listing vectors |
| Relevance as assessor opinion (§2.3) | Lin et al. | Two admins per pair, disagreement-rate tracking, evolving guidelines |
| Effectiveness/efficiency tradeoff | Lin et al. (pervasive theme) | The entire three-tier design |
| Vocabulary mismatch | Lin et al. §1.2.2 | Semantic relevance model over lexical retrieval |
| BM25 as the sparse baseline underneath | Robertson; Turnbull | Implied — the retrieval layer they haven't touched yet |

The through-line across everything you've read: **BM25 (or a hybrid) retrieves, learned models rerank, and the interesting engineering is in deciding what runs at which stage under what latency budget.** Etsy's post is that principle instantiated with 2026-era components.