# Mock Interview Transcript — "Design a News Feed" (Version 2)

**Variant:** chronological ordering · symmetric friendship
**Format:** 45 minutes, calibrated at Google L5 depth
**Annotations:** `▶ WHY THIS WORKS` blocks explain the move. Not part of the interview.

> **Why this version exists:** Version 1 assumed a *ranked* feed over an *asymmetric follow* graph. Flip both answers and you get a genuinely different system — not a simplified one. Two of v1's hardest problems disappear entirely, and two new ones appear that v1 never had to solve. Reading both is the clearest demonstration available that scoping answers are architecture decisions, not preliminaries.

---

## Phase 1 — Scope & Clarify (0:00 – 0:05)

**INTERVIEWER:** Design a news feed.

**CANDIDATE:** Two questions determine almost everything here, so let me ask those first.

**Is the feed chronological or ranked?**

**INTERVIEWER:** Chronological. Strict reverse-chronological order.

**CANDIDATE:** Good — and that has a consequence I want to state now because it shapes the whole design.

If the feed is ranked, it's a *view* over a candidate set — I precompute candidates, a model selects from them at read time, and the stored thing is never the answer. If it's chronological, **the feed is a list.** It's a merge of sorted streams, it's fully determined by the data, and I can precompute the actual answer rather than the inputs to one.

That makes the read path much simpler. It also removes a safety net I'd otherwise have, and I'll come back to that — it's where the hard part of this version lives.

**Second: is the relationship symmetric friendship or asymmetric follow?**

**INTERVIEWER:** Symmetric. Mutual friendship, both parties consent.

**CANDIDATE:** That's the bigger of the two answers, and it changes the shape of the problem more than people expect.

Symmetric friendship comes with a **cap**. Every real friendship product limits you — five thousand, a few thousand, some number — because the relationship requires mutual consent and there's a practical ceiling on how many people you can meaningfully know.

That cap eliminates the unbounded tail. In a follow graph, one account can have a hundred million followers and a single post generates a hundred million writes. In a friendship graph, the worst case is the cap. Bounded, and small.

So I'd expect the celebrity problem — which is normally *the* hard problem in feed design — to simply not exist here.

**INTERVIEWER:** Confirm the cap at 5,000.

**CANDIDATE:** Then a single post generates at most 5,000 fanout writes. That's milliseconds of work, not minutes.

Three more.

**Privacy model** — is all content friends-only, or are there per-post audience settings?

**INTERVIEWER:** Per-post. Friends, or a custom subset, or public.

**CANDIDATE:** That's real complexity and it's specific to this model. A follow graph is implicitly public — if you follow me, you see my posts. Friendship implies privacy expectations, which means visibility is a per-post property that has to be enforced somewhere.

**Unfriending** — when I unfriend someone, do their existing posts disappear from my feed?

**INTERVIEWER:** Yes. Immediately.

**CANDIDATE:** That's a retroactive visibility change, and it's going to force a design decision I'll flag in the data model.

**Freshness** — how quickly must a post appear in friends' feeds?

**INTERVIEWER:** What would you say?

**CANDIDATE:** Tighter than I'd need in a ranked feed, and this is the consequence of chronological I mentioned.

In a ranked feed, a post arriving thirty seconds late is invisible — the user can't distinguish "not yet fanned out" from "the ranker didn't surface it." Ranking masks fanout lag completely.

Chronological has no such mask. If posts are ordered by time, a late-arriving post has a *specific place* it belongs, and if the user has already looked past that place, they may never see it. The lag becomes visible as missing content.

So I'd target a few seconds, and more importantly I'd want the lag to be **bounded and known**, not just small on average. I'll explain why the bound matters more than the average when I get to the deep dive.

**CANDIDATE:** To confirm: strict reverse-chronological, symmetric friendship capped at 5,000, per-post audience settings, unfriending retroactively hides content, few-second bounded fanout lag. Out of scope: friend recommendations, groups and pages, ads, and messaging.

