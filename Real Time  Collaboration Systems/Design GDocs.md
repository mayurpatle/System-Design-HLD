# Mock Interview Transcript — "Design Google Docs"

**Format:** 45 minutes, calibrated at Google L5 depth
**Structure:** the six-phase HLD template
**Annotations:** `▶ WHY THIS WORKS` blocks explain the move. Not part of the interview.

> **Calibration note:** this transcript is deliberately pitched above L4. The OT/CRDT deep dive, the TP1/TP2 distinction, and the failover-with-in-flight-ops discussion are L5 signals. If you're interviewing at L4, delivering half of this puts you at the top of the band.

---

## Phase 1 — Scope & Clarify (0:00 – 0:05)

**INTERVIEWER:** Design Google Docs.

**CANDIDATE:** Let me bound this first — collaborative editing has one genuinely hard problem in it and several ordinary ones, and I want to make sure I spend the time on the hard one.

Four questions.

First — scope. There's the editing engine, presence and cursors, comments and suggestions, version history, sharing and permissions, and offline. I'd like to center on **the concurrent editing engine and presence**, cover persistence and version history at a moderate level, and treat permissions as a service I call. Does that work?

**INTERVIEWER:** That works. Assume permissions is solved.

**CANDIDATE:** Second — plain text or rich text? It matters more than it sounds. Rich text means formatting spans that can overlap with insertions and deletions, which complicates the operation model considerably.

**INTERVIEWER:** Rich text. Assume bold, italic, headings, lists.

**CANDIDATE:** Good — I'll design the model to handle attributed ranges rather than bare characters.

Third — concurrency shape. What I need to know is the distribution. My assumption is that the vast majority of documents have one editor, a meaningful tail has two to ten, and there's a long tail of documents with hundreds or thousands of people *open* but only a handful actively typing. Is that roughly right?

**INTERVIEWER:** That's right. Design for the common case but tell me what breaks at the tail.

**CANDIDATE:** Will do. And fourth — offline. Does a user need to edit disconnected for an extended period and merge later, or is brief network interruption enough?

**INTERVIEWER:** Brief interruption is required. Extended offline is a nice-to-have — tell me what it would cost.

**CANDIDATE:** That's a significant answer, because it's the main thing that would push me toward a different core algorithm. I'll come back to it.

So: rich-text collaborative editing, presence and cursors, mostly small editor counts with a viewer-heavy tail, brief-interruption offline required and extended offline as a stretch. Permissions out of scope, comments and suggestions out of scope.

One more thing before I design — what does correctness mean here? I'd define it as three properties: **convergence**, all clients that have seen the same set of operations end in identical state; **intention preservation**, my edit does what I meant relative to what I was looking at; and **local responsiveness**, typing echoes instantly with no server round trip. The third one is why this is hard — if I could wait for the server, this would be a much easier problem.

**INTERVIEWER:** That's the right framing. Go.

> **▶ WHY THIS WORKS**
> The candidate identified before designing that there is *one* hard problem here and asked questions that determine its shape. The rich-text question isn't cosmetic — it changes the operation model. The offline question is the highest-leverage one asked, because it's the main fork between OT and CRDT, and the candidate flagged that explicitly rather than just noting the answer.
>
> The correctness definition at the end is the L5 move. Naming convergence, intention preservation, and local responsiveness as three separate properties — and identifying that the third is what creates the difficulty — frames the entire rest of the interview.

---

## Phase 2 — Estimation (0:05 – 0:10)

**CANDIDATE:** The numbers here have a different shape from most systems, and I want to show why.

*[writes]*

```
ASSUMPTIONS
  DAU                          ~500M
  Docs opened/user/day          ~4
  Doc-open sessions             2B/day → ~25K/sec avg, ~75K/sec peak

EDIT RATE  (per document)
  Human typing                 ~5 chars/sec while actively typing
  Active typists per doc       ~2 (common case)
  Ops/sec per document         ~10        ← surprisingly small
  Batched (50ms coalescing)    ~2-5 msg/sec per doc

CONNECTIONS  ← the number that matters
  Concurrent open docs         ~30M sessions at peak
  Each = 1 persistent conn     30M WebSocket connections

STORAGE
  Doc content                  ~50 KB avg
  Op log                       ~100 bytes/op, high churn
  1B docs × 50 KB              ≈ 50 TB content  (small!)
  Op log w/ 90-day retention   ≈ 200-400 TB
```

