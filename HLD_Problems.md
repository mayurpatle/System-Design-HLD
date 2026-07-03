# System Design Problems → 12-Week Curriculum Mapping

This document maps the ~115 system design problems from the question bank to specific weeks of the 90-day curriculum. Use it to know **which problems you should be able to solve after each week** and which are **stretch goals** as you progress.

---

## How to Read This Mapping

- **Core problems**: After completing this week, you should be able to sketch this in 30–45 minutes using primarily that week's concepts.
- **Stretch problems**: You can *attempt* this with the week's skills plus earlier weeks, but full depth requires later weeks.
- **Returns deeper**: Problems that appear in multiple weeks because at Staff level, the same problem is asked at different depths.
- **Difficulty tags**: 🟢 easy, 🟡 medium, 🔴 hard (from the original bank).

A problem like "Uber Dispatch" appears in Week 1 (estimate it), Week 2 (where do locations live?), Week 9 (the full system), and Week 11 (drilled in mocks). That's the progression.

---

# Phase 1 — Foundations & Primitives (Weeks 1–4)

## Week 1 — Napkin Math & the Estimation Reflex

**You aren't designing yet — you're estimating.** Every problem in the bank is fair game for estimation practice this week. The point isn't a complete design; it's deriving DAU → QPS → storage → bandwidth in <5 minutes with stated assumptions.

### Core Drills (Estimate Cold)

These are the "first 5 you estimate on Day 6" per the curriculum:

- 🟢 **Design Instagram** (Photo Sharing + Social Features) — `Social`
- 🟡 **Design a Real-Time Chat Application (WhatsApp / Slack)** — `Messaging`
- 🟡 **Design a Ride-Hailing Service (Uber)** — `Geo Location`
- 🔴 **Design an Email Service (like Gmail)** — `Messaging`
- 🟡 **Design a Video Streaming Platform (YouTube / Netflix)** — `Media Streaming`

### Additional Estimation Targets (5 more for Day 6 drill)

- 🔴 **Design TikTok (Short Video Platform)** — `Social`
- 🟢 **Design a Music Streaming Service (Spotify)** — `Media Streaming`
- 🟡 **Design a Food Delivery Platform (DoorDash / Zomato)** — `Commerce Marketplace`
- 🟡 **Design an E-Commerce Platform (Amazon / Flipkart)** — `Commerce Marketplace`
- 🟢 **Design Google Typeahead / Autocomplete** — `Search`

**Note**: every problem in the bank should be estimable by end of Week 1. The five above are the canonical drills.

---

## Week 2 — Storage Primitives

**Pick the right storage engine and defend it.** Problems where storage choice is the central question.

### Core Problems

- 🟡 **Design a Key-Value Store** — `Storage` — pure Dynamo-paper depth
- 🟢 **Design Pastebin** — `Web Services` — object store + metadata DB + signed URLs
- 🔴 **Design a Time-Series Database** — `Storage` — Gorilla compression, retention, downsampling intuition
- 🔴 **Design a Blob Storage System (like S3)** — `Storage` — object store at scale, sharding by prefix
- 🔴 **Design a Relational Database (PostgreSQL)** — `Storage` — MVCC, WAL, B+ tree mastery
- 🔴 **Design a Social Graph Store** — `Storage` — graph DB vs Postgres recursive CTE trade-off
- 🟡 **Design a Distributed Cache (Redis / Memcached)** — `Infrastructure` — though deeper in Week 3

### Stretch (requires Week 5 too)

- 🔴 **Design a Distributed Banking Ledger System** — `Fintech` — append-only ledger + storage + consistency

---

## Week 3 — Caching, CDNs & Read-Heavy Architectures

**Multi-tier caching, hit-ratio math, stampede protection, invalidation contracts.**

### Core Problems

