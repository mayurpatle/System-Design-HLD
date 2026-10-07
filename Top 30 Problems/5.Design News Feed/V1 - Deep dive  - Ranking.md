# News Feed — Alternate Deep Dive: Ranking

**Drop-in replacement for Phase 5 (0:27 – 0:42)** of the News Feed transcript, for when the interviewer picks ranking instead of the merge.

> **Why this branch is harder:** the merge is a distributed systems problem and you can reason your way through it. Ranking sits on the boundary between systems and ML, and most backend candidates either retreat into vagueness ("we score with a model") or drift into ML theory that isn't what's being asked. The signal is in treating it as a **serving problem with a latency budget** — and then knowing enough about the training loop to name where it goes wrong.

---

**CANDIDATE:** That's the skeleton. The two deepest areas are the merge, and **ranking** — how you score twelve hundred candidates inside a latency budget, and what goes wrong in the training loop. Preference?

**INTERVIEWER:** Ranking. Walk me through it.

---

## Phase 5 — Deep Dive: Ranking (0:27 – 0:42)

**CANDIDATE:** Good. I'll frame this as a serving problem first, because that's where the systems constraints bite, and then get to the training loop, which is where the interesting failures are.

**Level one: the budget is the design.**

```
  Feed load p99 target            ~200 ms
    ├─ pool read                    ~5 ms
    ├─ pull path (parallel, deadline) ~50 ms
    ├─ merge + filter               ~10 ms
    ├─ RANKING                      ~60 ms   ← the budget
    ├─ hydrate top 20               ~30 ms
    └─ serialize + network          ~20 ms

  So: score ~1,200 candidates in ~60 ms.
      = 50 microseconds per candidate, including
        feature fetching.
```

**CANDIDATE:** That number is what makes this a systems problem rather than a modeling one. Fifty microseconds per candidate is not enough time to fetch features over a network and run a meaningful model.

So the first architectural decision is that **you cannot rank all twelve hundred with the real model.** You need a funnel inside the ranking stage itself.

**Level two: two-stage ranking.**

```
  1,200 candidates
        │
        ▼
  ┌──────────────────────────────────────┐
  │ STAGE 1 — lightweight scorer         │
  │                                      │
  │  · features already in memory only   │
  │    (post age, author affinity score, │
  │     content type, cached engagement) │
  │  · simple model — linear or a small  │
  │    gradient-boosted tree             │
  │  · NO network calls                  │
  │  · ~5 µs per candidate               │
  └──────────────┬───────────────────────┘
                 │  top ~200
                 ▼
  ┌──────────────────────────────────────┐
  │ STAGE 2 — heavy ranker               │
  │                                      │
  │  · full feature set, including       │
  │    viewer × author × topic crosses   │
  │  · neural model                      │
  │  · batched inference, one call       │
  │  · ~40 ms for the batch              │
  └──────────────┬───────────────────────┘
                 │  scored, ordered
                 ▼
  ┌──────────────────────────────────────┐
  │ STAGE 3 — re-ranking (business rules)│
  │  diversity, dedup by author,         │
  │  freshness injection, integrity      │
  │  demotions                           │
  └──────────────┬───────────────────────┘
                 ▼
             top 20
```

**CANDIDATE:** This is structurally the same pattern as retrieval — a cheap wide pass followed by an expensive narrow one. In search you'd call it bi-encoder then cross-encoder. Here it's a lightweight scorer then a full model. Same reasoning: you can't afford the accurate thing at scale, so you use it only where the field is already narrowed.

The property that matters: **stage one determines the ceiling.** Stage two can only reorder what stage one passed through. If the genuinely best post is ranked 400th by the cheap model, no amount of quality in stage two recovers it. So stage one is tuned for recall, not precision — it should be generous, and its job is "don't lose good things," not "pick winners."

**INTERVIEWER:** Where do the features come from? You said no network calls in stage one.

**CANDIDATE:** That constraint is what shapes the whole feature architecture, and it's the part I'd say is genuinely a systems problem.

Features come in four categories, and they have completely different fetch profiles:

```
  VIEWER features           fetched ONCE per request
    age, locale, session context, topic affinities
    → one lookup, reused across all 1,200 candidates

  POST features             stored WITH the candidate
    age, media type, language, cached engagement counts
    → denormalized into the feed pool at fanout time
    → zero fetch cost at read time

  AUTHOR features           small set, cacheable
    follower count, posting frequency, quality score
    → only ~200 distinct authors across 1,200 candidates
    → batch fetch, heavily cached

  VIEWER × AUTHOR crosses   the expensive ones
    have I engaged with this author before?
    how recently? how often?
    → 1,200 lookups if done naively
```

**CANDIDATE:** The cross features are the problem. They're also the most predictive — how you've interacted with an author historically is a much stronger signal than anything about the post itself.

Two things make them affordable.