**CANDIDATE:** Here's what jumps out.

The **edit rate per document is tiny.** Ten operations per second is nothing — a single modest server could handle thousands of documents. Humans type slowly. This is not a throughput problem in the way most systems are.

The **storage is small.** Fifty terabytes of document content across a billion documents is unremarkable. The op log is bigger than the content, which is worth noting, but it's still not exotic.

The number that actually governs the design is **thirty million concurrent persistent connections**, and — more importantly than the raw count — the fact that they're *partitioned by document* and every document needs a consistent ordering of its operations.

So this is fundamentally a **coordination problem at high connection count**, not a throughput problem. That reframing drives everything: I'm going to care much more about connection management and per-document consistency than about scaling compute.

**INTERVIEWER:** You said 10 ops/sec per doc. What about a doc with 1,000 active editors?

**CANDIDATE:** Then it's 5,000 ops per second into one document, and every one of those has to be ordered, transformed, and broadcast to 1,000 connections. That's 5 million outbound messages per second for a single document.

That case breaks the architecture I'm about to draw, and I'd handle it separately rather than designing the common case around it. I'll cover it in failure modes — the short version is that at that scale you stop treating everyone as an editor and split the read path from the write path.

**INTERVIEWER:** Good, come back to it.

> **▶ WHY THIS WORKS**
> This is the estimation phase doing real work. The candidate discovered that the "obvious" scaling dimension — throughput — is a non-issue, and that the actual constraint is connection count plus per-document coordination. Stating "this reframing drives everything" makes the estimate load-bearing rather than decorative.
>
> When pushed on the tail case, the candidate didn't try to make one architecture serve both. They said the common-case design breaks there, committed to addressing it separately, and moved on. Refusing to over-generalize is a maturity signal.

---

## Phase 3 — API & Data Model (0:10 – 0:15)

**CANDIDATE:** The protocol is where the design lives, so let me be precise.

*[writes]*

```
WEBSOCKET PROTOCOL

  C→S   SUBMIT_OP
        { docId, clientId, baseRevision, ops[] }

  S→C   ACK
        { clientId, serverRevision, transformedOps[] }

  S→C   BROADCAST
        { serverRevision, ops[], authorId }

  C→S   PRESENCE
        { docId, cursorPos, selectionRange }

  S→C   PRESENCE_UPDATE
        { userId, cursorPos, selectionRange, color }

REST (non-hot-path)
  GET  /doc/{id}          → snapshot + revision
  GET  /doc/{id}/history  → version list
```

**CANDIDATE:** The critical field is `baseRevision`. Every operation a client submits says "I computed this against revision N." That's what lets the server know which concurrent operations it needs to transform against. Without it, the server can't reason about causality at all.

Data model:

```
DOCUMENT
  docId (PK) | currentRevision | snapshotRef | metadata

OPERATION_LOG                            ← append-only
  (docId, revision) [PK] | authorId | ops[] | timestamp

SNAPSHOT
  (docId, revision) [PK] | serializedContent

PRESENCE                                 ← ephemeral, never persisted
  (docId, userId) | cursor | selection | TTL
```

**CANDIDATE:** Partition key is `docId` for everything, and that's not a casual choice — it's the central architectural decision.

Every access pattern is scoped to a single document. Load a doc, append an op to a doc, replay a doc's history. Nothing crosses documents. That means documents are **perfectly shardable with zero cross-shard coordination**, which is unusually clean and is what makes this system tractable at a billion documents.

What it makes expensive is anything cross-document — "all docs edited by this user," full-text search across a corpus. Those I'd serve from separate derived indexes fed by CDC, not from the operational store.

**INTERVIEWER:** Why an append-only op log rather than just storing the current document?

**CANDIDATE:** Four reasons, and they compound.

Correctness first: a late-arriving operation from a client on an old revision needs to be transformed against everything that happened since. That requires the history, not just current state.

Recovery: an edit server holding a document in memory can die. Rebuilding requires replaying from a snapshot forward, which requires the log.

Version history and undo are essentially free once the log exists — they're the product feature that falls out of the correctness requirement.

And auditability — who changed what, when.