- 🟢 **Design a URL Shortener (TinyURL / Bit.ly)** — `Web Services` — read-heavy, cache 99% hit ratio math
- 🟡 **Design a Content Delivery Network (CDN)** — `Infrastructure` — the canonical CDN design
- 🟡 **Design a Distributed Cache (Redis / Memcached)** — `Infrastructure` — eviction policies, sharding, hot keys
- 🟡 **Design Top K Rankings System (App Store / Amazon Bestsellers)** — `Data Analytics` — pre-aggregation + caching
- 🟡 **Design a Trending Topics System** — `Data Analytics` — sliding window + cache
- 🟡 **Design Top K Most Shared Articles** — `Data Analytics`
- 🔴 **Design Like Count for High Profile Users** — `Data Analytics` — hot key problem at its worst
- 🟡 **Design a Leaderboard System** — `Data Analytics` — Redis sorted sets + caching tiers

### Stretch (needs Week 4 fanout)

- 🟡 **Design a News Feed System (Facebook / Instagram)** — push vs pull, celebrity problem
- 🟢 **Design a Timeline and Tweet Service (Twitter)** — fanout strategy

---

## Week 4 — Messaging, Queues & Async Decomposition

**Outbox pattern, idempotency keys, DLQs, the dual-writes bug.** Core "event-driven" thinking lands here.

### Core Problems

- 🟢 **Design a Notification System (Push, Email, SMS)** — `Infrastructure` — the canonical async pipeline
- 🟡 **Design a Distributed Message Broker (Kafka-style)** — `Infrastructure` — covered deeper in Week 6
- 🟡 **Design a Distributed Worker Queue (RabbitMQ / SQS)** — `Infrastructure`
- 🟡 **Design a Distributed Stream Processing System (Apache Flink)** — `Data Analytics`
- 🟡 **Design a Distributed Job Scheduler (Quartz / Airflow)** — `Infrastructure`
- 🟡 **Design an Event Sourcing System** — `Infrastructure`
- 🔴 **Design a Change Data Capture (CDC) Pipeline** — `Data Analytics` — Debezium + outbox at scale
- 🔴 **Design a Workflow Orchestration Engine (like Temporal/Cadence)** — `Infrastructure` — sagas in production

### Stretch (now you have the outbox pattern)

- 🟡 **Design a Ticketing System (BookMyShow / TicketMaster)** — seat locking + payment saga
- 🟡 **Design a Shopping Cart System** — event-sourced
- 🟡 **Design an Order Management System** — saga + outbox

---

# Phase 2 — Distributed Systems Core (Weeks 5–8)

## Week 5 — Consistency, Consensus & the CAP Reality

**Linearizability vs causal vs eventual. Raft, Paxos, CRDTs, 2PC vs Saga.**

### Core Problems

- 🔴 **Design a Distributed Consensus System (Raft / Paxos)** — `Infrastructure` — implement the protocol
- 🔴 **Design a Distributed Coordination Service (ZooKeeper)** — `Infrastructure` — consensus in practice
- 🟡 **Design a Distributed Lock Manager** — `Infrastructure` — Redlock vs Chubby vs etcd
- 🟡 **Design a Service Discovery System** — `Infrastructure` — consistency vs availability trade-off

### Returns Deeper

- 🔴 **Design Google Docs (Real-Time Collaborative Editing)** — `Realtime` — CRDT vs OT (full depth in Week 9)
- 🔴 **Design a Distributed Banking Ledger System** — `Fintech` — consistency requirements drive the design

---

## Week 6 — Deep Dive: Kafka Internals

**Your expertise week.** You're not designing new systems — you're going three levels below the box-diagram on the systems you already touch.

### Core (return to with depth)

- 🟡 **Design a Distributed Message Broker (Kafka-style)** — now with leader epochs, ISR, transaction coordinator
- 🟡 **Design an Event Sourcing System** — Kafka-as-source-of-truth pattern
- 🔴 **Design a Change Data Capture (CDC) Pipeline** — Debezium + KRaft + idempotent consumers

### Stretch (Kafka becomes the substrate)

- 🔴 **Design a Real-Time Bidding System (Ad Tech)** — Kafka for impression logging
- 🟡 **Design a Distributed Tracing System (like Jaeger / Zipkin)** — Kafka for span ingestion