> **▶ WHY THIS WORKS**
> Both scoping questions get their consequence stated immediately rather than noted and filed. "Chronological means the feed is a list, not a view" and "symmetric means bounded fanout" are the two sentences that determine the rest of the interview.
>
> The strongest move is recognizing that **the cap eliminates the celebrity problem before it arises.** Most candidates carry the follow-graph mental model into a friendship problem and design a hybrid they don't need.
>
> The freshness answer is the best in the phase: it identifies that ranking was doing *hidden work* in v1 — masking fanout lag — and that removing it makes a previously-invisible property user-visible. Noticing what a removed feature was silently providing is a sophisticated observation.

---

## Phase 2 — Estimation (0:05 – 0:10)

**CANDIDATE:** The numbers here look worse than the follow-graph case in one dimension and dramatically better in another, and the second one is what matters.

*[writes]*

```
SCALE
  Total accounts                  2B
  DAU                             500M
  Posts per day                   100M
  Feed loads per user per day     ~12

FRIEND DISTRIBUTION   ← compare to v1's follower distribution
  median                          ~200
  mean                            ~350
  p99                             ~2,000
  MAX                              5,000   ← hard cap

  Note: no long tail. The distribution is
  roughly bounded and roughly symmetric.

FANOUT VOLUME
  100M posts × 350 mean friends
  = 35 BILLION writes/day        ← HIGHER than v1's 20B
  → ~405K/sec avg

  After skipping inactive users (500M DAU / 2B total):
  35B × 25% ≈ 8.75B/day  →  ~100K/sec avg, ~300K peak

MAX BURST PER POST   ← the number that matters
  5,000 writes.
  At ~100K writes/sec cluster capacity: 50 milliseconds.

  Compare v1: 100,000,000 writes for one post
              = 7 minutes of total pipeline
```

**CANDIDATE:** Two observations.

**The total volume is higher than the follow case.** Thirty-five billion versus twenty billion, because the mean friend count is higher — friendship distributions have a fat middle, whereas follower distributions have a tiny median and a monstrous tail. So on aggregate throughput, this is the harder workload.

**And it doesn't matter,** because the burst is bounded. Five thousand writes is fifty milliseconds. There is no post anywhere in the system that can block the pipeline.

That's the reframe: **the cap converts a tail problem into a volume problem, and volume is the easy kind.**

Volume you solve with money — more Redis nodes, more fanout workers, and it scales linearly. A burst you cannot solve with money, because the work arrives in a single instant and no amount of capacity spreads it over time. Version one needed an architectural workaround, the push/pull hybrid, precisely because no amount of provisioning fixed the burst.

Here, pure fanout-on-write works for every single user. **No hybrid. No classification. No pull path. No merge.**

That's an enormous simplification, and it came entirely from one scoping answer.

**INTERVIEWER:** So there's no equivalent of the celebrity problem at all?

**CANDIDATE:** Not in the fanout sense, and I want to be precise about that rather than just saying no.

The reason the celebrity problem exists is **unbounded fan-out from a single write**. The cap removes exactly that. A user with 5,000 friends is 25 times the median, which is a factor you handle with normal capacity planning — it is not a factor of 500,000 like the follow case.

What *does* remain is a milder version at the **read** end. A user with 5,000 friends receives roughly 25 times more posts into their pool than the median user, so their pool churns faster and they need a larger one to cover the same time window. That's a real per-user variance and I'd size pools accordingly, but it's a tuning question rather than an architectural one.

There's also a subtler one: heavy posters. If one of my 5,000 friends posts 200 times a day, they can dominate my feed. In a ranked feed the ranker naturally suppresses that. Chronological has no defense — they genuinely are the most recent posts.

That's a product problem more than a systems one, and the usual answer is a per-author cap within a time window. Worth noting that it's a problem *created* by choosing chronological.

> **▶ WHY THIS WORKS**
> "The cap converts a tail problem into a volume problem, and volume is the easy kind" is the reframe of the phase, and it's earned by showing both numbers — higher aggregate, bounded burst — rather than asserting the conclusion.
>
> Explicitly noting that v1's whole hybrid architecture is unnecessary here, and that it came from a single scoping answer, is the observation that makes this transcript worth reading alongside v1.
>
> The answer to "no celebrity problem at all?" is careful rather than dismissive: it distinguishes the write-side problem (gone) from the read-side variance (real but minor) and then surfaces the heavy-poster problem, which chronological *introduces*. Volunteering a problem your own design choice created is a strong signal.

---

## Phase 3 — API & Data Model (0:10 – 0:15)