The cost is that the log grows unboundedly, which is why snapshots exist. I'd snapshot every few hundred operations, and then the log before the last snapshot is only needed for history rather than for correctness, so it can move to cold storage.

> **▶ WHY THIS WORKS**
> Naming `baseRevision` as the critical field, and explaining *why* — it's the causality mechanism — shows the candidate understands what the protocol is actually doing rather than just listing endpoints.
>
> The partition key answer is the strongest part. "Documents are perfectly shardable with zero cross-shard coordination" is the property that makes this system scale, and stating it explicitly, along with what it sacrifices, is exactly the analysis interviewers want and rarely get.

---

## Phase 4 — High-Level Design (0:15 – 0:27)

**CANDIDATE:** Let me draw it.

*[draws]*

```
┌────────────────────────────────────────────────────────────────────┐
│                            CLIENTS                                 │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │  Local document state (source of truth for rendering)    │      │
│  │  Pending queue: ops sent, not yet acked                  │      │
│  │  Local echo → instant, zero round-trip                   │      │
│  └──────────────────────────────────────────────────────────┘      │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ WebSocket
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  GATEWAY TIER  (stateless — terminates connections only)           │
│   auth, rate limit, routes by docId                                │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ lookup: docId → owning edit server
                              ▼
                    ┌──────────────────────┐
                    │  Doc Registry        │  (Redis / etcd)
                    │  docId → serverId    │  + lease
                    └──────────┬───────────┘
                               │
                               ▼
┌────────────────────────────────────────────────────────────────────┐
│  EDIT SERVER TIER   ★ ONE OWNER PER DOCUMENT ★                     │
│                                                                    │
│   ┌────────────────────────────────────────────────────┐           │
│   │  In-memory doc state @ revision N                  │           │
│   │  Transform engine (OT)                             │           │
│   │  Recent op history (for transforming late ops)     │           │
│   │  Connected client set                              │           │
│   └────────────────────────────────────────────────────┘           │
│         │ append (durable) │ broadcast          │ presence         │
└─────────┼──────────────────┼────────────────────┼──────────────────┘
          ▼                  ▼                    ▼
    ┌──────────┐      ┌─────────────┐      ┌─────────────┐
    │ Op Log   │      │ connected   │      │  Presence   │
    │ (Spanner/│      │ clients via │      │  (Redis,    │
    │  Bigtable│      │ gateway     │      │   TTL)      │
    └────┬─────┘      └─────────────┘      └─────────────┘
         │
         ├──▶ [Snapshot Service]  every ~N ops → [Blob Store]
         │
         └──▶ [Kafka] ──▶ search index, activity feed, analytics
```

**CANDIDATE:** The starred line is the whole design.

**Exactly one edit server owns a document at a time.** Every operation for that document flows through that server, which assigns it a monotonic revision number and transforms it against concurrent operations.

That single decision converts what looks like a distributed consensus problem into a **single-writer ordering problem**, which is dramatically simpler. There is no agreement protocol between servers because there's nothing to agree about — one server decides the order, full stop.

Let me trace an edit.

I type a character. The client applies it locally and renders immediately — zero latency, that's the local responsiveness requirement. It puts the op in a pending queue tagged with `baseRevision`.

Gateway routes it to the owning edit server, found via the registry.

Edit server: transforms the op against any operations committed since `baseRevision`, assigns revision N+1, appends to the durable log, acks the sender with the transformed form, broadcasts to everyone else.

The sender receives its ack and reconciles — if the server transformed its op, the client rewrites its local state to match. Everyone else receives the broadcast, transforms it against their own pending ops, and applies.

Result: everyone converges, and nobody waited for a round trip to see their own typing.

**INTERVIEWER:** One server per document is a single point of failure. Why is that acceptable?

**CANDIDATE:** It's a deliberate trade and I'd defend it, but let me first be precise about what it is and isn't.

It's a single point of failure *for one document*, not for the system. A server holding ten thousand documents dying affects ten thousand documents, but the blast radius per document is one document. Compare that to a shared-state design where a coordination failure affects everything.

What it buys is enormous. With one writer, I need no consensus protocol, no distributed locking, no conflict resolution between servers, and — this is the part that matters most and I'll come back to it in the deep dive — the operational transformation algorithm only needs to satisfy a much weaker correctness property than it would without a central ordering point. That single fact is the difference between an algorithm that works and one that's notoriously buggy.

