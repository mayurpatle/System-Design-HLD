# Company → Problem Mapping

Built from the "Asked by" tags in the 115-problem bank, inverted so you can prep per-target.

**★ = one of The 30** (see `the_30_problems.md`). Prioritize those.

---

## How To Use This

You have a loop scheduled at company X. Open their section, take the top 6–8, and make those your mock rotation for the two weeks before.

**Two honest caveats:**

1. These tags are crowd-sourced signals of what *has been asked*, not a syllabus. An interviewer can ask anything. Treat it as a probability weighting, not a guarantee.
2. **Indian companies are not in the source tags.** Section 4 is my inference from what those companies actually build. Directionally right, not sourced.

---

# 1. Big Tech (source-tagged)

## Google

Deepest drilling on storage internals, algorithms, and consistency. Expect to be followed three levels down.

**Core rotation**
- ★ Search Engine — the home-turf question
- ★ Google Typeahead
- ★ Key-Value Store (Bigtable/Spanner lineage)
- ★ Google Docs — OT/CRDT
- ★ URL Shortener
- ★ Distributed Consensus (Raft/Paxos)
- ★ Blob Storage
- ★ Distributed Tracing

**Also tagged**
Web Crawler · Map Rendering & Navigation · Email Service (Gmail) · Shared Calendar · Relational Database (PostgreSQL) · Distributed Coordination (ZooKeeper) · Search Ranking (Learning to Rank) · Ad Click Prediction · Real-Time Bidding · Recommendation System · Video Recommendation Engine · Content Moderation · User Analytics Pipeline · Price Comparison · Review & Rating · Load Balancer · Circuit Breaker · Distributed Job Scheduler · Auth & Authorization (OAuth2/SSO) · Proximity Server · Video Transcoding · Live Streaming · Podcast Delivery · Video Conferencing · Digital Wallet · Threaded Forum (Reddit) · Quora · Pastebin · Distributed Lock Manager · Top K Rankings · Online Judge · LLM Chat Application

---

## Meta

Fanout, feed ranking, read-path optimization. The celebrity/high-follower problem appears constantly. Expect product-sense follow-ups ("how would you A/B test this?").

**Core rotation**
- ★ News Feed — the canonical Meta question
- ★ Like Count for High-Profile Users — hot key at its worst
- ★ Real-Time Chat
- ★ TikTok / short video
- ★ Notification System
- ★ URL Shortener
- ★ Key-Value Store

**Also tagged**
Instagram · Timeline & Tweet Service · Follower/Following System · Mentions & Tagging · Ephemeral Stories · Live Likes & Reactions · Live Comments System · Social Graph Store · Trending Topics · Top K Most Shared · Threaded Forum · Distributed Cache · Feature Flag System · A/B Testing Platform · Content Moderation · Ad Click Prediction · Real-Time Bidding · Live Streaming Platform

---

## Amazon

Operational excellence weighted heavily. Leadership Principles woven into the technical round. Have deployment, rollback, and on-call answers ready.

**Core rotation**
- ★ E-Commerce Platform
- ★ Notification System
- ★ Key-Value Store (Dynamo — read the paper)
- ★ Blob Storage (S3)
- ★ URL Shortener
- ★ API Rate Limiter
- ★ Ticketing System

**Also tagged**
Shopping Cart · Order Management · Inventory Management · Flash Sale System · Coupon & Discount Engine · Review & Rating · Price Comparison · Distributed Worker Queue · Distributed Lock Manager · Load Balancer · CDN · Backup & Disaster Recovery · Search Engine · Google Typeahead · Search Ranking · Top K Rankings · Video Streaming · Music Streaming · Live Streaming (Twitch) · Pastebin · Relational Database · Hotel Booking · Service Discovery

---

## Microsoft

Broad, less predictable than Google or Meta. Enterprise and collaboration flavor.

**Core rotation**
- ★ Google Docs — Office collaboration lineage
- ★ Real-Time Chat (Teams)
- ★ Search Engine
- ★ Blob Storage
- ★ API Rate Limiter

**Also tagged**
Shared Calendar · Email Service · User Presence · Video Conferencing · Unique ID Generator · Load Balancer · Circuit Breaker · Distributed Worker Queue · On-Call Escalation · Auth & Authorization · Relational Database · Search Ranking · Web Crawler · Leaderboard System · Backup & Disaster Recovery · Google Typeahead

