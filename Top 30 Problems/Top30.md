# The 30 — Definitive Problem List

Companion to `HLD_Problems.md`. That document maps all ~115 problems to weeks. **This one is the shortlist you actually solve.**

> **Correction to the original:** the mapping doc said "~30 problems" but the breakdown beneath it summed to 40. This is the corrected 30, with the allocation adjusted so depth weeks get fewer slots and integration weeks get the ones that matter.

---

## The Rule

**Solve 30 problems at high quality. Do not attempt all 115.**

"High quality" means: you could whiteboard it in 45 minutes, defend every choice with a tradeoff, and go three levels deep on at least one component when drilled.

Thirty problems solved to that standard beats a hundred sketched. The bank's remaining 85 exist so that when an unfamiliar one appears, you recognize its shape — not so you solve them all.

---

## The Allocation

| Weeks | Slots | Why this number |
|---|---|---|
| W1 | 0 (5 estimation drills) | Not solves — estimation reps. Listed separately below. |
| W2 Storage | 3 | Broad primitive; needs range |
| W3 Caching | 3 | Broad primitive; needs range |
| W4 Messaging | 3 | Broad primitive; needs range |
| W5 Consistency | 2 | Narrow topic; concepts matter more than problem count |
| W6 Kafka internals | 2 | **Depth week** — you return to W4 problems deeper, not new ones |
| W7 Networking | 2 | Narrow topic |
| W8 Search | 3 | Broad + your secondary depth anchor |
| W9 Real-time/Geo | 4 | The canonical hard problems |
| W10 Money/Ads/Obs | 4 | Your primary depth anchor |
| W11–12 Integration | 4 | Everything-bagel problems for mocks |
| **Total** | **30** | |

---

## Week 1 — Estimation Drills (not solves)

Do these as 5-minute estimation reps only. DAU → QPS → storage → bandwidth, assumptions stated aloud. No architecture.

- [ ] 🟢 Instagram
- [ ] 🟡 Real-Time Chat (WhatsApp / Slack)
- [ ] 🟡 Ride-Hailing (Uber)
- [ ] 🔴 Email Service (Gmail)
- [ ] 🟡 Video Streaming (YouTube / Netflix)

*Four of these five reappear as full solves later. That's intentional — you estimate them in Week 1 and design them in Weeks 9–12.*

---

## The 30

### Week 2 — Storage Primitives (3)

| # | Problem | Why this one |
|---|---|---|
| 1 | 🟡 **Key-Value Store** | The Dynamo paper made concrete. Consistent hashing, quorums, vector clocks. Everything else in storage references it. |
| 2 | 🔴 **Blob Storage (S3)** | Object storage at scale, prefix sharding, durability math. Appears as a component in half the bank. |
| 3 | 🔴 **Time-Series Database** | Forces columnar thinking, compression, retention, downsampling. Distinct engine reasoning from the other two. |

**Skipped and why:** Pastebin (too small for a full solve), Relational Database (interesting but rarely asked outside Google infra roles), Social Graph Store (returns inside News Feed).

---

### Week 3 — Caching & Read-Heavy (3)

| # | Problem | Why this one |
|---|---|---|
| 4 | 🟢 **URL Shortener** | The canonical read-heavy problem. Hit-ratio math, ID generation, cache tiering. Fastest to solve, highest reuse. |
| 5 | 🟡 **News Feed** | Push vs pull fanout, the celebrity problem. The single most-referenced pattern in social system design. |
| 6 | 🔴 **Like Count for High-Profile Users** | The sharpest hot-key problem in the bank. Sharded counters, write coalescing, approximate counts. |

**Skipped and why:** Trending Topics and Top-K Rankings overlap ~80% with Like Count on mechanism. Do one, recognize the others.

---

### Week 4 — Messaging & Async (3)

| # | Problem | Why this one |
|---|---|---|
| 7 | 🟢 **Notification System** | The canonical async pipeline. Fanout, delivery guarantees, retries, DLQ, user preferences. |
| 8 | 🟡 **Distributed Message Broker (Kafka-style)** | You return to this in W6 at internals depth. Solve it structurally here first. |
| 9 | 🟡 **Ticketing System (BookMyShow)** | Seat locking + payment saga + outbox. Where async meets money. Very common in Indian interviews. |