---

## Week 7 — Networking, Load Balancing & the Edge

**TCP vs QUIC, TLS, DNS, L4 vs L7 LB, service mesh, rate limiting at 1M RPS.**

### Core Problems

- 🟢 **Design an API Rate Limiter** — `Infrastructure` — the canonical design exercise
- 🟢 **Design a Load Balancer** — `Infrastructure` — L4 vs L7, consistent hashing
- 🔴 **Design an API Gateway (Kong / Envoy)** — `Infrastructure`
- 🟡 **Design a Circuit Breaker** — `Infrastructure` — Resilience4j patterns
- 🟡 **Design a Service Discovery System** — `Infrastructure` (also in Week 5)

---

## Week 8 — Search, Ranking & Specialized Storage

**Inverted indexes, BM25, HNSW vector search, time-series, graph.** Your AutoOps pgvector experience anchors this week.

### Core Problems

- 🟡 **Design a Search Engine (Google)** — `Search` — inverted index + ranking + crawling
- 🟢 **Design Google Typeahead / Autocomplete** — `Search` — trie + caching + ranking
- 🟡 **Design a Web Crawler (Googlebot)** — `Data Analytics`
- 🟡 **Design Quora (Q&A Platform)** — `Search` — search + ranking + Q-A scoring
- 🔴 **Design a Log Aggregation and Search System (like Splunk / ELK)** — `Observability` — Lucene at scale
- 🔴 **Design a Search Ranking System (Learning to Rank)** — `Machine Learning` — multi-stage retrieval
- 🔴 **Design an ML Feature Store** — `Machine Learning` — online + offline + freshness

### Stretch (your secondary depth)

- 🔴 **Design a Document Q&A Platform (RAG System)** — `Ai Applied Systems` — *this is AutoOps territory; lean in*
- 🔴 **Design an LLM Chat Application (ChatGPT)** — `Ai Applied Systems` — RAG + serving + safety
- 🔴 **Design an AI Coding Assistant (Cursor / Claude Code)** — `Ai Applied Systems`

### Returns Deeper

- 🔴 **Design a Recommendation System (Netflix / TikTok Style)** — `Machine Learning` — candidate gen + rerank
- 🔴 **Design a Video Recommendation Engine** — `Machine Learning`

---

# Phase 3 — Staff-Level Sparring (Weeks 9–10)

## Week 9 — The Hard Problems I: Real-Time & Geo-Distributed

**Uber dispatch, Google Docs, Zoom, live streaming.** The canonical "hard real-time" interview problems.

### Core Problems

- 🟡 **Design a Ride-Hailing Service (Uber)** — `Geo Location` — **the canonical Week 9 problem**
- 🔴 **Design Google Docs (Real-Time Collaborative Editing)** — `Realtime` — OT vs CRDT
- 🔴 **Design a Video Conferencing System (like Zoom)** — `Realtime` — SFU vs MCU
- 🔴 **Design a Live Streaming Platform like Twitch** — `Media Streaming` — HLS, multi-CDN
- 🟡 **Design a Real-Time Chat Application (WhatsApp / Slack)** — `Messaging` — fanout + presence
- 🟡 **Design a Food Delivery Platform (DoorDash / Zomato)** — `Commerce` — Uber-shaped
- 🟡 **Design a Proximity Server (Yelp / Nearby Friends)** — `Geo Location` — geo-indexing
- 🟡 **Design a Geofencing Service** — `Geo Location` — H3/S2 cells
- 🟡 **Design a Real-time Vehicle Tracking System** — `Geo Location`
- 🟡 **Design Foursquare (Check-ins and Recommendations)** — `Geo Location`
- 🔴 **Design an ETA Calculation Service** — `Geo Location` — ML + road network
- 🔴 **Design a Map Rendering and Navigation System like Google Maps** — `Geo Location`
- 🔴 **Design Tinder (Matching System)** — `Geo Location` — geo + matching
- 🟡 **Design a Bike Sharing System like Citi Bike** — `Commerce` — Uber-lite