The alternative — multiple servers per document with consensus — would mean every operation pays a consensus round trip. At ten operations per second per document that's affordable, but it buys me nothing, because the failure I'm protecting against is rare and recoverable in about a second.

**INTERVIEWER:** So what does recovery look like?

**CANDIDATE:** The registry holds a lease rather than a plain mapping. The owning server heartbeats to renew it. If the server dies, the lease expires in a couple of seconds and another server claims it.

The new owner loads the latest snapshot and replays the op log forward to reconstruct current state. With snapshots every few hundred ops, that's a fast replay.

Clients see a disconnect, reconnect through the gateway, get routed to the new owner, and resynchronize by sending their pending ops with the `baseRevision` they had. The server transforms those forward. If a client was ahead of what got durably logged, its pending ops get replayed — which is exactly why acking only after the durable write matters.

There's a real subtlety in the in-flight window that I'd want to talk through if we have time in the failure section.

**INTERVIEWER:** We will. Keep going.

**CANDIDATE:** Two more notes on the diagram.

Presence is deliberately separate and never persisted. Cursor positions are high-churn, low-value, and lossy-tolerant — if a cursor update drops, the next one fixes it. Putting it through the same durable path as operations would multiply the write volume for no benefit. It goes to Redis with a TTL, and it's fine if it's occasionally stale.

And the Kafka tap is how everything downstream — search indexing, activity feeds, analytics — gets fed without adding load to the edit path. The edit server's only job is ordering and broadcasting.

> **▶ WHY THIS WORKS**
> The diagram has one starred line, and the candidate identified it as "the whole design" — that focus is what separates a diagram from an architecture.
>
> The single-writer defense is the strongest moment so far. Rather than getting defensive about SPOF, the candidate reframed the blast radius precisely (one document, not the system), listed what the trade buys, and — critically — foreshadowed the algorithmic reason it matters, creating a hook into the deep dive. Then when asked about recovery, they had the lease mechanism, the replay path, *and* the honest flag that there's a subtle in-flight problem worth discussing.

---

**CANDIDATE:** That's the skeleton, deliberately shallow so we have time to go deep.

The two most interesting places are the **transformation algorithm** — OT versus CRDT, which is the actual hard problem and connects back to your offline question — and the **failover semantics** around in-flight operations. Preference?

**INTERVIEWER:** Do the algorithm. That's what I want to hear about.

---

## Phase 5 — Deep Dive: Operational Transformation vs CRDT (0:27 – 0:42)

**CANDIDATE:** Good, because this is where the real engineering is.

**Level one: the problem, concretely.**

Let me use the smallest example that shows the difficulty.

*[writes]*

```
Both clients start at:  "CAT"     (revision 5)

Client A:  insert(pos=0, 'S')  →  "SCAT"
Client B:  delete(pos=2)       →  "CA"     (deleting the 'T')

Both computed against revision 5. Both are correct locally.
Both are now wrong relative to each other.
```

**CANDIDATE:** If the server naively applies both in arrival order: apply A's insert to "CAT" gives "SCAT". Then apply B's delete at position 2 — but position 2 in "SCAT" is 'A', not 'T'. We delete the wrong character and get "SCT". Client B's intention was destroyed.

The fix is transformation.

```
Server applies A:  "CAT" → "SCAT"   (revision 6)

B's op arrives with baseRevision=5. Server must transform it
against A's op:

    T( delete(2), insert(0,'S') )  →  delete(3)

    Reasoning: A inserted at position 0, which is at or before
    B's target position, so everything after shifts right by 1.

Server applies transformed op:  "SCAT" → "SCA"   (revision 7)
Client A receives delete(3), applies to "SCAT" → "SCA"     ✓
Client B receives insert(0,'S'), transforms against its own
  pending delete, applies → "SCA"                          ✓

Converged. Both intentions preserved.
```

**Level two: what makes this hard, and the property that saves us.**

The transformation function has to handle every pair of operation types — insert against insert, insert against delete, delete against insert, delete against delete — plus, for rich text, formatting operations against all of those. That's a matrix of cases and each needs to be correct.

For convergence you need a property called **TP1**: for any two concurrent operations a and b, applying a then transformed-b must produce the same state as applying b then transformed-a. That's the two-way convergence guarantee, and it's demanding but achievable.