---

## Netflix

Media pipeline, CDN, resilience patterns, experimentation. They wrote the book on several of these.

**Core rotation**
- ★ Video Streaming Platform — the home-turf question
- ★ Distributed Tracing
- ★ Recommendation System
- ★ CDC Pipeline

**Also tagged**
Video Transcoding Pipeline · Video Recommendation Engine · Thumbnail Generation · CDN · Circuit Breaker · Service Discovery · Feature Flag System · A/B Testing Platform · Distributed Metrics Aggregation · Distributed Job Scheduler · Distributed Stream Processing · Workflow Orchestration · API Gateway · P2P File Transfer

---

## Uber

Real-time and geo dominate. Expect the deep dive on the matching loop or the geo index.

**Core rotation**
- ★ Ride-Hailing (Uber) — obviously
- ★ Distributed Tracing
- ★ Distributed Lock Manager
- ★ CDC Pipeline

**Also tagged**
ETA Calculation Service · Surge Pricing · Geofencing Service · Real-time Vehicle Tracking · Proximity Server · Foursquare/Check-ins · Bike Sharing · Map Rendering · Food Delivery · Distributed Message Broker · Distributed Worker Queue · Distributed Stream Processing · Event Sourcing · Workflow Orchestration · ML Feature Store · Distributed Metrics Aggregation · Real-time Dashboard

---

## Stripe

**Correctness over scale.** A design that is fast and occasionally loses money is a failed design. Your Week 10 + Week 14 material is the whole interview.

**Core rotation**
- ★ Payment Gateway
- ★ Distributed Banking Ledger
- ★ API Rate Limiter
- ★ CDC Pipeline

**Also tagged**
Multi-Currency Payment System · Fraud Detection · Event Sourcing System · API Gateway · Digital Wallet

**Volunteer unprompted:** idempotency keys with request fingerprinting, the mismatched-payload 422 case, double-entry invariants, reconciliation. These are exactly what they're listening for.

---

## Apple

Sparse tagging in the bank; media and consumer services.

**Also tagged**
Music Streaming · Podcast Delivery · Notification System · Shared Calendar · Map Rendering · Top K Rankings · Leaderboard System

---

## LinkedIn / Twitter (X)

**LinkedIn** — Distributed Message Broker (Kafka origin) · News Feed · Timeline & Tweet Service · Follower/Following · Top K Most Shared · Ephemeral Stories · Social Graph Store · Distributed Stream Processing

**Twitter/X** — ★ Like Count for High-Profile Users · Timeline & Tweet Service · Trending Topics · Top K Most Shared · Follower/Following · Mentions & Tagging · Unique ID Generator (Snowflake) · Distributed Cache · Social Graph Store · Live Likes · Distributed Coordination

---

# 2. Second Tier (source-tagged)

| Company | Their questions |
|---|---|
| **Airbnb** | Hotel Booking · Foursquare/Check-ins · Distributed Job Scheduler |
| **Dropbox** | Dropbox/Drive sync · Blob Storage · Thumbnail Generation · Pastebin |
| **Snap** | Ephemeral Stories · Instagram · TikTok · Image Processing Pipeline |
| **Spotify** | Music Streaming · Podcast Delivery · P2P File Transfer |
| **Pinterest** | Image Processing · Thumbnail Generation · Quora · Instagram |
| **Slack** | Real-Time Chat · User Presence · Mentions & Tagging |
| **DoorDash** | Food Delivery · Geofencing Service |
| **Lyft** | Ride-Hailing · ETA · Surge Pricing · Vehicle Tracking · Geofencing · Bike Sharing · Proximity Server |
| **Coinbase / Binance** | Cryptocurrency Exchange · Fraud Detection · Stock Exchange Matching Engine |
| **Datadog** | Distributed Metrics Aggregation · Real-time Dashboard · Log Aggregation · Time-Series Database |
| **Atlassian** | Google Docs · On-Call Escalation · Code Hosting Platform |
| **Cloudflare** | CDN |
| **Shopify** | Shopping Cart · Order Management · Inventory Management |
| **Walmart** | E-Commerce · Shopping Cart · Order Management · Inventory · Coupon Engine |
| **PayPal / Block** | Digital Wallet · Payment Gateway · Multi-Currency · Fraud Detection · Banking Ledger · Event Sourcing |
| **Robinhood** | Stock Exchange Matching Engine · Cryptocurrency Exchange |
| **ByteDance** | TikTok · Recommendation System · Video Recommendation · Trending Topics · Live Likes · Content Moderation · Like Count |