### Realtime Add-Ons

- 🟡 **Design Live Likes & Reactions** — `Realtime` — fanout at scale
- 🟡 **Design a User Presence System** — `Messaging` — heartbeats + Redis TTL
- 🔴 **Design a Live Comments System (like Facebook Live / YouTube Live)** — `Realtime`
- 🔴 **Design a Multiplayer Game Backend** — `Realtime` — tick loops + state sync
- 🟡 **Design a Shared Calendar System (like Google Calendar)** — `Realtime` — concurrent edits
- 🟡 **Design Ephemeral Stories (Instagram Stories)** — `Social` — TTL + fanout

### Streaming Returns

- 🟡 **Design a Video Streaming Platform (YouTube / Netflix)** — full depth at this scale
- 🟡 **Design a Music Streaming Service (Spotify)** — `Media Streaming`
- 🟡 **Design a Podcast Delivery Platform** — `Media Streaming`
- 🟡 **Design an Image Processing Pipeline** — `Media Streaming`
- 🟡 **Design a Thumbnail Generation Service** — `Media Streaming`
- 🔴 **Design a Video Transcoding Pipeline** — `Media Streaming`

---

## Week 10 — The Hard Problems II: Scale, Money & Ads

**Payment processing, double-entry ledgers, ad auctions, public APIs, observability at Datadog scale.** Your second genuine depth area.

### Core Problems — Payments & Ledgers

- 🟡 **Design a Payment Gateway (Handling ACID Transactions)** — `Fintech` — **the canonical Week 10 problem**
- 🔴 **Design a Distributed Banking Ledger System** — `Fintech` — double-entry from the ground up
- 🟡 **Design a Multi-Currency Payment System** — `Fintech` — FX snapshots
- 🔴 **Design a Digital Wallet System** — `Fintech` — Paytm/PhonePe-style
- 🔴 **Design a Stock Exchange Matching Engine** — `Fintech` — order book + sequencer + replay
- 🔴 **Design a Cryptocurrency Exchange** — `Fintech` — matching + custody + ledger
- 🔴 **Design a Fraud Detection System** — `Machine Learning` — pre-bid + post-bid filters

### Core Problems — Ads

- 🔴 **Design a Real-Time Bidding System (Ad Tech)** — `Data Analytics` — 100ms SLA
- 🔴 **Design an Ad Click Prediction System** — `Machine Learning` — feature store + serving

### Core Problems — Public APIs

- 🟢 **Design an API Rate Limiter** — returns here at 1M RPS with multi-tier (also Week 7)
- 🟡 **Design a Review and Rating System** — `Commerce` — abuse-resistant
- 🟡 **Design a Coupon and Discount Engine** — `Commerce` — fraud-resistant
- 🟡 **Design a Price Comparison Engine** — `Commerce`

### Core Problems — Observability

- 🟡 **Design a Distributed Tracing System (like Jaeger / Zipkin)** — `Observability`
- 🟡 **Design a Distributed Metrics Aggregation System** — `Observability` — cardinality control
- 🟡 **Design a Real-time Dashboard and Metrics System** — `Observability`
- 🔴 **Design a User Analytics Pipeline (like Google Analytics)** — `Data Analytics`
- 🔴 **Design a Log Aggregation and Search System (like Splunk / ELK)** — `Observability` (also Week 8)
- 🟡 **Design a Feature Flag System** — `Observability` — LaunchDarkly-shape
- 🟡 **Design an On-Call Escalation System (like PagerDuty / OpsGenie)** — `Observability`

### Security

- 🔴 **Design an Authentication and Authorization System (OAuth 2.0/SSO)** — `Security` — JWT, OIDC
- 🔴 **Design a Content Moderation System** — `Security` — ML + queue

### Commerce Returns Deeper