Here's the part that matters. In a **peer-to-peer** system with no central ordering, you also need **TP2**: transforming an operation against two others must give the same result regardless of the order you do the transformations in. TP2 is notoriously difficult — there's a well-known history of published OT algorithms that claimed TP2 and were later shown to violate it in edge cases.

**With a central server assigning a total order, TP2 is not required at all.** Every client sees operations in the same sequence, so the ambiguous multi-way transformation case never arises.

That's the payoff for the single-writer decision I made earlier. It's not just an operational simplification — it removes an entire class of correctness bugs that has historically been very hard to get right.

**INTERVIEWER:** So why does anyone use CRDTs, if OT with a server works?

**CANDIDATE:** Because CRDTs solve a problem OT doesn't, and they pay for it differently.

*[writes]*

```
CRDT approach: every character gets a unique, immutable,
globally-ordered identifier.

  "CAT"  →  [ (C, id=0.1) (A, id=0.5) (T, id=0.9) ]

  Client A inserts 'S' before C:  (S, id=0.05)
  Client B deletes T:             tombstone id=0.9

  No transformation needed. Operations commute.
  Any order of application converges.

  Result: [ (S,0.05) (C,0.1) (A,0.5) (T,0.9 ✗deleted) ]
          renders as "SCA"
```

**CANDIDATE:** The key property is that operations **commute** — you can apply them in any order and converge. No transformation, no central ordering requirement, no TP2 problem because there are no transformations to be ambiguous about.

That makes CRDTs genuinely better for peer-to-peer and offline-first. Two clients can sync directly with no server, and a client offline for a month merges cleanly.

The costs are real though.

**Metadata overhead.** Every character carries an identifier. Naive implementations are several times the size of the text. Modern CRDT libraries have optimized this hard with run-length encoding of contiguous runs, and the gap has narrowed a lot — but it's still there.

**Tombstones.** Deleted characters can't be removed, because a concurrent operation might reference them. A document that's been heavily edited accumulates tombstones indefinitely. Garbage collecting them requires knowing all peers have seen the deletion, which is easy with a server and hard without one.

**Interleaving anomalies.** If two users concurrently type entire words at the same position, some CRDT designs interleave the characters — you get "hweolrllod" instead of one word then the other. Both are technically convergent; one is nonsense. Newer designs address this, but it's a real class of problem.

**Level three: the actual choice, and what I'd do.**

For this system as scoped — server-mediated, brief-interruption offline only — **I'd choose OT.**

The reasoning: I already have a central server for permissions, presence, and persistence, so the main CRDT advantage of not needing one buys me nothing. Given the server, TP2 disappears and the hard part of OT goes away. The wire format is compact — an op is a position and a character, which matters at thirty million connections. And there's no tombstone growth or metadata overhead on documents that live for years.

But I want to be honest that this is contingent, not absolute. If your offline requirement had been "edit for a week disconnected," I'd flip. Transforming a week's worth of divergent operations forward is expensive and the failure modes get ugly — you'd end up bounding it and falling back to a conflict copy, which is a worse product experience than CRDT merge. If offline-first were a core requirement, CRDT is the right answer and I'd accept the metadata cost.

And if I were designing this greenfield today rather than describing Google Docs, I'd at least seriously evaluate CRDT, because the libraries have matured substantially and the metadata overhead is much less prohibitive than it was when the OT-based systems were designed.

**INTERVIEWER:** Nice. How does undo work in OT?

**CANDIDATE:** Undo is genuinely one of the hardest parts, and it's where a lot of implementations get subtly wrong behavior.

The naive approach — apply the inverse of your last operation — breaks immediately in collaboration. If I insert a character, you type ten more, and I hit undo, the inverse of my original operation refers to a position that has since moved.

The correct approach is **transformed inverse**. Take the inverse of my operation, then transform it forward against every operation that has been applied since — including other people's. That gives an operation which removes what I inserted, at its current position.

There's a second requirement that's a product decision as much as a technical one: undo should be **per-user**, not global. If I hit undo, it should undo *my* last edit, not whatever the most recent edit in the document was. That means maintaining a per-user undo stack of their own operations, and transforming each one forward when it's popped.

And redo has the same structure with an additional complication — if someone else has edited the region you're redoing into, the redo may no longer be meaningful, and you have to decide whether to apply it anyway or drop it.

