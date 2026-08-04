# High Performance Browser Networking — Building Blocks of TCP & HTTP/2 — Detailed Summary

**Source:** *High Performance Browser Networking* by **Ilya Grigorik** (O'Reilly, 2013), free in full at hpbn.co. Covers **Chapter 2: Building Blocks of TCP** and the **HTTP/2 chapter** (numbered Ch. 12 in the online edition; Ch. 9 in some printings/tables of contents).

**Why these two together:** Ch. 2 explains why latency — not bandwidth — governs real-world transfer performance. The HTTP/2 chapter is the application-layer response to those exact constraints. Read as a pair, they form a single argument: *connections are expensive, so use fewer of them and multiplex.*

---



# PART I — Building Blocks of TCP



## 1. What TCP is for

IP provides host-to-host routing and addressing; **TCP provides the abstraction of a reliable network running over an unreliable channel** — hiding retransmission of lost data, in-order delivery, congestion control and avoidance, and data integrity. Proposed by Vint Cerf and Bob Kahn (1974), split in 1981 into **RFC 791 (IP)** and **RFC 793 (TCP)**; the core operation hasn't changed significantly since.

**The framing sentence of the whole chapter:** TCP is **optimized for accurate delivery, rather than timely delivery** — and that tradeoff is the source of nearly every performance problem that follows. HTTP doesn't mandate TCP, but in practice all HTTP traffic uses it.

## 2. The Three-Way Handshake

Before any application data flows, both sides must agree on starting sequence numbers (**picked randomly for security reasons**) and other connection variables:

- **SYN** — client picks random sequence number `x`, sends SYN.
- **SYN ACK** — server increments `x` by one, picks its own random `y`, responds.
- **ACK** — client increments both `x` and `y` by one, completes the handshake.

The client may send data immediately after its ACK; the server must wait for that ACK first.

**The performance implication:** every new connection costs **a full roundtrip of latency before any application data moves**. New York→London over fiber: minimum **56 ms** (28 ms each way). **Bandwidth plays no role** — the cost is pure propagation latency. This is the fundamental reason **connection reuse is a critical optimization** for anything running over TCP.  

TCP Fast Open (TFO) is a mechanism that aims to eliminate the latency penalty imposed on new TCP connections by allowing data transfer within the SYN packet.

## 3. Congestion collapse — the historical context

In 1984 **John Nagle (RFC 896)** documented **"congestion collapse"**: when roundtrip time exceeds the retransmission interval, hosts inject more and more duplicate datagrams; switching-node buffers fill; packets drop; RTT hits maximum; every packet gets sent several times before one arrives. Nagle's chilling observation: **this condition is stable** — once saturated, the network keeps operating in a degraded state.

Congestion collapse  means a state where network thoughput is dropped to tiny fraction of its normal due to excess unneccasary transmission of data packets , which causes buffer overflow , routers dropping  packets and tcp missing acks.

It wasn't a problem for early ARPANET **(uniform bandwidth, spare backbone capacity), but by **1986**, with 5,000+ heterogeneous nodes, congestion collapse incidents swept the network — **capacity dropped by a factor of 1,000** in some cases and the network became unusable. The response: **flow control, congestion control, and congestion avoidance**.

## 4. Flow Control — protecting the *receiver*

Each side advertises a **receive window (**`rwnd`**)** — the buffer space it has available — initialized from system defaults and **carried in every ACK**, so both sides continuously adapt. If a side can't keep up it advertises a smaller window; `rwnd = 0` **means stop sending** until the application drains the buffer.

Typical web pages stream server→client, so the **client's window is usually the bottleneck**; for uploads (images, video) the **server's** window becomes limiting.

## 5. Slow-Start — protecting the *network*

Flow control stops the sender overwhelming the **receiver**, but nothing stopped either side overwhelming the **network** — and neither end knows the available bandwidth at connection start. **Van Jacobson and Michael J. Karels (1988)** documented **slow-start, congestion avoidance, fast retransmit, and fast recovery**; the book notes it's widely held these updates **prevented an Internet meltdown** in the late '80s/early '90s.

**Congestion window (**`cwnd`**)** — a **sender-side, private, never-advertised** limit on unacknowledged data in flight. The governing rule:

> **Max data in flight = min(rwnd, cwnd)**

`cwnd` starts conservatively and grows as ACKs arrive: **1 segment originally → 4 (RFC 2581, 1999) → 10 segments (RFC 6928, 2013)**. For each ACK, `cwnd` grows by one segment — so **each roundtrip doubles the window**: exponential growth, converging on the path's real capacity.

**Why every web developer must care:** every TCP connection goes through slow-start, so **you cannot use the full link capacity immediately**, regardless of available bandwidth. Time to reach a throughput target is a function of **RTT and initial cwnd**.

**Worked example** (64 KB windows, initcwnd 10, RTT 56 ms): reaching the 64 KB receive-window limit requires growing cwnd to **45 segments = 168 ms = three roundtrips**. With the older 4-segment initcwnd, add another 56 ms roundtrip.

**The headline comparison** — 64 KB file, New York→London, 56 ms RTT, 5 Mbps both ends, 40 ms server processing:


|                                             | Time       |
| ------------------------------------------- | ---------- |
| **New connection** (handshake + slow-start) | **264 ms** |
| **Reused connection** (cwnd already grown)  | **96 ms**  |


**A 275% improvement** — and *the 5 Mbps of bandwidth was irrelevant in both cases*. **Latency and congestion window size were the limiting factors.** The gap only widens as RTT grows.

Crucially: slow-start barely matters for **large streaming downloads** (cost amortized over a long transfer) but dominates **short, bursty HTTP connections**, which frequently finish *before* the window ever reaches maximum. That is why so much web performance work is really RTT work.

## 6. Congestion Avoidance and the sawtooth

**TCP is specifically designed to use packet loss as a feedback mechanism.** It's not *if* packet loss occurs, but *when*. Slow-start doubles the window each RTT until it exceeds `rwnd`, crosses a configured threshold (`ssthresh`), or **a packet is lost** — at which point congestion avoidance takes over, assuming loss indicates congestion, resets the window, and regrows it more cautiously. Repeat forever: **that's the sawtooth pattern** in every TCP throughput trace.

Variants in the wild: TCP Tahoe/Reno (originals), Vegas, New Reno, BIC, **CUBIC (Linux default)**, **Compound TCP (Windows default)** — the flavor changes, the core performance implications don't.

## 7. Bandwidth-Delay Product (BDP)

> **BDP** = data link's capacity × its end-to-end delay = the **maximum amount of unacknowledged data that can be in flight** at any time.

If either side exceeds `min(rwnd, cwnd)`, it must **stop and wait one RTT** for ACKs — creating gaps in the data flow and capping throughput. So **the optimal window size depends on the roundtrip time.**

Two worked calculations:

- **16 KB window, 100 ms RTT → the connection cannot exceed ~1.31 Mbps**, no matter what bandwidth exists.
- To saturate a **10 Mbps link at 100 ms RTT**, you need a window of at least **122.1 KB** — well above TCP's **64 KB maximum without window scaling (RFC 1323)**.

The practical diagnosis: if a connection transmits at a fraction of known-available bandwidth, suspect a small window — a saturated peer advertising a low `rwnd`, packet loss resetting `cwnd`, or explicit traffic shaping.

## 8. TCP Head-of-Line Blocking

Every TCP packet carries a sequence number and **data must be delivered in order**. If one packet is lost, **all subsequent packets sit in the receiver's TCP buffer** until the lost one is retransmitted and arrives. The application has **no visibility** into retransmissions or queued buffers — it just experiences a mysterious delay reading from the socket. That's **TCP head-of-line (HOL) blocking**.

The tradeoff is deliberate: applications never deal with reordering or reassembly, at the cost of **unpredictable latency variation (jitter)**.

And the key insight for protocol design: **some applications don't need these guarantees at all** — if every packet is a standalone message, in-order delivery is unnecessary; if every message overrides the previous one, reliable delivery is unnecessary. TCP offers no such configuration; **everything is sequenced and delivered in order**. Latency/jitter-sensitive applications that tolerate loss or reordering are better served by **UDP**. *(This paragraph is, in retrospect, the entire motivation for QUIC/HTTP/3.)*

## 9. Optimizing for TCP

The four immutable facts:

1. The three-way handshake introduces **a full roundtrip of latency**.
2. **Slow-start is applied to every new connection.**
3. Flow and congestion control regulate throughput of all connections.
4. **Throughput is regulated by the current congestion window size.**

Therefore: **in most cases, latency — not bandwidth — is the bottleneck**, since bandwidth keeps growing while latency is bounded by the speed of light and is already within a small constant factor of its maximum.

**Server-side checklist:**

- **Upgrade your kernel** — called out as likely "the single best investment you can make," and commonly resisted.
- **initcwnd = 10** (RFC 6928).
- **Enable window scaling** (RFC 1323) for high-latency links.
- **Disable slow-start after idle** — helps long-lived connections that transfer in periodic bursts.
- **Investigate TCP Fast Open (TFO)** — lets application data ride in the initial SYN.
- Inspect sockets with `ss --options --extended --memory --processes --info`.

**Application-side principles (the three rules):**

- **"No bit is faster than one that is not sent"** — eliminate unnecessary transfers; compress the rest.
- **"We can't make the bits travel faster, but we can move the bits closer"** — geo-distribute via CDN to cut RTT.
- **Connection reuse is critical** — amortize handshakes and slow-start.

---



# PART II — HTTP/2



## 1. Goals and scope

Primary goals: **reduce latency via full request/response multiplexing**, **minimize protocol overhead via efficient header compression**, and add **request prioritization and server push** — supported by new flow control, error handling, and upgrade mechanisms.

**Critically: HTTP/2 does not change HTTP semantics at all.** Methods, status codes, URIs, and header fields are untouched. It changes **how data is framed and transported**, hiding the complexity in a new framing layer — so **existing applications work unmodified**. The version bumped to 2 (not 1.2) purely because the **binary framing layer isn't backward compatible**.

## 2. History: SPDY → HTTP/2

SPDY was a Google experiment announced mid-2009 targeting a **50% page-load-time reduction** without requiring content changes or network-infrastructure changes. Early lab results on the top 25 websites showed pages loading **up to 55% faster**. By 2012 Chrome, Firefox, and Opera supported it and large sites (Google, Twitter, Facebook) had deployed it — becoming a de facto standard.

The HTTP Working Group then adopted **SPDY as the starting point** for an official standard, with SPDY continuing as the **experimental branch** used to test proposals — because "what looks good on paper may not work in practice." Three years and a dozen-plus drafts later: **RFC 7540 (HTTP/2) and RFC 7541 (HPACK), May 2015**. Chrome deprecated SPDY and NPN (in favor of **ALPN**) in early 2016.

The book's verdict on that process: HTTP/2 is **"one of the best and most extensively tested standards right out of the gate"** — dozens of production-ready implementations existed the day it was approved. A genuinely instructive model for standards development: co-evolve a real implementation with the spec.

## 3. The Binary Framing Layer

The core of every HTTP/2 improvement: a new encoding layer inserted **between the socket interface and the HTTP API**. HTTP semantics unaffected; **all communication is split into smaller messages and frames, each binary-encoded**.

**Binary vs ASCII (the tradeoff, stated honestly):** ASCII protocols are easy to inspect but inefficient and **harder to implement correctly** — optional whitespace, varying termination sequences, and other quirks blur protocol and payload, causing parsing and security errors. Binary protocols take more effort to start with but yield **more performant, robust, and provably correct implementations**. You'll need Wireshark to debug — which you'd need for TLS anyway.

## 4. Streams, Messages, and Frames — the terminology


| Term        | Definition                                                                                                                                     |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| **Stream**  | A bidirectional flow of bytes within an established connection, carrying one or more messages; has a unique ID and optional priority info      |
| **Message** | A complete sequence of frames mapping to a logical HTTP request or response                                                                    |
| **Frame**   | The smallest unit of communication; carries a specific data type (headers, payload, …) and a header identifying **which stream it belongs to** |


**All communication happens over a single TCP connection carrying any number of bidirectional streams.** Frames from different streams are **interleaved and reassembled via the stream identifier** in each frame header. This is the foundation for everything else.

## 5. Request and Response Multiplexing — "the single most important enhancement"

HTTP/1.x delivers **one response at a time per connection** (response queuing), so parallelism requires multiple TCP connections — causing head-of-line blocking and inefficient TCP use. HTTP/2 breaks messages into independent frames, interleaves them, and reassembles them at the far end.

The book calls the ability to interleave and reassemble **"the single most important enhancement of HTTP/2,"** with a ripple effect across the stack:

- Interleave multiple **requests** in parallel without blocking on any one
- Interleave multiple **responses** in parallel without blocking on any one
- Use **a single connection** for many parallel requests and responses
- **Remove HTTP/1.x workarounds** — concatenated files, image sprites, domain sharding
- Lower page load times by eliminating latency and improving network-capacity utilization

Net effect: **application-layer head-of-line blocking is solved, and multiple connections are no longer needed.**

## 6. Stream Prioritization

Once frames interleave, **delivery order becomes a critical performance decision**. Each stream may carry:

- an integer **weight** between **1 and 256**
- an explicit **dependency** on another stream (its parent; default parent is the implicit "root stream")

Together these build a **prioritization tree** expressing how the client would prefer to receive responses. The server uses it to allocate CPU, memory, and bandwidth. Siblings split resources **in proportion to weight** — e.g., weights 12 and 4 → **¾ and ¼** of resources; a dependency is a **stronger** signal than weight (deliver D before C). Clients can **update priorities at any time**, e.g. in response to user interaction.

**The essential caveat:** dependencies and weights express a **transport preference, not a requirement**. The client cannot force a processing order — deliberately, because you don't want the server idle on a lower-priority resource when a higher-priority one is blocked.

**Why browsers need this:** the HTML builds the DOM, CSS builds the CSSOM, both can be blocked on JavaScript, and images are typically lower priority. Browsers already compute priorities (by asset type, page position, even learned from previous visits) — but **HTTP/1.x gave them no way to express it**, leaving only ~6 parallel connections per origin and client-side request queuing. HTTP/2 lets the browser **dispatch every request the moment it's discovered** and communicate its preferences.

## 7. One Connection Per Origin

With multiplexing, HTTP/2 needs only **one persistent connection per origin**.

The killer statistic, from Patrick McManus (Firefox): the fraction of connections that carry **just a single HTTP transaction** — and therefore bear all the setup overhead — is **74% under HTTP/1**, and **plummets to 25% under HTTP/2**. "Persistent connections just aren't as helpful as we all want."

Why it matters: most HTTP transfers are **short and bursty**, while **TCP is optimized for long-lived bulk transfers** (see slow-start above). Reusing one connection uses TCP more efficiently, cuts protocol overhead, and reduces memory/processing footprint across clients, intermediaries, and origins. **For HTTPS the win compounds**: fewer expensive TLS handshakes and better session reuse.

**The honest downside section** — packet loss and high-RTT links:

- HOL blocking is eliminated **at the HTTP layer but still exists at the TCP layer**.
- BDP effects still cap throughput if **window scaling is disabled**.
- **Packet loss shrinks** `cwnd`**, reducing throughput of the entire connection** — now all your streams, not one of six.

But multiple connections have their own costs: **distinct compression contexts** (worse HPACK), **worse prioritization across separate TCP streams**, **more competing flows and congestion**, and higher resource overhead. Real-world evidence settled it — from HTTP/2 Draft 2: *the negative effects of head-of-line blocking, especially with packet loss, are outweighed by the benefits of compression and prioritization.*

And the line that predicts the next decade: **"the moment you remove one performance bottleneck, you unlock the next one. In the case of HTTP/2, TCP may be it."** Hence continued TCP work (TFO, PRR, larger initcwnd) — and the note that HTTP/2 **doesn't mandate TCP**; other transports like UDP aren't out of the question. *(That's HTTP/3 / QUIC, foretold.)*

## 8. Flow Control (HTTP/2 level)

Same problem as TCP flow control, but **TCP's version isn't granular enough** once many streams share one connection, and gives applications no API to regulate individual streams. Motivating examples: a user pauses a video and the client wants to throttle delivery; a proxy with a fast downstream and slow upstream wants to match rates.

HTTP/2 supplies **building blocks, not an algorithm**:

- **Directional** — each receiver sets whatever window it wants, per stream and for the whole connection.
- **Credit-based** — receivers advertise initial windows (bytes), decremented by each `DATA` frame, replenished by `WINDOW_UPDATE`.
- **Cannot be disabled** — `SETTINGS` frames at connection start establish windows in both directions; **default 65,535 bytes**, raisable to a large maximum.
- **Hop-by-hop, not end-to-end** — intermediaries can apply their own resource-allocation policies.

Deliberately **no specified algorithm**: clients and servers implement custom strategies. The elegant example given: fetch a **preview/first scan of an image**, drop the stream window to zero to pause it, let higher-priority fetches proceed, then resume.

## 9. Server Push

The server can send **multiple responses for a single client request** — pushing resources the client hasn't asked for yet, breaking HTTP's strict request-response semantics and enabling one-to-many, server-initiated workflows.

The rationale: the server already knows which resources the document references, so why wait for the client to parse and ask? **If you've ever inlined CSS/JS via a data URI, you've done server push manually** — but HTTP/2 push is strictly better because pushed resources:

- can be **cached** by the client
- can be **reused across different pages**
- can be **multiplexed** alongside other resources
- can be **prioritized** by the server
- **can be declined** by the client

`PUSH_PROMISE` **mechanics:** every push stream is announced by a `PUSH_PROMISE` frame carrying just the promised resource's headers, and it **must arrive ahead of the response data that references it** — otherwise the client may issue its own duplicate request. The simplest correct strategy: send all `PUSH_PROMISE` frames before the parent's `DATA` frames.

The client can **decline via** `RST_STREAM` (e.g., already cached) — a major improvement over inlining, which is a **"forced push"** the client can't opt out of, cancel, or process separately. Clients control push via `SETTINGS`: cap concurrent pushed streams, set the initial flow-control window, or **disable push entirely**. Security constraint: pushed resources must obey the **same-origin policy**.

## 10. Header Compression (HPACK)

HTTP/1.x sends headers as plain text on every transfer — **500–800 bytes of overhead per transfer, kilobytes with cookies**. HPACK uses two techniques:

1. **Static Huffman coding** of transmitted header field values, shrinking individual sizes.
2. A **shared, indexed list of previously seen header fields** maintained by both sides (a **shared compression context**), so repeats are sent as **index references** instead of full key/value pairs.

The context has two tables: a **static table** defined in the spec (common header fields every connection will likely use) and a **dynamic table**, initially empty, populated from values exchanged on that connection.

Header semantics are unchanged except: **all header names are lowercase**, and the request line splits into pseudo-headers `:method`**,** `:scheme`**,** `:authority`**,** `:path`.

**Why HPACK exists (great security story):** early SPDY/HTTP/2 used **zlib with a custom dictionary**, achieving **85–88% reduction** in header bytes and, on a 375 Kbps DSL uplink, **45–1142 ms** of page-load-time improvement for request-heavy sites. Then in summer 2012 the **CRIME attack** was published against TLS and SPDY compression, enabling session hijacking. zlib was replaced by **HPACK**, designed to fix the security issue while staying simple to implement correctly and still compressing well. *(Lesson: compression over attacker-influenced data plus secrets is a leak channel.)*

## 11. Upgrading to HTTP/2

HTTP/1.x will be around **at least another decade**, so clients and servers must negotiate. Three mechanisms:

1. **TLS + ALPN** — negotiated during the TLS handshake with **no extra latency or roundtrips**. The standard doesn't require TLS, but it's the most reliable way to deploy a new protocol past existing intermediaries — and **Firefox and Chrome only enable HTTP/2 over TLS**, making TLS+ALPN a **de facto requirement** in browsers.
2. **HTTP Upgrade** (`Upgrade: h2c` plus a base64 `HTTP2-Settings` payload) — the server either responds normally in HTTP/1.1 or returns `101 Switching Protocols` and switches to binary framing. Either way, **no extra roundtrips**.
3. **Prior knowledge** — from DNS, config, or memory: just start sending HTTP/2 frames, falling back to Upgrade or TLS+ALPN on failure.



## 12. Binary framing internals

Every frame shares a **common 9-byte header**:

- **24-bit length** — up to 2²⁴ bytes (~~16 MB) per frame, but the **default max~~ `DATA` ~~payload is 2¹⁴ (~~16 KB)**, negotiable higher. **Bigger is not always better: smaller frames enable efficient multiplexing and minimize head-of-line blocking.**
- **8-bit type** — determines format and semantics
- **8-bit flags** — frame-type-specific booleans
- **1-bit reserved** (always 0)
- **31-bit stream identifier**

Because frames are **length-prefixed**, a parser can skip straight to the next frame — a large improvement over HTTP/1.x text parsing. (HTTP/2 uses **fixed-length fields exclusively**: variable-length encoding's savings wouldn't justify parser complexity — halving overhead on a 1,400-byte packet saves 4 bytes, 0.3%.)

**The ten frame types:** `DATA` (message bodies), `HEADERS` (header fields for a stream), `PRIORITY`, `RST_STREAM` (terminate a stream), `SETTINGS` (connection config), `PUSH_PROMISE`, `PING` (RTT measurement / liveness), `GOAWAY` (stop creating streams on this connection), `WINDOW_UPDATE` (flow control), `CONTINUATION` (continue header block fragments).

**Initiating a stream:** the client sends a `HEADERS` frame with optional dependency/weight, flags, and HPACK-encoded request headers. Payload travels separately in `DATA` frames — a deliberate separation of **control traffic from application data**: flow control applies **only to** `DATA` **frames**, and **non-**`DATA` **frames are always processed with high priority**.

**Server-initiated streams** use `PUSH_PROMISE` (like `HEADERS` but without dependency/weight, since the server controls delivery). To avoid ID collisions: **client-initiated streams have odd IDs, server-initiated streams have even IDs.**

**Sending data:** `DATA` frames carry the payload, split across multiple frames as needed, with the last one setting the `END_STREAM` flag. Content encoding (plain, gzip, …) remains the application's choice.

**Reading a trace:** in the book's worked example, three streams (IDs 1, 3, 5 — all odd, so all client-initiated, no pushes), with the server interleaving `HEADERS` and `DATA` for stream 3 between `DATA` frames for stream 1 — multiplexing visible on the wire.

---



# Key Takeaways (TL;DR)

**TCP:**

- TCP trades timeliness for accuracy; that trade is the root of its performance behavior.
- Every connection costs **one full RTT** for the handshake before any data moves — bandwidth is irrelevant to that cost.
- `max in flight = min(rwnd, cwnd)`; `rwnd` protects the receiver (advertised in every ACK), `cwnd` protects the network (private to the sender, starts at **10 segments**, doubles per RTT).
- **Every new connection runs slow-start**, so short bursty HTTP transfers usually finish before reaching full speed. Real numbers: **264 ms new connection vs 96 ms reused — a 275% difference** with bandwidth playing no part.
- TCP **uses packet loss as its feedback signal** — hence the sawtooth throughput trace.
- **BDP** = capacity × delay caps in-flight data; a **16 KB window at 100 ms RTT caps you at 1.31 Mbps**, and saturating 10 Mbps at 100 ms RTT needs a **122 KB** window (above TCP's 64 KB max without **window scaling**).
- **TCP head-of-line blocking**: one lost packet stalls every later packet in the receiver's buffer, invisibly to the application — the price of in-order delivery, and the case for UDP where ordering isn't needed.
- **Latency, not bandwidth, is the bottleneck.** Send fewer bits, move bits closer, and reuse connections.

**HTTP/2:**

- Same HTTP semantics; **only framing and transport change**, so applications work unmodified.
- **Binary framing layer**: streams (bidirectional, ID'd, prioritizable) → messages → frames (9-byte header, length-prefixed, tagged with a stream ID).
- **Multiplexing is the central feature** — interleaved frames on one connection kill HTTP-layer head-of-line blocking and retire domain sharding, sprites, and concatenation.
- **Prioritization** via weights (1–256) and dependencies forming a preference tree — a **hint, not a guarantee**, by design.
- **One connection per origin**: single-transaction connections drop from **74% → 25%**; fewer TLS handshakes; better congestion behavior — accepting residual **TCP-level** HOL blocking because compression and prioritization win on balance.
- **Flow control** is credit-based, directional, per-stream and per-connection, hop-by-hop, undisableable (default 64 KB window, `WINDOW_UPDATE` to replenish), with **no mandated algorithm**.
- **Server push** via `PUSH_PROMISE` sent *before* the referencing response; unlike inlining, pushed resources are **cacheable, reusable, multiplexed, prioritizable, and declinable** (`RST_STREAM`).
- **HPACK** = static Huffman coding + static/dynamic indexed tables, replacing zlib after the **CRIME attack**; headers lowercase, request line becomes `:method`/`:scheme`/`:authority`/`:path` pseudo-headers.
- Deploy via **TLS + ALPN** (de facto required by browsers); `h2c` Upgrade and prior knowledge exist for cleartext.

**The connecting thread:** HTTP/2 is an application-layer workaround for TCP's connection setup costs — one connection, multiplexed, so you pay for the handshake and slow-start once. And the book names its own sequel: remove HTTP's bottleneck and **TCP becomes the bottleneck** (TCP-level HOL blocking, cwnd collapse on loss affecting all streams) — which is precisely the problem **QUIC/HTTP/3** was built to solve by moving streams into UDP.