**CANDIDATE:** The API is noticeably simpler than the ranked case, and the difference is worth pointing at.

*[writes]*

```
POST /v1/posts
     Idempotency-Key: <uuid>
     { text, mediaIds[], audience: FRIENDS | CUSTOM | PUBLIC,
       customAudienceId? }
     → 201 { postId, createdAt }

GET  /v1/feed?before=<postId>&limit=20
     → { items[], oldestPostId }
                 ↑ the cursor is just a postId

POST   /v1/friends/requests   { targetUserId }
POST   /v1/friends/requests/{id}/accept
DELETE /v1/friends/{userId}                  ← unfriend
```

**CANDIDATE:** The cursor is the clearest contrast with version one.

```
  RANKED FEED (v1)
    Ranking changes between page loads.
    Item ranked #25 becomes #15 → appears on page 2 unseen.
    Item ranked #18 becomes #22 → appears on BOTH pages.
    → cursor must PIN a stored ranking for the session

  CHRONOLOGICAL FEED (v2)
    Ordering is a total order on postId. It never changes.
    "everything before postId X" is deterministic
    and stable forever.
    → cursor is literally just a postId
```

That's a genuine simplification — no session state, no stored rankings, no expiry. Pagination is correct by construction because the ordering is a property of the data rather than a computation.

**CANDIDATE:** Data model:

```
POSTS
  postId (PK, snowflake — time-ordered)
  authorId, text, mediaIds[]
  audience       FRIENDS | CUSTOM | PUBLIC
  customAudienceId
  createdAt, isDeleted

FRIENDSHIPS                    ← symmetric
  (userId, friendId) [PK]
  since, status
  Stored in BOTH directions, but they are the
  SAME logical set, not two different indexes.

FEED   (Redis, per user)
  feed:{userId} → sorted set
      member: postId
      score:  postId
  Capped at ~500, covering the recent window
```

**CANDIDATE:** Three things worth flagging, and each is a change from version one.

**The graph is simpler despite still being stored twice.** In the follow model I needed two genuinely different indexes — followers for pushing, following for pulling — with different contents and different uses. Here, `friends(A)` is one set that serves both purposes. I still write two rows per friendship because I need to traverse from either endpoint, but the semantic complexity is gone: there's no risk of the two views meaning different things, and a consistency check is just "does A appear in B's set and vice versa."

**The pool is smaller — 500, not 1000.** In a ranked feed the pool is a candidate set, and the ranker wants breadth to choose from. Here it's an actual feed cache, and I only need enough to cover a typical session's scroll depth. Anything deeper can be served by **k-way merging from the source**, because chronological ordering is deterministic — I can always reconstruct the exact correct feed from friends' post lists. That's not possible in a ranked feed, where deep pages would require re-running the ranker.

**Audience is a per-post field**, which sets up the visibility problem I flagged in scoping.

**INTERVIEWER:** Talk about that. How does unfriending work if posts are already in my feed?

**CANDIDATE:** This is the problem specific to this variant, and there are two options with very different cost profiles.

```
  OPTION A — un-fanout on unfriend
    Find every one of Alice's posts in Carol's pool, remove them.
    · needs a reverse index (which posts in this pool are
      from which author) — extra storage on every entry
    · unfriending becomes a scan-and-delete
    · but read path stays clean

  OPTION B — filter at read
    Leave the pool alone. On every feed load, check whether
    Carol is still friends with each post's author.
    · unfriend is a single graph write, instant
    · read path pays on EVERY load, for EVERY candidate
```

I'd take **option B**, filtered efficiently.

The reasoning: unfriending is rare, feed loads are constant. But a naive option B is 500 graph lookups per feed load, which at 175K loads/sec is 87 million graph reads per second. That's not viable.

The fix is the same shape as the affinity-map trick in a ranked system: **fetch Carol's friend set once per request** and filter in memory. Her friend set is at most 5,000 IDs, roughly 40 KB, and it's cacheable per session.

```
  Per feed load:
    1 graph read  →  friend set (≤5,000 IDs, ~40 KB)
    500 in-memory set lookups  →  microseconds
```

One network read, not five hundred. And the friend set is useful for other things in the same request, so it amortizes.