**INTERVIEWER:** What about the cursors of other users while I'm typing?

**CANDIDATE:** Cursors are positions, so they have to be transformed exactly like operations.

If your cursor is at position 10 and I insert five characters at position 3, your cursor must move to 15 or it will visually drift and, worse, your next edit will land in the wrong place.

So every transformation applied to the document also gets applied to every tracked cursor and selection range. Selections are two positions and both transform, with a subtlety: if my insert lands *inside* your selection, does your selection expand to include it? That's a product decision — most editors say yes for insertions strictly inside, no for insertions at the boundary.

The nice property is that this is the same transformation machinery, just applied to a different data type. If your transform functions are correct for operations, cursor transformation mostly falls out.

> **▶ WHY THIS WORKS**
> This is a genuine three-level descent. Level one uses the smallest possible concrete example — "CAT" — and shows the failure before showing the fix. Level two introduces TP1 and TP2 and then delivers the payoff: *the single-writer decision from Phase 4 is what eliminates TP2*. Connecting an architectural choice made twelve minutes earlier to a deep algorithmic consequence is a strong L5 signal.
>
> The OT/CRDT comparison is balanced rather than partisan, and the candidate explicitly said the choice was contingent on the offline requirement — tying it back to the scoping question they flagged in Phase 1. Then they went further and said a greenfield design today might reasonably choose differently, which is intellectual honesty that costs nothing and reads as genuine familiarity rather than memorized position.
>
> The undo answer is the kind of detail you only have if you've thought about it: naive inverse breaks, transformed inverse is correct, and undo must be per-user rather than global.

---

## Phase 6 — Failure Modes & Wrap (0:42 – 0:47)

**INTERVIEWER:** Five minutes. What breaks?

**CANDIDATE:** Five things, starting with the one I flagged earlier.

**The in-flight window on failover.** This is the subtle one. The edit server transforms an operation, appends it to the durable log, acks the client, then broadcasts. If it dies between the durable append and the broadcast, the operation is committed but some clients never heard about it. On reconnect they resync from their last known revision and pick it up — that case is fine.

The genuinely bad case is if you ack *before* the durable write, for latency. Then a client believes its edit is committed, the server dies, the op is gone, and the client's local state has diverged from the server's with no way to detect it. So: **ack strictly after the durable append.** That puts a replicated write in the critical path, which costs a few milliseconds — and it's worth it, because the alternative is silent data loss that surfaces as "Docs ate my paragraph."

**The hot document.** The thousand-editor case you asked about. The single-writer design breaks: five thousand ops per second in, five million messages per second out.

I'd split the paths. The handful of genuine editors keep the OT path. Everyone else is a viewer, and viewers get served from read replicas fed by a broadcast tree rather than direct fan-out from the edit server — the edit server publishes once, and a fan-out tier handles the amplification. Viewers can also tolerate a second of latency and coarser batching, which editors cannot. And I'd cap concurrent editors explicitly, degrading additional users to view-only, because a document with a thousand simultaneous typists is not a real product scenario.

**Long offline divergence.** A client offline for a week returns with operations based on a very old revision. The server has to transform them forward against everything since — potentially tens of thousands of operations. That's expensive and the intention preservation gets poor: transforming an edit forward through a week of other people's changes often produces something the user didn't mean.

I'd bound it. Under some threshold, transform forward. Beyond it, don't pretend — create a conflict copy and let the user merge manually. Honest and ugly beats silent and wrong.

**Op log growth.** Every keystroke is an op. A heavily-edited document accumulates a large log. Snapshot every few hundred operations, keep the recent log hot for correctness, age older log segments to cold storage where they serve version history only.

**Registry as a coordination dependency.** The doc registry is on the path for every session establishment. If it's unavailable, no new documents can be opened — though existing sessions continue, because the connection is already established. I'd want it highly available and regionally replicated, and I'd cache the mapping at the gateway with a short TTL so a brief registry blip doesn't stop new connections.

**Observability** — three metrics:

- **Convergence divergence rate** — clients that had to fully resync because their state disagreed with the server. This should be near zero and it's the correctness canary. If transformation has a bug, this is where it shows.
- **Local echo to ack latency, p99** — the user-perceived responsiveness of collaboration.
- **Lease failover rate** — spikes mean edit servers are unhealthy, and it's a leading indicator before users notice.

