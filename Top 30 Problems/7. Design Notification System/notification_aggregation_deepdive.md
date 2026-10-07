# Notification System — Alternate Deep Dive: The Aggregation Window

**Drop-in replacement for Phase 5 (0:27 – 0:42)** of the Notification System transcript, for when the interviewer picks aggregation instead of delivery.

> **Why this branch is different:** the delivery deep dive is about *reliability* — duplicates, retries, provider limits. This one is about *product quality*, and it's where the perceived value of a notification system actually lives. It's also the branch where the correct answer is most often "it depends on the user," which makes it harder to give a crisp answer and more rewarding when you do.

---

**CANDIDATE:** That's the skeleton. The two deepest areas are delivery reliability at the third-party boundary and the **aggregation window** — which I'd argue is where most of the product quality lives. Preference?

**INTERVIEWER:** Aggregation. Why is it hard?

---

## Phase 5 — Deep Dive: The Aggregation Window (0:27 – 0:42)

**CANDIDATE:** Because it's a latency-versus-quality tradeoff with no fixed correct answer, and getting it wrong in either direction is directly visible to the user.

**Level one: the tension.**

```
  Alice posts. Five people like it over ten minutes.

  NO AGGREGATION — send immediately
    12:00  "Bob liked your post"
    12:02  "Carol liked your post"
    12:04  "Dave liked your post"
    12:07  "Erin liked your post"
    12:09  "Frank liked your post"
    → 5 buzzes. Alice mutes the app.

  MAXIMUM AGGREGATION — batch everything, send at midnight
    23:59  "5 people liked your post today"
    → 1 buzz. Also 12 hours stale and worthless.
    → Alice ignores it, and the moment has passed.

  The right answer is somewhere in between, and it is
  NOT the same somewhere for every post.
```

The naive design is a fixed window — collect for five minutes, then flush. That's better than nothing and it's wrong at both ends of the distribution.

```
  Post getting 1 like/hour
    → 5-minute window collects exactly one like
    → you added 5 minutes of delay for zero batching benefit
    → and the FIRST like is the most emotionally significant
      one. Delaying it is the worst possible trade.

  Post getting 100 likes/minute
    → 5-minute window collects 500
    → but you send 12 notifications per hour
    → still far too many
```

So a fixed window is wrong for quiet posts and wrong for viral ones. It's only right in the middle, which is where the fewest posts are.

**Level two: what the window should actually do.**

**CANDIDATE:** Three mechanisms, and they compose.

**Flush-on-first.** The first event for an entity sends immediately, with no window at all.

```
  12:00  Bob likes    →  SEND NOW  "Bob liked your post"
                          window opens
  12:02  Carol likes  →  batched
  12:04  Dave likes   →  batched
  12:09  window flush →  "Carol, Dave and 1 other liked
                          your post"
```

This matters more than it looks. The first like on a post is qualitatively different from the fiftieth — it's the one the user actually wants to know about, and delaying it is the worst possible trade. Batching from the second event onward gets the immediacy where it counts and the suppression where it counts.

**Exponential backoff on the window.** Each successive flush waits longer.

```
  1st notification   immediate
  2nd                after 1 min
  3rd                after 5 min
  4th                after 20 min
  5th                after 1 hour
  ...capped at, say, 4 hours
```

A post that gets one like gets one immediate notification. A post going viral gets a handful of notifications with increasing summaries — and the user gets a sense of accelerating activity from the *sizes* of the batches rather than from their frequency.

This handles both ends of the distribution with one mechanism, which is why I prefer it to trying to classify posts as quiet or viral.

**Adaptive by arrival rate.** Backoff alone still fires on a schedule. I'd also let the observed rate influence the window: if events are arriving fast, extend; if they've stopped, flush early rather than waiting out the timer.

```
  window_duration = f(notification_count, arrival_rate)

  events stopped arriving  →  flush now, don't wait
  events accelerating      →  extend, batch will be richer
```

That early-flush case matters — if the activity has died down, holding a batch for another twenty minutes is pure staleness with no benefit.

**INTERVIEWER:** What's the grouping key? What gets batched with what?

