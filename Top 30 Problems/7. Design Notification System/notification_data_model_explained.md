# The Notification System Data Model — Explained Simply

There are four stores. Each one answers **one question**. That's the easiest way to hold them.

```
1. DEVICE_TOKENS      "where do I send it?"
2. PREFERENCES        "should I send it at all?"
3. NOTIFICATION_LOG   "what have I sent?"
4. AGGREGATION_STATE  "what am I currently collecting?"
```

Let's walk through each with real data.

---

# 1. DEVICE_TOKENS — "where do I send it?"

## The problem it solves

You want to push to Alice. But "Alice" isn't an address — a phone is. And Alice might have three.

```
Alice has:
  · an iPhone
  · an iPad
  · an old Android she still uses sometimes
```

Each device has a **token** — a long string Apple or Google gave you when the app was installed. That token *is* the address.

```
"send to Alice"  →  which tokens does Alice have?  →  send to each
```

## What's stored

```
DEVICE_TOKENS      partition key = user_id

┌──────────┬────────────────────┬──────────┬─────────────┬──────────┐
│ user_id  │ token              │ platform │ last_seen   │ is_valid │
├──────────┼────────────────────┼──────────┼─────────────┼──────────┤
│ alice    │ "d3f8a91b2c..."    │ ios      │ 2026-08-29  │ true     │
│ alice    │ "8b2e01ff7a..."    │ ios      │ 2026-08-28  │ true     │
│ alice    │ "kx91mn03pq..."    │ android  │ 2026-02-11  │ false    │ ← dead
│ bob      │ "77aa3e0c19..."    │ android  │ 2026-08-29  │ true     │
└──────────┴────────────────────┴──────────┴─────────────┴──────────┘
```

Partitioned by `user_id`, so all of Alice's devices sit together. One lookup gets all her addresses.

## Why `is_valid` exists

Tokens die constantly:

```
· Alice uninstalls the app        → token dead
· Alice logs out                  → token dead
· She factory-resets the phone    → token dead
· Apple rotates the token         → old one dead
```

You find out **when you try to send** — the provider replies "invalid token." At that moment you mark it dead so you stop wasting sends on it.

If you never clean these up, you eventually send mostly to dead phones, and providers penalize senders with high invalid rates.

---

# 2. PREFERENCES — "should I send it at all?"

## The problem it solves

Alice doesn't want everything. She wants comment notifications, not like notifications. And nothing between 10pm and 8am.

## What's stored

```
PREFERENCES        partition key = user_id

┌──────────┬───────────────┬──────────────────┬────────────────────┐
│ user_id  │ type          │ channels         │ enabled            │
├──────────┼───────────────┼──────────────────┼────────────────────┤
│ alice    │ post_liked    │ [in_app]         │ true               │ ← no push
│ alice    │ post_comment  │ [push, in_app]   │ true               │
│ alice    │ new_follower  │ []               │ false              │ ← off
│ alice    │ promotional   │ [email]          │ true               │
└──────────┴───────────────┴──────────────────┴────────────────────┘

Plus per-user global settings:
┌──────────┬──────────────┬──────────────┬───────────┬─────────────┐
│ user_id  │ quiet_start  │ quiet_end    │ timezone  │ global_mute │
├──────────┼──────────────┼──────────────┼───────────┼─────────────┤
│ alice    │ 22:00        │ 08:00        │ IST       │ false       │
└──────────┴──────────────┴──────────────┴───────────┴─────────────┘

Plus things she's muted specifically:
  muted_entities: [ post_5522, thread_991 ]   ← "stop notifying
                                                 me about THIS"
```

## How it's used

Every single notification checks this first:

```
Bob liked Alice's post
        ↓
   Read PREFERENCES for alice
        ↓
   type = post_liked
   → channels = [in_app] only, no push
   → so: write to her in-app inbox, DON'T push
```

Read on **every** notification, so it's cached aggressively — this is the hottest read in the system.

---

# 3. NOTIFICATION_LOG — "what have I sent?"

## The problem it solves

Two things at once:

```
1. The in-app inbox — the bell icon with a red dot.
   Alice taps it and sees her history.

2. The frequency cap — "has Alice already had 8 today?"
   You can't answer that without a record.
```

## What's stored

```
NOTIFICATION_LOG    partition key = (user_id, day_bucket)
                    clustered by created_at DESC

┌──────────┬────────────┬──────────────┬──────────────┬─────────┐
│ user_id  │ created_at │ type         │ entity_id    │ read_at │
├──────────┼────────────┼──────────────┼──────────────┼─────────┤
│ alice    │ 14:32      │ post_comment │ post_1001    │ null    │ ← unread
│ alice    │ 12:15      │ post_liked   │ post_1001    │ 12:20   │
│ alice    │ 09:44      │ new_follower │ user_bob     │ 09:45   │
│ alice    │ 08:10      │ post_liked   │ post_0987    │ null    │
└──────────┴────────────┴──────────────┴──────────────┴─────────┘
```

Plus what was actually delivered, so you can debug:

```
  channels_sent: ["push", "in_app"]
  push_delivered_at: 14:32:04
```

## Why `day_bucket` is in the partition key

Without it, a user's partition grows forever:

```
alice, 3 notifications/day × 5 years = ~5,500 rows
  → one big partition, growing without bound
```

With a day bucket:

```
(alice, 2026-08-29)  →  today's rows
(alice, 2026-08-28)  →  yesterday's
```

Each partition is small and bounded. And the inbox almost always wants recent notifications, so you read one or two buckets.

It also makes expiry easy — drop old buckets wholesale rather than deleting rows one by one.

---

# 4. AGGREGATION_STATE — "what am I currently collecting?"

## The problem it solves

This is the batching window. Five people like Alice's post; you want to send **one** notification saying "Bob, Carol and 3 others liked your post" instead of five separate buzzes.

To do that, you need somewhere to hold the likes while you wait.

## What's stored — in Redis, not the database

```
Key:   agg:alice:post_1001:like

Value: {
  count:      5,
  actors:     [bob, carol, dave, erin, frank],
  first_seen: 12:00,
  last_seen:  12:09
}

TTL:   10 minutes
```

## How it fills up

```
12:00  Bob likes    →  key doesn't exist
                       → CREATE, count=1
                       → SEND IMMEDIATELY (flush-on-first)
                       → window now open

12:02  Carol likes  →  key exists → count=2, add carol
12:04  Dave likes   →  count=3, add dave
12:07  Erin likes   →  count=4
12:09  Frank likes  →  count=5

12:10  window expires
       → read the key
       → "Carol, Dave and 2 others liked your post"
       → SEND
       → delete the key
```

## Why Redis instead of Cassandra

Three reasons, and they all point the same way:

```
· SHORT-LIVED       exists for minutes, then gone
· HIGH CHURN        updated on every single like
· DISPOSABLE        losing it = a missed batch, nobody dies
```

Putting this in Cassandra would mean hundreds of writes and a delete for data that was never meant to persist — and in Cassandra, deletes create tombstones that hurt reads.

Redis handles churn natively, and the TTL does cleanup for free.

**And this is why transactional notifications skip aggregation entirely.** You can afford to lose a like-batch. You cannot afford to lose a password reset.

---

# How they work together — one notification, start to finish

```
  Bob likes Alice's post 1001
              │
              ▼
  ┌────────────────────────────────────────────┐
  │  Read PREFERENCES[alice]                   │
  │    post_liked → enabled, [in_app] only     │
  │    quiet hours? 12:02 IST → no             │
  │    global mute? no                         │
  │  → PROCEED                                 │
  └────────────────┬───────────────────────────┘
                   ▼
  ┌────────────────────────────────────────────┐
  │  Check NOTIFICATION_LOG[alice, today]      │
  │    count today = 3                         │
  │    cap = 8                                 │
  │  → under cap, PROCEED                      │
  └────────────────┬───────────────────────────┘
                   ▼
  ┌────────────────────────────────────────────┐
  │  AGGREGATION_STATE                         │
  │    agg:alice:post_1001:like                │
  │      exists? YES, count 1 → 2              │
  │  → HOLD, don't send yet                    │
  └────────────────┬───────────────────────────┘
                   │
                   │  ... 8 minutes later, window closes ...
                   ▼
  ┌────────────────────────────────────────────┐
  │  Render: "Bob and 1 other liked your post" │
  └────────────────┬───────────────────────────┘
                   ▼
  ┌────────────────────────────────────────────┐
  │  DEVICE_TOKENS[alice]                      │
  │    → 2 valid iOS tokens                    │
  │    (but preferences said in_app only,      │
  │     so we skip push this time)             │
  └────────────────┬───────────────────────────┘
                   ▼
  ┌────────────────────────────────────────────┐
  │  Write NOTIFICATION_LOG[alice, today]      │
  │    → appears in her inbox, bell icon lights│
  └────────────────────────────────────────────┘
```

---

# The summary table

| Store | Question | Where | Partition by | Lifetime |
|---|---|---|---|---|
| **DEVICE_TOKENS** | where do I send? | Cassandra | `user_id` | until invalid |
| **PREFERENCES** | should I send? | Cassandra | `user_id` | permanent |
| **NOTIFICATION_LOG** | what did I send? | Cassandra | `(user_id, day)` | ~90 days |
| **AGGREGATION_STATE** | what am I collecting? | Redis | key = user+entity+type | minutes |

## Two patterns worth noticing

**Everything durable is partitioned by `user_id`.** Because every question is about one user — "Alice's devices," "Alice's preferences," "Alice's inbox." One user, one partition, one lookup. Nobody contends with anybody.

That's the same reasoning as `likes_by_user` in the like-count problem. When your access pattern is always scoped to one person, partition by that person and the hot-key problem never arises.

**The one thing in Redis is the one thing you can afford to lose.** Short-lived, high-churn, disposable — three properties that jointly say "cache." And because it's lossy, anything that *can't* be lost (password resets, payment receipts) bypasses it completely.