**Precompute the viewer's affinity map.** Rather than looking up "Alice × Bob" per candidate, fetch Alice's top few hundred author affinities as one blob at request start. It's a few kilobytes, it's cacheable per session, and then every cross-feature lookup is a hash lookup in local memory.

**Denormalize post features into the pool.** When fanout writes a post ID into a follower's pool, it can write a small feature blob alongside it — post age bucket, content type, author ID, a cached engagement snapshot. That costs maybe 40 bytes per candidate and eliminates a fetch entirely.

That second one is a real tradeoff worth naming: it makes the pool bigger, which per the earlier sizing means more Redis nodes. And the cached engagement counts go stale — a post that goes viral after fanout has stale counts in the pool. I'd accept staleness in stage one, since it's only a filter, and fetch fresh counts for the two hundred that reach stage two.

**INTERVIEWER:** How is the model actually served?

**CANDIDATE:** Separate service, not in-process, and that's a deliberate choice.

```
  Feed Service ──batch of 200──▶ Ranking Service ──▶ model
                ◀──scores────────
```

In-process would be faster — no network hop. But separating it buys three things that matter more:

**Independent deployment.** Model updates ship several times a day; the feed service ships weekly. Coupling them means every model update is a full service deploy.

**Different hardware.** Inference may want GPUs or specialized instances. The feed service wants cheap general compute. Separating lets each scale on its own axis.

**A/B infrastructure.** You need to run several model versions simultaneously against live traffic. That's much cleaner as routing inside a ranking service than as feature flags inside the feed service.

The batching matters a lot: two hundred candidates go in **one** call, not two hundred calls. Model inference is far more efficient batched, and one network round trip instead of two hundred is the difference between viable and not.

**Level three: the training loop, and where it goes wrong.**

**CANDIDATE:** Serving is the tractable half. The training loop has three failure modes that are subtle and self-reinforcing.

**Problem one — position bias.**

```
  You train on engagement: "did the user click / like / dwell?"

  But engagement depends on POSITION.
  A post at rank 1 gets far more engagement than the
  identical post at rank 15 — because it was seen.

  Train naively on logged data and the model learns:
      "posts that appear high get engagement"
      → "rank high what the previous model ranked high"

  The model learns to reproduce the current ranker,
  not to identify good content.
```

Standard mitigations: include position as a feature during training and set it to a constant at serving time, so the model can attribute engagement to position and then have that attribution neutralized. Or inverse propensity weighting — down-weight engagement on high-ranked items proportional to how much exposure they got.

Neither is complete, and it's a known-hard problem rather than a solved one.

**Problem two — the feedback loop.**

```
  Model ranks post high
        │
        ▼
  Post gets impressions
        │
        ▼
  Post accumulates engagement signal
        │
        ▼
  Training data says "this post was good"
        │
        ▼
  Model ranks similar posts higher
        │
        └──────────► loop

  Meanwhile: posts that were never surfaced have
  NO positive signal, ever. They cannot enter the
  training set as positives, so the model never
  learns they'd have been good.
```

This is rich-get-richer at the content level, and it narrows the distribution over time. The model becomes confident about a shrinking slice of content and blind to everything outside it.

The fix is **deliberate exploration** — reserving some fraction of impressions for candidates the model is uncertain about, accepting a short-term engagement cost to gather signal. That's an explicit product decision, because it means knowingly showing people slightly worse feeds in exchange for a better model later.

I'd frame it in the design as: the re-ranking stage injects a small percentage of exploration slots, and those impressions are logged separately so their engagement isn't confounded with organic ranking.

**Problem three — training-serving skew.**

Features computed one way in the offline training pipeline and another way in the online serving path. The model performs well offline and badly in production, and the discrepancy is invisible in either codebase separately because both look correct.

The mitigation is a **feature store** with a single definition serving both paths, plus point-in-time correctness — training only on feature values that would have been knowable at prediction time. If your training data includes a post's final engagement count but serving only has the count at impression time, you've leaked the future and the offline metrics are meaningless.

**INTERVIEWER:** What are you actually optimizing for?

**CANDIDATE:** This is the question I think matters most, and the honest answer is that it's a product decision with engineering consequences, not the reverse.

The naive objective is engagement — clicks, likes, dwell time. It's easy to measure and it's directly loggable. It's also **known to produce pathologies**:

```
  Optimizing pure engagement rewards:
    · outrage and moral conflict — they drive comments
    · clickbait — high click rate, low satisfaction
    · content that keeps you scrolling rather than
      content you're glad you saw
```

Those aren't hypothetical; they're well-documented outcomes of engagement-maximizing feeds.

So a real objective is **multi-objective with explicit weights**:

```
  score = w₁·P(like)        + w₂·P(comment)
        + w₃·P(share)       + w₄·E(dwell time)
        − w₅·P(hide)        − w₆·P(report)
        + w₇·quality_score   ← independent content quality
        + w₈·P(long-term retention)
```

Two things about that formulation.