**Skipped and why:** Worker Queue and Job Scheduler are substantially covered by Notification System's mechanics.

---

### Week 5 — Consistency & Consensus (2)

| # | Problem | Why this one |
|---|---|---|
| 10 | 🟡 **Distributed Lock Manager** | Redlock vs etcd vs Chubby. The practical face of consensus, and asked far more often than Raft itself. |
| 11 | 🔴 **Distributed Consensus (Raft / Paxos)** | The theory problem. Rare as a full interview question but the foundation under everything else. |

**Why only 2:** this week's value is conceptual — CAP, PACELC, consistency models, quorums. Those show up *inside* other problems rather than as standalone questions. ZooKeeper overlaps Raft by ~70%; skip it.

---

### Week 6 — Kafka Internals (2)

| # | Problem | Why this one |
|---|---|---|
| 12 | 🔴 **Change Data Capture (CDC) Pipeline** | Debezium + outbox + idempotent consumers. Your Kafka depth anchor applied. |
| 13 | 🟡 **Distributed Stream Processing (Flink)** | Windowing, watermarks, exactly-once state. The processing layer above Kafka. |

**Why only 2:** this is a **depth week**. You revisit the Week 4 broker problem with segments, ISR, leader epochs, and the transaction coordinator — that's the work, not new problems. The deliverable is a recorded talk, not more solves.

---

### Week 7 — Networking & Load Balancing (2)

| # | Problem | Why this one |
|---|---|---|
| 14 | 🟢 **API Rate Limiter** | The most-asked "easy" problem in the industry. Token bucket, sliding window, distributed counting, multi-tier. |
| 15 | 🔴 **API Gateway (Kong / Envoy)** | Subsumes load balancer, service discovery, and circuit breaker into one design. Better use of a slot than solving each. |

**Skipped and why:** Load Balancer, Circuit Breaker, and Service Discovery are all components of the API Gateway answer.

---

### Week 8 — Search & Specialized Storage (3)

| # | Problem | Why this one |
|---|---|---|
| 16 | 🟡 **Search Engine (Google)** | Crawl → index → rank → serve. Inverted indexes, BM25, sharding. Subsumes Web Crawler. |
| 17 | 🟢 **Google Typeahead** | Trie + caching + ranking under a 100ms budget. Small, sharp, frequently asked. |
| 18 | 🔴 **Document Q&A Platform (RAG)** | **Your AutoOps anchor.** Chunking, hybrid retrieval, reranking, evaluation. Credible because you built it. |

**Skipped and why:** Log Aggregation (ELK) overlaps Search Engine on mechanism; ML Feature Store returns inside the Week 10 ad problem.

---

### Week 9 — Real-Time & Geo (4)

| # | Problem | Why this one |
|---|---|---|
| 19 | 🟡 **Ride-Hailing (Uber)** | The most-asked hard problem in the industry. Geo-indexing, batched dispatch, surge. Non-negotiable. |
| 20 | 🟡 **Real-Time Chat (WhatsApp / Slack)** | Connection management, ordering, presence, delivery receipts. Subsumes User Presence. |
| 21 | 🔴 **Google Docs** | OT vs CRDT. The algorithmic-correctness problem, distinct in shape from everything else in the bank. |
| 22 | 🟡 **Video Streaming (Netflix / YouTube)** | CDN strategy, transcoding ladder, adaptive bitrate. The delivery-at-scale problem. |

**Skipped and why:** Food Delivery is Uber-shaped, Geofencing and Vehicle Tracking are Uber sub-components, Zoom is a good stretch but 4 is the budget.

---

### Week 10 — Money, Ads & Observability (4)

| # | Problem | Why this one |
|---|---|---|
| 23 | 🟡 **Payment Gateway** | **Your primary depth anchor.** Idempotency keys, saga, reconciliation, webhooks, PCI vault. |
| 24 | 🔴 **Distributed Banking Ledger** | Double-entry from the ground up. The correctness-over-scale problem. Stripe-shaped. |
| 25 | 🔴 **Real-Time Bidding (Ad Tech)** | 100ms auction SLA, feature store, budget pacing. Subsumes Ad Click Prediction. |
| 26 | 🟡 **Distributed Tracing System** | Span ingestion, sampling, cardinality control. Subsumes Metrics Aggregation and Dashboards. |