**CANDIDATE:** That's the design decision underneath all of this, and there are several levels of aggregation that stack.

```
  LEVEL 1 — same entity, same type
    "Bob, Carol and 3 others liked your post"
    key: (user, post_1001, LIKE)
    → the obvious one, and the highest quality

  LEVEL 2 — same entity, related types
    "Bob liked and Carol commented on your post"
    key: (user, post_1001, *)
    → richer, but harder to render well

  LEVEL 3 — same type, across entities
    "You have 12 new likes"
    key: (user, *, LIKE)
    → much lower quality — no context, not actionable
    → but sometimes necessary at high volume

  LEVEL 4 — everything
    "You have 47 new notifications"
    key: (user, *, *)
    → the digest. Almost worthless individually,
      but it's what stops the channel from collapsing
      for a very high-activity user.
```

I'd default to level one, because it produces the most useful notification. But the level should **escalate as volume increases**:

```
  quiet user      → level 1, per entity
  active user     → level 1, with backoff
  very active     → level 2 or 3
  overwhelmed     → level 4 digest only
```

That escalation is really the frequency cap expressed as aggregation rather than as dropping. Rather than discarding notifications when a user hits their limit, collapse them harder. The user still learns that things happened; they just get one buzz instead of thirty.

I think that's meaningfully better than dropping, because dropping loses information the user might have wanted, and collapsing doesn't.

**Level three: where this gets genuinely hard.**

**CANDIDATE:** Three problems that the mechanisms above don't solve.

**Which actors do you name?**

```
  "Bob, Carol and 3 others liked your post"
             ↑
    Which two of the five do you name?
```

Not arbitrary — naming the two the user cares most about is the difference between a notification they open and one they dismiss. Ideally you'd name the closest relationships, or the most notable actors, or the ones they've interacted with recently.

That's a ranking problem embedded inside what looks like a formatting decision. I'd start with a simple heuristic — mutual connections first, then most-recently-interacted — but I'd flag that this is one of the highest-leverage small details in the system, and it's the kind of thing that gets left as "first two by timestamp" and never revisited.

**The window is state, and state can be lost.**

```
  Open windows live in Redis with a TTL.
  Redis node fails.
  → open windows are gone
  → those notifications are never sent
```

For engagement notifications I'd accept that. Losing a batch of like notifications during an incident is invisible to almost everyone.

But it does mean aggregation must be **strictly for suppressible types.** Transactional bypasses it entirely, which I said in the main design, and this is the concrete reason why — I'm willing to lose an aggregation window and I'm not willing to lose a password reset.

There's a subtler version: the window is also the **timer**. If the process that would have flushed it dies, nothing flushes. So I'd want the flush to be driven by a durable scheduler — a sorted set of due timestamps that a worker polls — rather than by in-process timers, which vanish with the process.

**Aggregation and quiet hours interact badly.**

```
  22:00  window opens, quiet hours begin
  22:00–08:00  events accumulate for 10 hours
  08:00  flush → "47 people liked your post"

  Is that one notification, or should the overnight
  activity have produced several?

  And the individual events are now up to 10 hours old.
  A "Bob liked your post" from 11pm is stale by morning.
```

I'd flush at the quiet-hours boundary with everything collapsed to a single summary, and I'd accept that overnight loses granularity. The alternative — queueing several notifications to fire in sequence at 8am — is much worse, because the user wakes to a burst.

The general rule I'd state: **when a batch spans a long gap, collapse harder rather than preserving structure.** The user doesn't want a replay of the night; they want to know what happened.

**INTERVIEWER:** How would you know whether your aggregation is any good?

**CANDIDATE:** That's the right question, and the honest answer is that the obvious metrics point the wrong way.

**Delivery metrics are useless here.** Every aggregation strategy delivers successfully. A five-minute window and a four-hour window both show 100% delivery.

**Open rate is misleading alone.** Aggressive aggregation raises open rate — fewer, richer notifications get opened more often as a percentage. But it also means fewer total opens. Optimizing open rate pushes you toward sending almost nothing.

So I'd measure three things together:

```
  1. Mute / disable rate per notification type
     ← the ceiling. Over-sending shows up here first,
       and it's the one I'd page on.

  2. Total engaged sessions attributable to notifications
     ← the floor. Under-sending shows up here.
       NOT open rate — absolute opens.

  3. Time-to-notification for the FIRST event
     ← the quality signal for the flush-on-first rule.
       If this is creeping up, immediacy is being lost.
```

The first two bound the problem from opposite directions, which is what makes them useful as a pair. Aggregation is a dial between them: turn it up and mute rate falls while total engagement falls too; turn it down and both rise until mute rate spikes.

The right setting is wherever total engagement peaks *subject to* mute rate staying below a threshold — and that's a constrained optimization, so I'd A/B test window parameters rather than reasoning my way to a number.

I'd also want it segmented, because I strongly suspect the right setting differs by user. A user with three friends and a user with three thousand have completely different activity volumes, and one global window parameter can't be right for both.

> **▶ WHY THIS WORKS**
> **Showing that a fixed window is wrong at both ends** — no batching benefit for quiet posts, still far too many notifications for viral ones — is what earns the more complex answer. It's only right in the middle, which is where the fewest posts are.
>
> **Flush-on-first** is the highest-value mechanism and the reasoning is product-level: the first like is qualitatively different from the fiftieth, and delaying it is the worst possible trade. That's an observation about human behavior driving a technical decision.
>
> **Exponential backoff handling both ends with one mechanism** is elegant, and the candidate says explicitly why they prefer it to classifying posts as quiet or viral — one mechanism beats a classifier plus two policies.
>
> **Escalating the aggregation level instead of dropping** reframes the frequency cap: rather than discarding notifications a user won't receive, collapse them harder so no information is lost. That's a better answer than the frequency cap in the main transcript.
>
> **"Which two actors do you name?"** is a genuinely non-obvious problem — a ranking problem hiding inside a formatting decision — and the candidate flags that it's usually left as "first two by timestamp" and never revisited.
>
> **The durable scheduler point** is a real systems detail: the window is also the timer, and in-process timers die with the process.
>
> **The measurement answer is the strongest part.** It names why delivery metrics are useless, why open rate alone is actively misleading (it pushes you toward sending nothing), and then bounds the problem with two opposing metrics. Recognizing that a metric can be optimized in a direction that destroys the product is a senior instinct.

---

## Comparison to the delivery branch

| | Delivery deep dive | Aggregation deep dive |
|---|---|---|
| Core problem | Reliability at a boundary you don't control | Product quality with no fixed right answer |
| Hard part | Duplicates, retries, provider limits | Window sizing, grouping, actor selection |
| Feels like | Distributed systems | Product engineering with systems constraints |
| Standout | Ambiguous timeout resolved per priority | Open rate is misleading — optimize it and you send nothing |
| Risk of the branch | Becoming a list of retry policies | Sounding like opinions rather than engineering |

**Pick delivery** if the interviewer has probed reliability, queues, or third-party integration.

**Pick aggregation** if they've asked about user experience, product tradeoffs, or "how do you decide what to send." It's also the better branch if you want to demonstrate that you think about what a system is *for*, not just whether it works.

**The trap in this branch** is that the answers can sound like preferences. Anchor everything in a mechanism or a measurement: flush-on-first *because* the first event is qualitatively different; backoff *because* it handles both distribution ends with one mechanism; A/B test *because* the right window is a constrained optimization you can't reason to.

## The L4 core of this branch

- The tension: no aggregation means mute, maximum aggregation means stale
- Why a fixed window is wrong at both ends of the distribution
- Flush-on-first, with the reason
- Exponential backoff on subsequent windows
- Grouping key = (user, entity, type)
- Aggregation state is disposable, so transactional must bypass it
- Mute rate as the ceiling metric

The L5 extras: escalating aggregation level instead of dropping, actor selection as an embedded ranking problem, the durable scheduler for flush timing, the quiet-hours collapse rule, and the measurement answer — particularly that open rate is misleading because optimizing it pushes toward sending nothing.