---

# 3. AI Labs

**Anthropic / OpenAI / Cohere / Cursor**

- ★ Document Q&A Platform (RAG) — **your AutoOps anchor**
- LLM Chat Application (ChatGPT)
- AI Coding Assistant (Cursor / Claude Code)
- ★ Search Ranking / ML Feature Store
- ★ API Rate Limiter · API Gateway (serving infra)

Expect serving infrastructure, evaluation rigor, and cost-per-request thinking over social-scale distributed systems. Classic system design and coding still dominate the loop — this material differentiates among candidates who clear those bars.

---

# 4. Indian Market — INFERRED, NOT SOURCE-TAGGED

> The bank's tags don't cover Indian companies. This section is my inference from what these companies build. Directionally useful; not evidence.

## Fintech — Razorpay, PhonePe, Cred, Paytm, Juspay

- ★ Payment Gateway
- ★ Distributed Banking Ledger
- **Digital Wallet** ← the single most likely question at PhonePe/Paytm
- Multi-Currency Payment System
- Fraud Detection System
- ★ API Rate Limiter
- ★ Ticketing System (payment saga under contention)
- Auth & Authorization (OAuth2/SSO)

**India-specific angles worth raising:** UPI's two-legged transaction flow and its reconciliation implications, RBI data-localization (your Week 13 material applies directly), and idempotency across a payment rail you don't control.

## Foodtech / Mobility — Swiggy, Zomato, Ola, Rapido, Zepto, Blinkit

- ★ Ride-Hailing (Uber)
- Food Delivery Platform
- Surge Pricing System
- ETA Calculation Service
- Geofencing Service
- Real-time Vehicle Tracking
- ★ Notification System
- Inventory Management ← critical for quick-commerce (Zepto/Blinkit dark stores)

## E-Commerce — Flipkart, Myntra, Meesho, Nykaa

- ★ E-Commerce Platform
- **Flash Sale System** ← Big Billion Days; near-certain at Flipkart
- Inventory Management
- Shopping Cart · Order Management
- ★ Search Engine · Google Typeahead
- ★ Recommendation System
- Coupon & Discount Engine
- Price Comparison Engine

## Broking / Wealth — Zerodha, Groww, Upstox, Angel One

- **Stock Exchange Matching Engine** ← the defining question here
- ★ Distributed Banking Ledger
- Real-time Dashboard · Time-Series Database (charting, tick data)
- ★ Notification System (price alerts)
- Cryptocurrency Exchange

## SaaS / Infra — Freshworks, Zoho, Postman, BrowserStack, Hasura

- ★ API Gateway · ★ API Rate Limiter
- Multi-tenancy (embed into whichever design; not a standalone problem in the bank)
- ★ Real-Time Chat (support products)
- On-Call Escalation
- Feature Flag System
- ★ Notification System

---

# 5. Full Reference — Problem → Companies

Compact index of all tagged problems. ★ = in The 30.