**Skipped and why:** Digital Wallet and Multi-Currency return in integration; Fraud Detection is an ML problem layered on the payment design.

---

### Weeks 11–12 — Integration Problems (4)

These are the "everything bagel" problems that touch 5–7 weeks each. Save them for mocks — they're your final exam, not a study exercise.

| # | Problem | Weeks it touches |
|---|---|---|
| 27 | 🟡 **E-Commerce Platform (Amazon / Flipkart)** | 1, 2, 3, 4, 5, 8, 10 — catalog, cart, checkout, search, payments, inventory |
| 28 | 🔴 **TikTok** | 1, 2, 3, 4, 8, 9, 15 — storage, CDN, recsys, fanout, transcoding |
| 29 | 🔴 **Digital Wallet (PhonePe / Paytm)** | 1, 2, 4, 5, 10 — ledger, KYC, P2P transfer, reconciliation. **Indian fintech targets.** |
| 30 | 🔴 **Recommendation System** | 8, 10, 15 — candidate generation, two-tower retrieval, feature store, ranking |

---

## The Next 10 (if you finish early)

Do these only after all 30 are solid. Ordered by return:

31. 🔴 **Flash Sale System** — race conditions at extreme scale; very Flipkart/Big Billion Days
32. 🔴 **Stock Exchange Matching Engine** — order book, sequencer, replay
33. 🔴 **Video Conferencing (Zoom)** — SFU/MCU, WebRTC, TURN
34. 🟡 **Dropbox / Google Drive** — content-defined chunking, delta sync (Week 15)
35. 🔴 **Authentication & Authorization (OAuth2 / SSO)** — JWT, OIDC, token lifecycle
36. 🔴 **Inventory Management** — concurrent stock, oversell prevention
37. 🟡 **Distributed Job Scheduler** — leader election, exactly-once execution
38. 🔴 **LLM Chat Application (ChatGPT)** — serving, streaming, cost (AI-lab targets)
39. 🟡 **Hotel Booking** — inventory + payment saga, different shape from Ticketing
40. 🔴 **Content Moderation System** — ML pipeline + human review queue

---

## Coverage Check — Does 30 Cover Week 15?

Week 15 added four gap topics after this mapping was written. Coverage in the 30:

| Week 15 topic | Covered by |
|---|---|
| Recommendation systems | #30 (primary), #28 TikTok |
| Persistent connections | #20 WhatsApp (primary), #21 Google Docs |
| File sync & delta transfer | **Not covered** — see #34 Dropbox in the next-10 |
| Media pipelines | #22 Video Streaming (primary), #28 TikTok |

**If you have time for one from the next-10, make it #34 Dropbox.** It's the only Week 15 topic with no representation in the 30, and content-defined chunking has no substitute elsewhere in the list.

---

## Solving Order

Don't solve in week order. Solve in this order, which builds confidence and reuse:

**Phase A — build the reflex (do these first, in order)**
4 (URL Shortener) → 14 (Rate Limiter) → 7 (Notification) → 17 (Typeahead)

*Four fast wins. Each is solvable in 30 minutes and establishes the template rhythm.*

**Phase B — the primitives**
1, 2, 3 → 5, 6 → 8, 9 → 10, 11 → 12, 13 → 15

**Phase C — the anchors (go deep, these are your steering targets)**
18 (RAG), 23 (Payment Gateway), 24 (Ledger), 12 (CDC)

**Phase D — the canonical hards**
19 (Uber), 20 (WhatsApp), 21 (Google Docs), 22 (Netflix), 16, 25, 26

**Phase E — integration, under mock conditions only**
27, 28, 29, 30

---

## Tracking

For each problem, you're done when you can tick all five:

- [ ] Solved in ≤45 min, spoken aloud, recorded
- [ ] Stated at least 3 tradeoffs with the alternative and its cost
- [ ] Went 3 levels deep on one component with numbers
- [ ] Named 3+ failure modes with mitigations
- [ ] Closed with an honest critique of my own design

A problem you sketched but can't do this for is not solved. Ten problems at this bar beat thirty at a lower one.