- 🟡 **Design an E-Commerce Platform (Amazon / Flipkart)** — now with full saga + payment ledger depth
- 🟡 **Design a Shopping Cart System** — saga + idempotency
- 🟡 **Design an Order Management System** — state machine + outbox
- 🟡 **Design a Ticketing System (BookMyShow / TicketMaster)** — seat locking + payment ledger
- 🔴 **Design an Inventory Management System** — concurrent stock updates
- 🔴 **Design a Flash Sale System** — race + queueing + Redis SETNX
- 🔴 **Design a Surge Pricing System like Uber or Lyft** — separate pipeline like Week 9 surge
- 🟡 **Design a Hotel Booking System** — inventory + payment saga

---

# Phase 4 — Polish & Pressure (Weeks 11–12)

## Week 11 — Company-Specific Targeting & Behavioral

**No new content — you're drilling the right problems for your target companies.** Pick from the bank based on your top targets.

### Drill These for Indian Fintech (Razorpay, PhonePe, Cred)

- Payment Gateway, Digital Wallet, Distributed Banking Ledger, Multi-Currency Payment, Fraud Detection, Authentication, Ticketing

### Drill These for Indian Foodtech / Mobility (Swiggy, Zomato, Ola, Uber India)

- Uber Dispatch (Week 9), Surge Pricing, ETA, Geofencing, Notification System, Real-time Vehicle Tracking, Bike Sharing

### Drill These for Indian E-commerce (Flipkart, Myntra, Meesho)

- E-Commerce Platform, Shopping Cart, Order Management, Inventory, Flash Sale, Search Engine, Recommendation System, Price Comparison

### Drill These for FAANG Western Tier

- **Google**: Key-Value Store, Distributed Consensus, Search Engine, BigTable-style storage
- **Meta**: News Feed, Instagram, Timeline, Live Likes, Real-Time Chat, User Presence, Follower System
- **Amazon**: E-Commerce, Order Management, Inventory, Notification, Distributed Worker Queue
- **Uber**: Dispatch, ETA, Surge, Distributed Tracing
- **Stripe**: Payment Gateway, Banking Ledger, Fraud Detection, API Rate Limiter

### Drill These for AI/ML-Leaning Companies

- 🔴 **Design an LLM Chat Application (ChatGPT)** — `Ai Applied Systems`
- 🔴 **Design an AI Coding Assistant (Cursor / Claude Code)** — `Ai Applied Systems`
- 🔴 **Design a Document Q&A Platform (RAG System)** — `Ai Applied Systems` — AutoOps anchor
- 🔴 **Design a Recommendation System** — `Machine Learning`
- 🔴 **Design an A/B Testing and Experimentation Platform** — `Machine Learning`
- 🔴 **Design an ML Feature Store** — `Machine Learning`

---

## Week 12 — Polish, Pressure-Testing & Launch

**Onsite simulation week.** Pick fresh problems for the back-to-back mocks. Suggested rotation that exercises different muscle groups:

- **Day 78 mock**: 🟡 Design a Real-Time Chat Application (WhatsApp / Slack) — messaging + ordering + fanout
- **Day 79 mock**: 🟢 Design a Notification System — delivery guarantees + retries
- **Day 80 mock**: 🔴 Design a Recommendation System (Pinterest-style) — multi-stage ranking
- **Day 87 onsite-sim mock 1**: 🟡 Design a Ride-Hailing Service (Uber) — your strongest hard problem
- **Day 87 onsite-sim mock 2**: 🟡 Design a Payment Gateway — your strongest depth piece

---

# Cross-Cutting Problems (Touch Multiple Weeks)

Some problems are 60–90 minute "everything bagels" — they need composition across many weeks. Mark these as **integration tests** of your cumulative skill:

| Problem | Touches Weeks | Notes |
|---|---|---|
| 🟡 **Uber Dispatch** | 1, 2, 3, 4, 5, 9, 10 | Estimate + storage + cache + Kafka + locks + geo + surge |
| 🟡 **News Feed** | 1, 2, 3, 4, 8 | Estimate + storage + cache + fanout + ranking |
| 🟡 **E-Commerce** | 1, 2, 3, 4, 5, 8, 10 | Catalog + cart + checkout + search + payments |
| 🔴 **Payment Gateway** | 1, 2, 4, 5, 10 | Idempotency + ledger + saga + reconciliation |
| 🔴 **TikTok** | 1, 2, 3, 4, 8, 9 | Storage + CDN + recommendation + fanout |
| 🔴 **Google Docs** | 1, 5, 9 | CRDT/OT + presence + persistence |
| 🔴 **Search Engine (Google)** | 1, 2, 8 | Crawl + index + rank + serve |
| 🔴 **Cryptocurrency Exchange** | 1, 2, 5, 10 | Matching engine + ledger + custody |
| 🔴 **Stock Exchange Matching Engine** | 1, 2, 5, 10 | Order book + sequencer + replay |
| 🔴 **Inventory Management** | 1, 2, 4, 5 | Concurrent stock + sagas + consistency |

These should be your **final drills** in Week 12. If you can solve any of them at 4/5 across all axes in 45 minutes, you're interview-ready.

---

# Difficulty Distribution

The bank has ~115 problems. Rough breakdown by where you'll spend prep time:

| Tier | Count (approx) | When to Touch |
|---|---|---|
| 🟢 Easy (11) | Estimation drills + warm-ups | Week 1 + as openers in Weeks 3, 4, 7 |
| 🟡 Medium (56) | The bread-and-butter mocks | Weeks 2–10 distributed |
| 🔴 Hard (48) | Staff-level integration | Weeks 9–12 mocks |

**You will NOT solve all 115 in 90 days.** That's the wrong goal. The right goal: solve ~30 problems at high quality, where "high quality" means you could whiteboard the design + defend it for 45 min + go three levels deep on one component.

Suggested 30:
- 5 from Week 1 (estimation only)
- 3 each from Weeks 2–8 (= 21)
- 4 from Week 9 (real-time)
- 4 from Week 10 (money/ads/observability)
- 6 hard integration problems in Weeks 11–12 mocks

---

# What's NOT in This Mapping

A few categories sit outside the curriculum:

- **AI Applied Systems** (LLM Chat, RAG, AI Coding Assistant): These map best to Week 8 (vector search) + Week 4 (event-driven inference pipelines). Your AutoOps experience is your anchor; these should be specifically prepped if targeting AI-leaning companies (OpenAI, Anthropic, Cohere, Glean, Perplexity).
- **Online Judge System (Leetcode)**: A niche problem; touches Week 4 (queue-based code execution) + Week 7 (sandboxing/rate limiting). Skip unless asked.
- **Code Hosting Platform (GitHub)**: Touches Week 2 (storage), Week 7 (rate limiting), Week 10 (analytics). Skip unless targeting GitHub/GitLab/Atlassian specifically.
- **P2P File Transfer (BitTorrent)**: Outside mainstream interview scope. Skip.
- **Backup and Disaster Recovery**: Operational concern more than a design problem. Embed into other designs' failure-mode sections.

---

# Quick-Reference: Which Week Owns Each Category?

| Category from Bank | Primary Week |
|---|---|
| Web Services | Week 3 (URL shortener) |
| Infrastructure | Weeks 4–7 spread |
| Social | Weeks 3 (feed), 9 (realtime social) |
| Search | Week 8 |
| Media Streaming | Week 9 |
| Geo Location | Week 9 |
| Storage | Week 2 (also 6 for Kafka-as-storage) |
| Messaging | Weeks 4 (queues), 9 (chat) |
| Data Analytics | Weeks 3 (top-K), 8 (search), 10 (RTB, observability) |
| Commerce Marketplace | Week 10 (with saga from Week 4) |
| Fintech Payments | Week 10 |
| Observability Devops | Week 10 |
| Realtime Systems | Week 9 |
| Machine Learning | Weeks 8, 10 |
| Security Trust | Week 10 |
| Ai Applied Systems | Week 8 (RAG) + stretch |

---

*Last updated to reflect the 12-week curriculum (Weeks 1–12 + Phase 1–4 structure). Adjust as you progress: if a problem feels too hard in its assigned week, defer it; if too easy, mark it complete and move on.*