The cost is that the pool accumulates dead entries from unfriended people, wasting slots. I'd have a background job prune them lazily — it's not urgent, since the read filter already guarantees correctness.

> **▶ WHY THIS WORKS**
> The cursor comparison is the clearest possible demonstration of what the scoping answer bought. Showing v1's failure mode next to v2's "correct by construction" makes the simplification concrete rather than asserted.
>
> The observation that the graph is *semantically* simpler even though it's still written twice is precise — it corrects a lazy reading of "symmetric means store it once" while identifying the real benefit.
>
> "Deep pages can be k-way merged from source because chronological is deterministic" is a genuine insight that only holds in this variant, and it justifies the smaller pool.
>
> The unfriend answer weighs both options on their actual cost profile (rare operation vs constant operation), picks one, identifies that the naive version doesn't scale, and applies a known pattern to fix it. That's the full reasoning chain, not just the answer.

---

## Phase 4 — High-Level Design (0:15 – 0:27)

**CANDIDATE:** This is substantially simpler than the hybrid, and I want to show why by drawing it.

*[draws]*

```
┌──────────────────────────────────────────────────────────────────┐
│  WRITE PATH                                                      │
└──────────────────────────────────────────────────────────────────┘

  POST /v1/posts
       │
       ▼
  ┌──────────────┐
  │ Post Service │──▶ [Post Store]     durable, returns 201
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │    Kafka     │   partitioned by authorId
  └──────┬───────┘
         ▼
  ┌───────────────────────────────────────────────┐
  │  FANOUT SERVICE                               │
  │                                               │
  │   friends = graph.friendsOf(authorId)  ≤5,000 │
  │   filter by post audience                     │
  │   filter to ACTIVE users only                 │
  │                                               │
  │   for each recipient:                         │
  │       ZADD feed:{recipient} postId postId     │
  │       ZREMRANGEBYRANK ... trim to 500         │
  │                                               │
  │   ← NO classification. NO branching.          │
  │     Every author takes the same path.         │
  └──────────────────┬────────────────────────────┘
                     ▼
            ┌──────────────────┐
            │   Feed Store     │
            │   (Redis)        │
            └──────────────────┘


┌──────────────────────────────────────────────────────────────────┐
│  READ PATH                                                       │
└──────────────────────────────────────────────────────────────────┘

  GET /v1/feed?before=X
       │
       ▼
  ┌────────────────┐
  │  Feed Service  │
  └───────┬────────┘
          │
          ├──1──▶ [Graph]      friend set, ONE read, cached
          │
          ├──2──▶ [Feed Store] ZREVRANGEBYSCORE feed:{user} (X -inf
          │                    → 500 postIds, already ordered
          │
          ├──3──▶  FILTER      still-friends? audience allows?
          │                    deleted? blocked?
          │                    (all in-memory against friend set)
          │
          ├──4──▶ [Post Store] hydrate top 20
          │
          └──5──▶  return      no ranking, no session state


┌──────────────────────────────────────────────────────────────────┐
│  DEEP SCROLL FALLBACK  (pool exhausted)                          │
└──────────────────────────────────────────────────────────────────┘

  k-way merge from source:
     for each friend: fetch posts before cursor
     merge sorted streams, take top N
  ← possible ONLY because ordering is deterministic
```

**CANDIDATE:** Compare that to version one's diagram. Gone entirely: the classification service, the pull path, the merge stage, the ranking service, the session store. What's left is one path from post to pool and one path from pool to screen.

Let me trace a post.

Author posts. Durable write, 201 returned. Kafka event.

Fanout service reads the friend list — at most 5,000 IDs, one graph read. Filters by the post's audience setting: friends-only goes to everyone, a custom audience is intersected with the friend set, public still fans out to friends but is also readable by others through the profile path.

Then it filters to active users, which is the same optimization as version one — three quarters of accounts are inactive and writing to their pools is waste.

Then it writes. At most 5,000 small Redis writes, batched into pipelines of a few hundred. Fifty milliseconds worst case.

The read is a single sorted-set range query. The pool is already in the correct order — no merge, no sort, no ranking. Filter, hydrate, return.

**INTERVIEWER:** Why partition Kafka by authorId?

**CANDIDATE:** So that one author's posts are fanned out in order, which matters more here than it did in the ranked version.