| Problem | Difficulty | Tagged companies |
|---|---|---|
| ★ URL Shortener | 🟢 | Google, Meta, Amazon |
| ★ API Rate Limiter | 🟢 | Stripe, Google, Amazon |
| Unique ID Generator (Snowflake) | 🟢 | Twitter, Meta, Microsoft |
| ★ Notification System | 🟢 | Apple, Meta, Amazon |
| Timeline & Tweet Service | 🟢 | Twitter, Meta, LinkedIn |
| Instagram | 🟢 | Meta, Pinterest, Snap |
| ★ Google Typeahead | 🟢 | Google, Microsoft, Amazon |
| Music Streaming (Spotify) | 🟢 | Spotify, Apple, Amazon |
| Proximity Server (Yelp) | 🟢 | Uber, Lyft, Google |
| Pastebin | 🟢 | Google, Dropbox, Amazon |
| Load Balancer | 🟢 | Google, AWS, Microsoft |
| ★ Key-Value Store | 🟡 | Amazon, Google, Meta |
| ★ News Feed | 🟡 | Meta, Twitter, LinkedIn |
| ★ Real-Time Chat | 🟡 | Meta, Slack, Microsoft |
| Threaded Forum (Reddit) | 🟡 | Reddit, Meta, Google |
| ★ Search Engine | 🟡 | Google, Microsoft, Amazon |
| Web Crawler | 🟡 | Google, Microsoft, Yahoo |
| Top K Rankings | 🟡 | Apple, Amazon, Google |
| ★ Video Streaming Platform | 🟡 | Netflix, Google, Amazon |
| ★ Distributed Stream Processing | 🟡 | Netflix, Uber, LinkedIn |
| CDN | 🟡 | Netflix, Cloudflare, Amazon |
| ★ Ride-Hailing (Uber) | 🟡 | Uber, Lyft, Grab |
| Food Delivery | 🟡 | DoorDash, Uber, Instacart |
| ★ E-Commerce Platform | 🟡 | Amazon, Walmart, eBay |
| ★ Ticketing System | 🟡 | Ticketmaster, Amazon, Google |
| ★ Payment Gateway | 🟡 | Stripe, PayPal, Square |
| Dropbox / Google Drive | 🟡 | Dropbox, Google, Microsoft |
| Distributed Cache | 🟡 | Twitter, Meta, RedisLabs |
| Distributed Job Scheduler | 🟡 | Google, Netflix, Airbnb |
| ★ Distributed Lock Manager | 🟡 | Uber, Google, Amazon |
| Distributed Worker Queue | 🟡 | Amazon, Microsoft, Uber |
| ★ Distributed Message Broker | 🟡 | LinkedIn, Confluent, Uber |
| ★ Distributed Tracing | 🟡 | Uber, Google, Netflix |
| Event Sourcing System | 🟡 | Stripe, Square, Uber |
| Live Likes & Reactions | 🟡 | Meta, ByteDance, Twitter |
| On-Call Escalation | 🟡 | PagerDuty, Atlassian, Microsoft |
| Distributed Metrics Aggregation | 🟡 | Datadog, Uber, Netflix |
| Real-time Dashboard | 🟡 | Datadog, Uber, Grafana |
| Trending Topics | 🟡 | Twitter, ByteDance, Meta |
| Top K Most Shared Articles | 🟡 | Twitter, Meta, LinkedIn |
| Quora | 🟡 | Quora, Pinterest, Google |
| Ephemeral Stories | 🟡 | Meta, Snap, LinkedIn |
| User Presence System | 🟡 | Slack, WhatsApp, Microsoft |
| Follower/Following System | 🟡 | Meta, Twitter, LinkedIn |
| Mentions & Tagging | 🟡 | Meta, Twitter, Slack |
| Geofencing Service | 🟡 | Uber, Lyft, DoorDash |
| Foursquare / Check-ins | 🟡 | Airbnb, Uber, Foursquare |
| Real-time Vehicle Tracking | 🟡 | Uber, Lyft, Grab |
| Bike Sharing | 🟡 | Uber, Lyft, Lime |
| Image Processing Pipeline | 🟡 | Pinterest, Snap, Instagram |
| Thumbnail Generation | 🟡 | Pinterest, Netflix, Dropbox |
| Podcast Delivery | 🟡 | Spotify, Apple, Google |
| Shopping Cart | 🟡 | Amazon, Walmart, Shopify |
| Order Management | 🟡 | Amazon, Walmart, Shopify |
| Review & Rating | 🟡 | Yelp, Amazon, Google |
| Price Comparison Engine | 🟡 | Google, Amazon, PayPal |
| Coupon & Discount Engine | 🟡 | Amazon, Walmart, PayPal |
| Multi-Currency Payment | 🟡 | Stripe, PayPal, Wise |
| Circuit Breaker | 🟡 | Netflix, Microsoft, Google |
| Service Discovery | 🟡 | HashiCorp, Netflix, AWS |
| Feature Flag System | 🟡 | LaunchDarkly, Netflix, Meta |
| Leaderboard System | 🟡 | Sony, Microsoft, Apple |
| Hotel Booking | 🟡 | Booking.com, Expedia, Airbnb |
| Backup & Disaster Recovery | 🟡 | AWS, Google, Microsoft |
| Shared Calendar | 🟡 | Google, Microsoft, Apple |
| Live Comments System | 🔴 | Meta, Google, Twitch |
| User Analytics Pipeline | 🔴 | Google, Mixpanel, Amplitude |
| Log Aggregation & Search | 🔴 | Splunk, Elastic, Datadog |
| ★ Like Count for High-Profile Users | 🔴 | Twitter, Meta, ByteDance |
| ★ TikTok | 🔴 | ByteDance, Snap, Meta |
| Tinder | 🔴 | Match Group, Tinder, Bumble |
| ★ Recommendation System | 🔴 | Netflix, ByteDance, Google |
| Social Graph Store | 🔴 | Meta, LinkedIn, Twitter |
| Map Rendering & Navigation | 🔴 | Google, Apple, Uber |
| ETA Calculation Service | 🔴 | Uber, Lyft, Google |
| Video Transcoding Pipeline | 🔴 | Netflix, Google, Twitch |
| Live Streaming Platform | 🔴 | Amazon, Google, Meta |
| Video Recommendation Engine | 🔴 | Netflix, Google, ByteDance |
| Inventory Management | 🔴 | Amazon, Walmart, Shopify |
| Flash Sale System | 🔴 | Alibaba, Amazon, Flipkart |
| Surge Pricing System | 🔴 | Uber, Lyft, Bolt |
| ★ Digital Wallet | 🔴 | PayPal, Block, Google |
| Fraud Detection System | 🔴 | Stripe, PayPal, Coinbase |
| Auth & Authorization (OAuth2/SSO) | 🔴 | Okta, Microsoft, Google |
| Stock Exchange Matching Engine | 🔴 | Nasdaq, NYSE, Robinhood |
| ★ Distributed Banking Ledger | 🔴 | Stripe, Block, PayPal |
| A/B Testing Platform | 🔴 | Netflix, Meta, Optimizely |
| Content Moderation System | 🔴 | Meta, ByteDance, Google |
| ★ Google Docs | 🔴 | Google, Microsoft, Atlassian |
| Video Conferencing (Zoom) | 🔴 | Zoom, Microsoft, Google |
| ★ Distributed Consensus | 🔴 | Google, CoreOS, HashiCorp |
| ★ Time-Series Database | 🔴 | InfluxData, Datadog, Prometheus |
| ★ Blob Storage (S3) | 🔴 | AWS, Google, Microsoft |
| ★ Real-Time Bidding (Ad Tech) | 🔴 | Google, TradeDesk, Meta |
| Multiplayer Game Backend | 🔴 | Epic Games, Roblox, Riot |
| P2P File Transfer | 🔴 | BitTorrent, Netflix, Spotify |
| Email Service (Gmail) | 🔴 | Google, Microsoft, Yahoo |
| Cryptocurrency Exchange | 🔴 | Coinbase, Binance, Robinhood |
| Ad Click Prediction | 🔴 | Google, Meta, TradeDesk |
| ML Feature Store | 🔴 | Uber, Feast, Tecton |
| Search Ranking (LTR) | 🔴 | Google, Microsoft, Amazon |
| ★ CDC Pipeline | 🔴 | Netflix, Debezium, Stripe |
| Workflow Orchestration | 🔴 | Temporal, Uber, Netflix |
| Online Judge (Leetcode) | 🔴 | LeetCode, HackerRank, Google |
| Relational Database (Postgres) | 🔴 | Google, Amazon, Microsoft |
| Distributed Coordination (ZK) | 🔴 | Google, Yahoo, Twitter |
| Code Hosting Platform | 🔴 | GitHub, GitLab, Atlassian |
| LLM Chat Application | 🔴 | OpenAI, Anthropic, Google |
| AI Coding Assistant | 🔴 | Cursor, Anthropic, Cognition |
| ★ API Gateway | 🔴 | Kong, Stripe, Netflix |
| ★ Document Q&A (RAG) | 🔴 | OpenAI, Cohere, Pinecone |

---

## The Coverage Observation

Six problems account for the majority of tags across the top ten companies:

**Key-Value Store · News Feed · URL Shortener · Notification System · Rate Limiter · Real-Time Chat**

All six are in The 30, and five are 🟢/🟡. If you solve nothing else deeply, solve those — they're the highest-frequency shapes in the entire bank, and their patterns (consistent hashing, fanout, cache tiering, async pipelines, token buckets, connection management) recur inside almost every hard problem.