**INTERVIEWER:** Anything you'd revisit?

**CANDIDATE:** Two things.

The rich text model is the part I most under-specified. I said "attributed ranges" in scoping and then designed as though operations were character inserts and deletes. Formatting operations that span ranges interact with concurrent insertions and deletions in ways that meaningfully expand the transformation matrix — what happens when you bold a range while I split it in half with a paragraph break. That's real complexity I glossed over, and in a production design it's where a lot of the bugs would actually live.

The second is that I chose OT partly on the offline requirement being weak, and I'd want to pressure-test that assumption with actual product data rather than my assumption. If a meaningful fraction of users edit on flaky mobile connections with multi-minute disconnections, the boundary between "brief interruption" and "long divergence" isn't as clean as I treated it, and that would move the calculus toward CRDT. I'd want to see the connection-duration distribution before committing.

**INTERVIEWER:** Good. That's time.

> **▶ WHY THIS WORKS**
> The in-flight failover answer delivers on the hook set in Phase 4, and it lands on a concrete rule — ack after durable append, accept the latency — with the failure mode named in user terms ("Docs ate my paragraph"). That translation from mechanism to user impact is a senior habit.
>
> The self-critique is unusually strong because it's *specific and correct*. Rich-text transformation genuinely is where the complexity hides, and admitting they glossed it is more credible than any claim of completeness. The second point — that the OT choice rests on an assumption they'd want to validate with data — shows the candidate knows which of their decisions is load-bearing.

---

# What To Extract

## The clock

| Phase | Time | What happened |
|---|---|---|
| Scope | 0–5 | Four questions; correctness defined as three properties |
| Estimate | 5–10 | Discovered throughput is *not* the constraint; reframed to coordination |
| API + model | 10–15 | `baseRevision` named as the causality mechanism; perfect shardability identified |
| HLD | 15–27 | Single-writer decision starred and defended; failover hook planted |
| Deep dive | 27–42 | OT vs CRDT, three levels, TP2 payoff, undo, cursors |
| Wrap | 42–47 | In-flight window, hot doc, offline bound, two honest self-critiques |

## The four L5 signals in this transcript

1. **Defining correctness before designing.** Convergence, intention preservation, local responsiveness — and identifying that the third creates the difficulty. Most candidates start drawing boxes.

2. **The estimate that reframed the problem.** Discovering that edit throughput is trivially small and the real constraint is connection count plus per-document coordination. That's the estimate doing genuine work.

3. **Connecting an architectural choice to an algorithmic consequence.** The single-writer decision in Phase 4 is what eliminates TP2 in Phase 5. Twelve minutes apart, explicitly linked. This is the single strongest moment in the transcript.

4. **Contingent rather than absolute positions.** "I'd choose OT — and here's exactly what would make me choose CRDT instead." Then going further: a greenfield design today might reasonably differ. Confident without being dogmatic.

## The pushbacks and the pattern

| Challenge | The move |
|---|---|
| "1,000 active editors?" | Admitted the design breaks, committed to addressing separately, refused to over-generalize |
| "Single point of failure?" | Reframed blast radius precisely, listed what the trade buys, planted a hook |
| "Why not just store current state?" | Four reasons that compound, plus the cost and its mitigation |
| "Why does anyone use CRDT then?" | Genuinely balanced answer; named the condition that would flip the choice |
| "How does undo work?" | Naive approach, why it fails, correct approach, plus the per-user product requirement |

The pattern: **never defend flatly, never capitulate flatly.** Every challenge gets either a reason or an update, and several get both.

## Delivering this at L4

If you're interviewing at L4, you don't need all of this. What you need:

- Phase 1's correctness definition (cheap, high signal)
- The estimate reframe — throughput isn't the problem, coordination is
- Single writer per document, and why it simplifies everything
- The "CAT" example showing why naive application breaks
- OT vs CRDT at level two, with the offline requirement as the deciding factor
- One honest self-critique at the close

That's roughly 60% of this transcript and it will read as clearly above band.

The TP1/TP2 distinction, the undo transformation, and the in-flight failover window are the pieces that push it to L5. Include them if the conversation gets there naturally — but reaching for them before you've delivered the fundamentals cleanly is the wrong trade.