If Alice posts twice in quick succession and the two fanout jobs run on different partitions, they can complete out of order — post two lands in some pools before post one. In a ranked feed that's invisible, because ranking reorders everything anyway. In a chronological feed, a reader who loads between the two writes sees Alice's second post and not her first, and depending on how their cursor advances, may never see the first.

Partitioning by author gives ordered processing per author, which eliminates that case entirely.

It does **not** solve cross-author ordering — Alice's post and Bob's post can still complete out of order relative to each other — and that's the harder problem I flagged in scoping. I'd like to make that the deep dive.

**INTERVIEWER:** Let's do that. But first — you filter by audience at write time and again at read time. Why both?

**CANDIDATE:** They're catching different things, and the distinction matters.

**Write-time filtering** is about the audience as it was *when the post was made.* If I post to a custom list of fifty people, only those fifty pools should ever receive it. That's a property of the post and it's fixed at creation, so resolving it once at fanout is correct and cheap.

**Read-time filtering** is about the relationship as it is *now.* Carol was my friend when I posted, so the post is legitimately in her pool. She unfriended me since. The post must not render.

So: write-time enforces the author's intent at publication, read-time enforces the current relationship state. Neither can substitute for the other — write-time can't know about future unfriending, and read-time can't reconstruct a custom audience the author has since edited.

I'd note that this makes read-time filtering **security-relevant**, not just a correctness detail. If the read filter fails, someone sees content they shouldn't. So it belongs in the request path, not in a cache that could serve stale membership.

> **▶ WHY THIS WORKS**
> Explicitly listing what's *absent* compared to v1 — classification, pull path, merge, ranking, session store — makes the simplification legible. It also demonstrates the candidate is holding both designs in mind.
>
> The Kafka partitioning answer is precise: it solves per-author ordering, it explicitly does *not* solve cross-author ordering, and the candidate names the remaining problem and asks to go deep on it. Setting up your own deep dive with an honest statement of what you haven't solved is a strong move.
>
> The write-time-vs-read-time filtering answer distinguishes two things that look redundant and aren't — intent-at-publication versus relationship-now — and closes by classifying the read filter as security-relevant. That last sentence changes where it can live in the architecture.

---

## Phase 5 — Deep Dive: Ordering Under Async Fanout (0:27 – 0:42)

**CANDIDATE:** This is the problem that only exists in the chronological version, and I think it's genuinely subtle.

**Level one: the bug.**

```
  Carol's friends include Alice (4,000 friends) and Bob (50).

  t = 0.000   Alice posts.  postId encodes t=0.000
  t = 0.001   Bob posts.    postId encodes t=0.001

  Fanout runs concurrently:
     Bob's post   →   50 writes  →  done at t = 0.05
     Alice's post → 4,000 writes →  done at t = 2.00

  t = 0.10   Carol opens the app.
             Her pool contains Bob's post. Not Alice's.
             Feed shows: [ P_bob ]
             Client records: newest seen = P_bob

  t = 2.00   Alice's post lands in Carol's pool.
             Sorted by postId → it sits BELOW P_bob,
             because it was created 1ms EARLIER.

  t = 5.00   Carol pulls to refresh.
             Client asks: "anything newer than P_bob?"
             Server: no.

  → Carol never sees Alice's post. Ever.
```

**CANDIDATE:** The post was inserted **below the read horizon.** It's in her pool, correctly ordered, and permanently invisible — because refresh only ever looks upward from the newest item she's seen.

The insidious part is that nothing is broken. The pool is correct. The ordering is correct. The query is correct. The post is simply in a place the user will never look.

And note this cannot happen in a ranked feed: ranking re-evaluates the whole candidate set on every load, so a late arrival just becomes a candidate like any other. **Ranking was silently providing insertion-safety, and removing it exposed this.**

**Level two: why the obvious fixes are unsatisfying.**

```
  FIX A — refresh by time window, not by cursor
    "give me the last 30 minutes" and dedupe client-side.
    · Works.
    · Costs bandwidth on every refresh.
    · Client must hold a dedup set.
    · Pathological for users who refresh constantly.

  FIX B — server tracks what each client has seen
    Store a per-client read horizon, return insertions below it.
    · Works.
    · Per-device state for 500M users × multiple devices.
    · Now the feed has session state — the thing chronological
      just saved me from.

  FIX C — timestamp posts at fanout completion, not creation
    Then nothing can ever insert below.
    · Breaks chronology. Alice's post would appear NEWER
      than Bob's despite being written first.
    · The feed no longer means what it says.
```