**Negative signals carry real weight.** A hide or a report is a much stronger quality signal than a like, because it costs the user effort. Weighting them properly is one of the more effective integrity levers available.

**Long-term retention is the objective that actually matters and the hardest to optimize.** Session engagement is measurable today; whether the user is still here in six months is measurable in six months. Most systems proxy it, imperfectly.

And the weights themselves are not an engineering choice — they encode what the product is for. I'd want them owned explicitly by product with the tradeoffs visible, rather than emerging implicitly from whatever the offline metric happened to be.

**INTERVIEWER:** How do you know a new model is better before shipping it?

**CANDIDATE:** Three stages, and the honest part is that only the last one is trustworthy.

**Offline replay.** Score historical impressions with the new model, compare ranking quality metrics — NDCG against logged engagement. This is fast and cheap and it's **systematically optimistic**, because the logged data was generated by the old model. You're evaluating on a distribution the new model didn't produce, which is exactly the position-bias problem again.

**Shadow scoring.** Run the new model on live traffic without serving its output, and compare score distributions and rank correlations against production. This won't tell you whether it's better, but it will catch the things that break: feature pipeline mismatches, latency regressions, score distribution shifts that signal a training bug. It's a safety check, not a quality measure.

**Online A/B.** The only trustworthy signal. Route a small percentage of traffic to the new model and measure real outcomes.

And the subtlety worth naming: **you have to measure the right window.** A model that increases clicks in a one-week test may decrease retention over three months — clickbait works, briefly. So the experiment has to run long enough to catch the objective you actually care about, which is often longer than anyone wants to wait.

I'd want holdout groups running for extended periods precisely for this, rather than only short experiments.

---

> **▶ WHY THIS WORKS**
>
> **The latency budget opens it.** Deriving "50 microseconds per candidate" from the feed's p99 target immediately makes this a systems problem with a hard constraint, rather than a vague discussion of models. Everything that follows — two-stage ranking, feature denormalization, batched inference — is a consequence of that number.
>
> **Connecting the two-stage funnel to bi-encoder/cross-encoder** shows the candidate recognizes a recurring pattern rather than treating this as a novel problem. And "stage one determines the ceiling, so tune it for recall" is the non-obvious correct instinct.
>
> **The feature categorization is the systems core.** Four categories with different fetch profiles, then two specific optimizations (precompute the viewer's affinity map; denormalize post features into the pool) with the tradeoff of the second named honestly — bigger pool, staler engagement counts, accepted in stage one only.
>
> **The three training failures build on each other.** Position bias means the model learns to reproduce the previous ranker. The feedback loop means unsurfaced content can never become a positive example. Training-serving skew means your offline numbers are lies. Each is real, each is named precisely, and the candidate says which are hard rather than solved.
>
> **The objective answer refuses the easy version.** Naming that pure engagement optimization is known to reward outrage and clickbait — and then giving a multi-objective formulation with negative signals weighted heavily — is the difference between describing a ranker and understanding what one is for. Saying the weights belong to product, not engineering, is the right allocation of a decision most engineers would quietly make themselves.
>
> **The evaluation answer ranks its own methods by trustworthiness** and explains why offline replay is systematically optimistic — tying it back to position bias from two minutes earlier. Then the closing point about measurement windows (clickbait wins in a week and loses in three months) is the kind of thing that only occurs to someone who's thought about what these experiments actually measure.

---

## What This Branch Adds Over the Merge Branch

| | Merge deep dive | Ranking deep dive |
|---|---|---|
| Domain | Distributed systems | Systems ↔ ML boundary |
| Hard part | Correctness under two sources | Latency budget + training loop |
| Standout insight | Storage architecture biases the candidate distribution | Engagement optimization is a known pathology, not a neutral objective |
| Risk of the branch | Getting lost in edge cases | Drifting into ML theory the interviewer didn't ask for |

**If you get to choose:** pick the merge if the interviewer seems distributed-systems oriented, ranking if they've signalled ML or product interest. Both are legitimate 15-minute deep dives.

**If they pick ranking and you're uncomfortable:** anchor hard on the latency budget. Fifty microseconds per candidate is a systems constraint, and two-stage ranking, feature denormalization, and batched inference are all systems answers. You can deliver a strong ranking deep dive without claiming ML expertise — but you do need to know that position bias, the feedback loop, and training-serving skew exist, because those three are what an interviewer will probe for.

## The L4 core of this branch

- The latency budget → why you can't rank 1,200 with the real model
- Two-stage ranking, and that stage one sets the ceiling
- Where features come from, and why viewer×author crosses are the expensive ones
- Model served separately, with batched inference
- Position bias — training on clicks teaches you to reproduce the current ranker
- Multi-objective, with negative signals weighted
- Online A/B as the only trustworthy evaluation

The L5 extras: denormalizing post features into the pool with the staleness tradeoff, the feedback loop and exploration slots, training-serving skew and point-in-time correctness, the engagement pathology, and the measurement-window point about clickbait.