Each of these solves the symptom by paying somewhere I don't want to pay.

**Level three: bound the lag, then hold the feed behind it.**

**CANDIDATE:** The cleanest fix inverts the problem. Rather than handling late insertions, make them impossible to be late *relative to what the user can see.*

```
  Establish a bounded maximum fanout lag — say 3 seconds.
  Enforce it: monitor it, alert on it, and treat a
  breach as an incident.

  Then serve feeds as of  (now − 3 seconds).

     ┌─────────────────────────────────────────┐
     │  posts older than (now − 3s)            │
     │  → fanout GUARANTEED complete           │
     │  → safe to serve                        │  ← visible
     ├─────────────────────────────────────────┤
     │  posts newer than (now − 3s)            │
     │  → fanout may be in flight              │  ← withheld
     │  → withhold                             │
     └─────────────────────────────────────────┘

  Nothing can insert below the read horizon,
  because the horizon never advances into the
  in-flight window.
```

**CANDIDATE:** The user's feed is deliberately three seconds stale, and three seconds is completely invisible to a human scrolling a feed. In exchange, the ordering guarantee becomes airtight — every post the user can see was fully fanned out before they could see it.

This is the same idea as a **watermark** in stream processing: you don't try to handle arbitrary late data, you define a bound, hold results behind it, and treat anything later as an exception rather than a case.

Two things make it work.

**The bound must be enforced, not hoped for.** If fanout lag exceeds three seconds, the guarantee breaks silently and the bug returns. So the lag is a hard SLO with alerting, and if it's breached the correct response is to *widen the withholding window* automatically rather than let bad ordering through. Degraded freshness beats invisible content.

**The author is exempt.** I see my own post immediately, because self-visibility is served from my own post list rather than my pool. That was in scoping and it still holds.

**INTERVIEWER:** What if fanout lag genuinely spikes to a minute? A backlog during a traffic event.

**CANDIDATE:** Then the automatic widening kicks in and feeds become a minute stale, which is noticeable but not broken — and it's strictly better than the alternative of users permanently missing posts.

But I'd want a second layer for the sustained case, because a minute of staleness across a traffic event is a bad experience.

The observation is that during a backlog, the pool is *incomplete* — not wrong, just missing recent entries. And I have a fallback that produces the correct answer independently: the **k-way merge from source** I mentioned for deep scroll.

So under sustained lag, the read path can bypass the pool for the recent window:

```
  Normal:     pool covers everything → serve from pool
  Degraded:   pool is stale beyond threshold
              → merge the last N minutes directly from
                friends' post lists
              → correct and complete, just more expensive
```

That's a graceful degradation with the right shape: it costs read capacity exactly when write capacity is the thing that's constrained, so the two don't compete for the same resource.

It only exists as an option because chronological ordering is deterministic. In a ranked feed I couldn't reconstruct the answer this way — I'd have to re-run the ranker, and the pool isn't the input to it in the same recoverable sense.

**INTERVIEWER:** How would you detect the original bug in production?

**CANDIDATE:** That's the right question, because it's a silent failure — no error, no latency spike, no user complaint that's traceable to it. Someone just doesn't see a post, and they have no way to know.

Three approaches.

**Direct measurement of the invariant.** For a sampled set of feed loads, compute what the feed *should* contain by k-way merging from source, and compare against what was served from the pool. Any post present in the merge and absent from the served feed is either a filtered item — which I can account for — or a missed insertion. That's the ground truth check, and I'd run it continuously on a small sample.

**Fanout lag distribution, not the mean.** The bug is caused by the *spread* between fast and slow fanouts, not by lag being high. Two posts one millisecond apart with lag of 50ms and 2s is exactly the failure. So the metric that matters is p99 fanout lag, and specifically the ratio between p50 and p99 — a widening gap means insertion risk is rising even if the average looks fine.

**Insertion-below-horizon counter.** The fanout service can detect it directly: when writing a post into a pool, check whether it sorts below entries already there beyond some threshold. That's a cheap check on the write path and it directly counts the dangerous event rather than inferring it.

I'd treat that third one as the primary signal, because it measures the actual thing rather than a proxy.

> **▶ WHY THIS WORKS**
> The bug is built up as a timeline with concrete timestamps and a definite ending — "Carol never sees Alice's post. Ever." That construction makes a subtle failure undeniable.
>
> "Ranking was silently providing insertion-safety, and removing it exposed this" is the strongest sentence in the transcript. It identifies that a feature removed in scoping was doing load-bearing work nobody had named, which is exactly the kind of second-order reasoning that separates depth from recall.
>
> Rejecting three obvious fixes with specific costs — bandwidth, session state, broken semantics — before presenting the watermark answer makes the chosen solution feel earned rather than pulled from memory. And connecting it to stream-processing watermarks shows the candidate recognizes a general pattern.
>
> The degradation answer is elegant: under write-path backlog, shift work to the read path, which has spare capacity precisely because the constrained resource is different. And noting that this option *only exists because ordering is deterministic* ties it back to the Phase 1 scoping answer.
>
> The detection question gets three methods ranked by directness, with the best one measuring the event itself rather than a proxy. For a silent failure, that's the correct instinct.

---

## Phase 6 — Failure Modes & Wrap (0:42 – 0:47)

**INTERVIEWER:** Five minutes. What else breaks?

**CANDIDATE:** Five things, and several are specific to this variant.

**The heavy poster dominating feeds.** I flagged this in estimation. One friend posting two hundred times a day genuinely occupies most of my recent window, and chronological ordering has no defense — those really are the newest posts.

A ranked feed suppresses this automatically. Here I need an explicit rule: cap consecutive posts from one author, or cap posts per author within a time window, and displace the excess. That's a product decision being forced by the ordering choice, and I'd want it acknowledged as such rather than treated as a bug fix.

**Feed gaps for high-friend users.** A user with 5,000 friends receives roughly 25 times the median post volume. With a 500-entry pool, their window might cover only a few hours, so opening the app after a day means the pool has already rolled past everything they missed.

Two options: size pools proportionally to friend count rather than uniformly, or lean harder on the k-way merge fallback for these users. I'd do the first, since pool memory is cheap relative to read amplification.

**The friend request state machine.** This is complexity version one didn't have at all. A friendship has states — none, pending, accepted, blocked — and both endpoints must agree. Two people sending simultaneous requests to each other should result in one friendship, not two pending requests. Accepting must be atomic across both directions.

I'd model it as a single edge keyed on the canonical ordered pair, with the two directional index rows derived from it. That way there's one source of truth for the state and the indexes are projections rather than independent facts.

**Unfriend and blocked-content leakage.** The read filter is security-relevant, as I said. If it's cached and the cache is stale, someone sees content after being unfriended. So the friend set fetched per request must not come from a long-lived cache — short TTL, or invalidated on graph writes.

I'd also make the filter fail closed: if the friend set can't be fetched, serve nothing rather than serving unfiltered. That's the opposite of the usual availability instinct and it's correct here, because the failure mode is a privacy breach.

**Fanout backlog making lag visible.** Covered in the deep dive — the automatic widening plus the read-path fallback. The thing I'd add is that fanout lag deserves a much tighter alert threshold here than in a ranked system, because the user-visible consequence is more severe.

**Observability** — four metrics:

- **Fanout lag p50 and p99, and the ratio between them.** The spread is what causes insertion, not the absolute value.
- **Insertion-below-horizon count.** Direct measurement of the deep dive's failure.
- **Pool coverage window per user percentile.** How many hours of feed the pool actually covers — a falling p10 means high-friend users are getting gaps.
- **Read-filter rejection rate.** A spike means either mass unfriending or a graph problem, and both need attention.

**INTERVIEWER:** What would you revisit?

**CANDIDATE:** Two things.

The friend request state machine got a paragraph and it deserves more. Symmetric relationships with mutual consent are a genuine distributed state problem — two parties, concurrent actions, and a state that must be consistent from both sides. Simultaneous requests, accept-while-blocking, unfriend-while-request-pending — these are all real races. I gestured at a canonical-pair model and moved on, and in a real design that's a subsystem, not a table.

The larger one: **I chose chronological because it was specified, and I should say plainly that it's a worse product for most users.**

Everything I gained — deterministic ordering, stable pagination, no ranking infrastructure, a reconstructable feed — is real engineering simplification. But the two problems I couldn't solve cleanly are both *product* problems that ranking would have solved for free: the heavy poster dominating the feed, and the fact that a user with 5,000 friends receiving 25× the volume has no mechanism to see the *important* posts rather than merely the *recent* ones.

Chronological scales to the median user and degrades for the heavy user, and it has no way to distinguish a friend's engagement announcement from their lunch photo. Those are exactly the pressures that pushed every large feed product toward ranking, and I don't think that was primarily an engagement-optimization decision — it was a response to the volume problem I've just designed around rather than solved.

So the honest summary is that I've built a simpler system that's correct, and I'd expect to be asked to add ranking within a year of launch.

**INTERVIEWER:** Good. That's time.

> **▶ WHY THIS WORKS**
> Three of the five failure modes are specific to this variant — heavy posters, high-friend gaps, the request state machine — which demonstrates the candidate is reasoning about *this* design rather than reciting feed failure modes generally.
>
> "Fail closed on the read filter" is the correct and counterintuitive call, and naming it as the opposite of the usual availability instinct shows the candidate knows they're deviating and why.
>
> The metric about the *ratio* between p50 and p99 fanout lag is precise — it measures the mechanism of the bug (spread) rather than a proxy (absolute lag).
>
> The self-critique is the best in either version. Rather than finding a gap, the candidate argues that the specified requirement is probably wrong: chronological is a simpler system and a worse product, and the two problems they couldn't solve are exactly the ones ranking solves for free. Then they make a historical claim — that feeds moved to ranking as a response to volume, not primarily for engagement — which is a defensible position stated as an opinion. Disagreeing with the premise you were handed, with reasons, is a senior move.

---

# What Changed From Version One

## The two scoping answers, and their blast radius

| | **v1: ranked + follow** | **v2: chronological + friends** |
|---|---|---|
| Max fanout per post | 100,000,000 | **5,000** |
| Total fanout volume | 20B/day | **35B/day** (higher!) |
| Fanout architecture | Push/pull hybrid + classification | **Pure push. No branching.** |
| Feed store contains | Candidates for a ranker | **The actual feed** |
| Pool size | 1,000 | **500** |
| Cursor | Pinned session ranking | **A postId** |
| Deep scroll | Re-rank required | **k-way merge from source** |
| Graph | Two different indexes | **One set, two directions** |
| Ranking service | Required | **None** |
| Merge stage | Required, 4 failure modes | **None** |
| Fanout lag | Masked by ranking | **User-visible** |
| Ordering guarantee | Free (re-ranked each load) | **Requires a watermark** |
| Retroactive visibility | N/A | **Read-time filtering, security-relevant** |
| Heavy poster | Suppressed by ranker | **Unsolved without an explicit cap** |

## The three transferable lessons

**1. A cap changes the class of problem.** Unbounded fanout is an architectural problem — no provisioning fixes a 100M-write burst. Bounded fanout is a capacity problem — you buy nodes. The friendship cap deleted v1's entire hybrid architecture, and it did so in Phase 1.

**2. Removing a feature can expose work it was silently doing.** Ranking wasn't just ordering posts. It was re-evaluating the candidate set on every load, which made late-arriving posts harmless. Take ranking away and that safety vanishes, and you have to reconstruct it deliberately with a watermark. **Ask what a feature is quietly providing before you remove it.**

**3. Simpler infrastructure is not automatically a better product.** v2 is genuinely easier to build, operate, and reason about. It's also worse for heavy users and has no mechanism to surface important content over recent content. The candidate saying so at the close — and arguing the specified requirement is probably wrong — is more valuable than defending the design.

## Which version to practise

**Practise v1** if the interviewer says Facebook, Instagram, Twitter, or TikTok. Ranked feeds over asymmetric graphs are what those products are, and the celebrity problem is what they'll probe.

**Practise v2** if they say "friends," "chronological," or describe a smaller-scale or privacy-focused product. Also worth knowing because **an interviewer may flip a scoping answer mid-interview** to see whether your design was reasoned or recalled.

If that happens, the correct response is the one this transcript opens with: *state what the answer changes, immediately, before designing anything.*