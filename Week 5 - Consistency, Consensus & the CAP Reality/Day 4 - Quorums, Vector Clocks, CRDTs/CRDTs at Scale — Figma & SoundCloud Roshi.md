# CRDTs at Scale — Figma & SoundCloud Roshi

> Two production systems, opposite conclusions.
> **Figma** read the CRDT literature and *rejected* true CRDTs.
> **SoundCloud** read the same literature and *shipped* one.
> Studying them together is worth far more than studying either alone.
>
> Sources:
> - Evan Wallace, *"How Figma's multiplayer technology works"* (Oct 2019)
> - Peter Bourgon, *"Roshi: a CRDT system for timestamped events"* (SoundCloud, 2014) + the Roshi README

---

# PART 1 — FIGMA: *"We're inspired by CRDTs. We don't use them."*

## 1. The setup

- **Client/server over WebSockets.** Figma clients are web pages talking to a server cluster.
- **One server process per document.** Everyone editing that doc connects to that process.
- **Open a doc → download a full copy.** From then on, updates flow both ways over the socket.
- **Offline works arbitrarily long.** On reconnect, the client downloads a **fresh copy**,
  **replays its offline edits on top**, and resumes syncing.
- Written in **Rust** on the server (that's a separate post).
- **Only documents go through multiplayer.** Comments, users, teams, projects live in **Postgres**
  with a completely separate sync system — different trade-offs around performance, offline, and
  security.

**The process note is quietly important:** *"connecting and reconnecting are very simple; all the
complexity is in updates to already-connected documents."* They deliberately made the cold path
dumb so the hot path could be the only hard thing.

**And a methodology note worth stealing:** before touching the real codebase, they built a
**prototype playground** — a web page simulating **three clients + a server**, visualizing the
entire system state, letting them script offline clients and bandwidth-limited links. They
evaluated algorithms *there*, then grafted the winner onto production. **"Taking time to research
and prototype in the beginning really paid off"** is one of their three stated takeaways.

## 2. Why not OT?

OT (Operational Transformation) is what powers Google Docs. Figma rejected it:

- **Combinatorial explosion.** Every new operation type must define how it transforms against
  *every other* operation type. The state space becomes very hard to reason about.
- **Hard to implement correctly.** Wallace cites the standard critique: formal proofs for OT are
  complicated and error-prone **even for algorithms handling only two character-wise primitives
  (insert and delete)**.
- **Overkill.** OT is optimized for *long text documents*. **Figma isn't a text editor.**

> **Their stated design goal: "no more complex than necessary to get the job done."** A simpler
> system is easier to reason about, implement, debug, test, and maintain. That sentence is the
> whole post in miniature.

## 3. Why not *true* CRDTs either? — **the key insight**

They liked CRDTs, borrowed from them, and then **deliberately broke the rules**:

> **"CRDTs are designed for decentralized systems where there is no single central authority to
> decide what the final state should be. There is some unavoidable performance and memory overhead
> with doing this. Since Figma is centralized (our server is the central authority), we can
> simplify our system by removing this extra overhead."**

**Unpack that, because it's the single most useful idea in the post:**

CRDTs pay for **coordination-freedom** with **metadata** — vector clocks, unique tags, tombstones,
version vectors. That machinery exists **solely to answer "which of these concurrent events came
first?" without anyone to ask.**

**Figma has someone to ask.** The server sees every message and can simply **define the order of
events**. So all that metadata is **dead weight**.

> **A CRDT is what you build when you have no authority. If you have a server, you have an
> authority — so buy the simplicity and stop paying for the metadata.**

**But — and this is why they still recommend reading the literature —** CRDTs gave them a
**well-studied, correct foundation** and the **intuition** for what a correct system looks like.
Only then did they **relax the requirements** deliberately, knowing exactly what they were giving
up. Their first takeaway is literally: *"CRDT literature can be relevant even if you're not
creating a decentralized system."*

**Also worth noting: a Figma document is not one CRDT.** It's **several CRDT-inspired structures
composed together**, each solving a different sub-problem.

## 4. The document model

> **Every Figma document is a tree of objects, like the HTML DOM.**
> Root → pages → hierarchy of objects. (This is the layers panel.)

Each object has an **ID** and a bag of **properties**. Two equivalent mental models:

```
Map<ObjectID, Map<Property, Value>>          ← a two-level map

or

rows of  (ObjectID, Property, Value)          ← a database of triples
```

**The payoff:** *"adding new features to Figma usually just means adding new properties to
objects."* The data model is the product roadmap's substrate. That's excellent design.

## 5. Syncing properties — the LWW register, minus the timestamp

> **The server tracks the latest value any client sent for a given (object, property).**

Conflict matrix:

| Scenario | Result |
|---|---|
| Different properties, same object | ✅ No conflict |
| Same property, different objects | ✅ No conflict |
| **Same property, same object** | ⚠️ **Conflict → last value received by the server wins** |

> **"This approach is similar to a last-writer-wins register in CRDT literature except we don't
> need a timestamp because the server can define the order of events."**

**There it is.** A real LWW-register needs a timestamp + peer-ID tiebreak because there's no
authority. **Figma deletes the timestamp entirely** — the server's receive order *is* the order.
That's the metadata savings, made concrete.

### The consequence: atomicity at the property boundary

**The converged value is always a value some client actually sent.** So:

```
Current text: "B"
Client 1 → "AB"        (concurrently)
Client 2 → "BC"

Result: "AB" or "BC".  NEVER "ABC".
```

**This is why you cannot collaboratively edit the same text string in Figma.** And they're at peace
with it: *"Figma is a design tool, not a text editor, and this use case isn't one we're
optimizing for."*

**A scoping decision, stated as a scoping decision.** Enormously mature.

### The flicker problem (best engineering detail in the post)

Local changes are **applied immediately** (no waiting for server ack — responsiveness). But if you
*also* blindly apply every incoming server change, you get **flickering**: an older,
already-acknowledged value momentarily overwrites your newer, unacknowledged one.

**The fix, and the reasoning is lovely:**

> Show the user your **best prediction of the eventually-consistent value.** Your unacknowledged
> change hasn't reached the server yet, but everything *from* the server already has — so **your
> own change is the most recent one in last-to-the-server order.** It *is* the best prediction.
>
> ⇒ **Discard incoming server changes that conflict with your unacknowledged local changes.**

Client-side optimistic UI, derived from first principles rather than hacked in.

## 6. Object creation & deletion — LWW set, with a twist

- **Create and remove are explicit operations.** You **cannot** bring an object into existence by
  writing a property to an unassigned ID.
- **Deleting removes ALL of the object's data from the server**, including every property.
- Conceptually a **LWW-element-set**: "is this object alive?" is just another LWW boolean property.

**The twist — and it's a great one:**

> **Figma does NOT keep the properties of deleted objects on the server.**
> **They live in the *undo buffer of the client that did the delete*.** If that client undoes, it
> is **responsible for restoring all the properties itself.**

**Why:** *"This helps keep long-lived documents from continuing to grow in size as they are
edited."*

**Read that as: they solved the CRDT tombstone/GC problem by refusing to have tombstones.** The
undo buffer *is* the tombstone — and it's on someone else's machine, and it evaporates when the
tab closes. The single hardest unsolved problem in the CRDT literature, sidestepped by an
architectural choice.

**Client-generated IDs:** each client has a unique client ID which it embeds in every object ID it
mints, so **no two clients ever generate the same object ID**. Note *why* the server can't just
assign IDs: **object creation must work offline.**

## 7. Syncing the tree — the hardest part

Reparenting (moving an object to a new parent) is the whole difficulty. Two goals:

1. **Reparenting must not conflict with unrelated property changes.** (One person recolors while
   another moves it — both must succeed.)
2. **Two concurrent reparents of the same object must never yield two copies** of it in the tree.

**The naive approach — model a reparent as delete + recreate-with-new-ID — fails**, because
**concurrent edits get dropped when the object's identity changes.**

**Their solution:** store **a link to the parent as a property on the child.**
- ✅ Object identity is preserved.
- ✅ An object can't end up with **multiple parents** (which could happen if parents stored child
  lists instead).

### The cycle problem

Parent links are now just **directed edges on a graph** — nothing guarantees a valid tree.

```
Client A: make A a child of B     }  concurrent
Client B: make B a child of A     }
                                   → cycle. 💥
```

- **The server rejects parent updates that would create a cycle**, so the *server's* state is
  always a valid tree.
- **But the client can still get there temporarily**: it may have sent an unacknowledged
  "A under B" *and* received "B under A" from the server. It doesn't yet know its change will be
  rejected. **A client cannot reject server changes — the server is the ultimate authority.**

**Figma's fix:** temporarily parent the objects to each other and **remove them from the tree**
until the server rejects the client's change and things settle. The objects **briefly disappear.**

**And their comment on it is the most honest line in the post:**

> *"This solution isn't great because the object temporarily disappears, but it's a simple solution
> to a very rare temporary problem so we didn't feel the need to try something more complicated."*

**Ship the ugly fix for the rare case. Spend your complexity budget elsewhere.**

### Child ordering: fractional indexing

An object's position among its siblings is **a fraction strictly between 0 and 1**. Children sort
by position. **To insert between two objects, take the average of their positions.**

```
   A: 0.25        B: 0.50        C: 0.75
   insert between A and B → position (0.25 + 0.50) / 2 = 0.375
```

This is the **dense-total-order identifier** trick from the CRDT sequence literature (Treedoc,
Logoot), in its simplest possible form.

**Critical detail:** **the parent link and the position must be stored as a SINGLE property**, so
they update **atomically**. Otherwise you could keep a position from an old parent after being
reparented, which is meaningless.

## 8. Undo/redo — the part nobody thinks about

Undo is trivial single-player and **inherently confusing** in multiplayer. If someone else edited
the objects you edited, and then you undo — should your *earlier* edit be applied over their
*later* one?

**They found a guiding principle, and everything fell out of it:**

> **"If you undo a lot, copy something, and redo back to the present (a common operation), the
> document should not change."**

Sounds obvious. But the single-player meaning of redo — *"put back what I did"* — will happily
**overwrite what other people did next.**

**Therefore, in Figma: an undo operation modifies the *redo* history at the time of the undo, and a
redo operation modifies the *undo* history at the time of the redo.** The stacks are actively
rewritten so a redo only re-asserts **your own** past edits, never reverts a peer's later edit.

**The lesson generalizes:** when the semantics are genuinely ambiguous, **find the user-facing
invariant you refuse to break**, and derive the mechanism from it. Don't start from the mechanism.

---

# PART 2 — SOUNDCLOUD ROSHI: *the opposite decision*

## 9. The problem

SoundCloud's **stream / activity feed**. They chose **fan-in-on-read**: rather than fanning a new
event out to every follower's timeline at write time, **read the recent events of everyone you
follow and merge them on the fly.**

- ✅ **Fast writes, minimal storage.**
- ❌ **Reads are brutal.** Follow thousands of users → thousands of simultaneous reads, then
  time-sort, merge, and truncate — all inside a request-response deadline. Bourgon noted that as
  far as they knew, **nobody at their scale built timelines via fan-in-on-read.**

## 10. The design

> **Roshi = a stateless, distributed layer over Redis (in Go) implementing a LWW-element-set CRDT
> with limited inline garbage collection.**
> **Partition tolerant, highly available, eventually consistent. Sits in the critical request path.**

**LWW-element-set, stated intuitively:**

> **An element is in the set if its most recent operation was an `add`.**
> **It is not in the set if its most recent operation was a `remove`.**

Mechanically (straight from Shapiro's catalog):

```
Set S = (A, R)              -- an "add set" and a "remove set"

add(e)     → insert (e, now())  into A
remove(e)  → insert (e, now())  into R

e ∈ S  iff  e ∈ A  AND  e is not in R with a HIGHER timestamp
```

**Storage:** Redis **ZSET** (sorted set) — because a ZSET *is* a set ordered by score, and here the
score is the timestamp. **The data structure and the CRDT are the same shape.** That's the whole
trick, and it's beautiful.

**Topology:**
- Shard the dataset across many Redis instances → a **cluster**.
- Duplicate clusters (typically **3**) → a **farm**. The clusters **do not talk to each other.**
- **Writes go to all clusters; the operation returns success once a user-defined number of clusters
  ack** — a **quorum**, exactly Dynamo's `W`.
- Slow or failed clusters are healed later by **read-repair**.

> **"Roshi leverages CRDT semantics to ensure consistency without explicit consensus."**

**Six developer-months to build.** It powered the SoundCloud stream for years.

## 11. Why LWW here, and not OR-Set?

**Because the domain has an external total order that everyone already agrees on: the event
timestamp.** These are **time-series events** — a "like" or "repost" *happened* at a wall-clock
moment, and the feed is *displayed in timestamp order anyway.*

LWW's fatal flaw elsewhere — *"clock skew silently decides which write survives"* — **doesn't bite
here**, because:
1. The timestamp is **externally meaningful**, not an artificial tiebreak.
2. **Losing a concurrent duplicate of the same event is harmless** — it was the same event.

**And the payoff is GC:** LWW's timestamps give you **inline garbage collection** — you can bound
the set (keep the newest N per key) and drop old entries, because *a feed only ever shows the most
recent things anyway.* **The tombstone problem that plagues OR-Sets simply doesn't arise.**

> **Roshi picked the "weaker," data-losing CRDT — and it was the right call, because the domain
> supplied the total order for free and old data was worthless by definition.**

---

# PART 3 — THE SYNTHESIS

## 12. Figma vs Roshi, side by side

| | **Figma** | **Roshi** |
|---|---|---|
| **Topology** | **Centralized** — one server process per doc | **Decentralized** — 3 independent, non-communicating Redis clusters |
| **Is there an authority?** | ✅ Yes: the server | ❌ No |
| **True CRDT?** | ❌ **No** — "inspired by" | ✅ **Yes** — LWW-element-set |
| **Conflict order determined by** | **Server receive order** (no timestamps!) | **Event timestamps** (externally meaningful) |
| **Property/element conflict** | LWW, server-ordered | LWW, timestamp-ordered |
| **Tombstones / GC** | **Avoided** — deleted state lives in the deleting client's undo buffer | **Inline GC** — bounded sets, old events are worthless |
| **Consensus** | Not needed (server is authority) | Not needed (CRDT semantics) |
| **Killer constraint** | Must work **offline** for arbitrarily long | Must serve the **critical read path** at huge fan-in |

## 13. The lessons that actually transfer

**1. Ask first: "do I have an authority?"**
If yes → **don't pay for CRDT metadata.** The server can define the order; that's what a server is
*for*. If no (offline-first, multi-master, edge, peer-to-peer) → **CRDTs are the principled
answer.** Figma and Roshi differ on *exactly this one question*, and everything else follows.

**2. CRDT literature is valuable even when you won't use a CRDT.**
It gives you a **correct baseline** and the vocabulary to describe what you're relaxing. Figma's
first stated takeaway. You **relax deliberately**, knowing the cost — instead of inventing an
ad-hoc merge and discovering the cost in production.

**3. Choose the CRDT that matches your domain's *natural* order.**
Roshi chose LWW — the "lossy" one everyone warns you about — because **timestamps were already
meaningful and old data was already worthless.** Meanwhile an OR-Set would be the right call for a
shopping cart, where losing an add is unacceptable. **The type follows the semantics you actually
want, not the one with the best reputation.**

**4. GC is the real CRDT tax — and both systems dodged it architecturally, not algorithmically.**
Figma **moved deleted state off the server** into the deleter's undo buffer. Roshi **bounded the
sets** because a feed is inherently finite. Neither solved distributed garbage collection. **Both
made it someone else's problem.** That is, honestly, the state of the art.

**5. Scope ruthlessly, and say so out loud.**
Figma: *"You can't co-edit a text string. That's fine; we're not a text editor."* And: *"The object
briefly disappears in this rare case. We didn't feel the need to do something more complicated."*
**Naming what you're not solving is a senior move**, and both posts do it constantly.

**6. When semantics are ambiguous, find the invariant, then derive the mechanism.**
Figma's undo/redo was a mess until they fixed on *"undo-a-lot-then-redo-back must not change the
document."* Everything followed from that one sentence.

---

## 14. Recall

- **Figma is NOT a CRDT.** Server = central authority ⇒ **the server defines event order** ⇒ **no
  timestamps needed** on its LWW registers ⇒ **less metadata, faster, leaner.**
- **Figma document = `Map<ObjectID, Map<Property, Value>>`**, a DOM-like tree.
  **Conflict granularity = (object, property).** Last value to reach the server wins.
- **Atomic at the property boundary** ⇒ "AB" or "BC", **never "ABC"** ⇒ **no collaborative text
  editing**, by design.
- **Discard incoming server changes that conflict with unacknowledged local ones** — that's the
  anti-flicker rule, and it's just "show your best prediction of the converged value."
- **Parent link stored as a property on the child** (preserves identity, prevents multi-parent).
  **Server rejects cycles; clients can transiently see one** and hide the objects until it resolves.
- **Fractional indexing** for sibling order: position = a fraction; insert = average of neighbors.
  **Parent + position must be one atomic property.**
- **Deleted-object properties live in the deleting client's undo buffer**, not the server —
  **that's how they avoid tombstones and unbounded document growth.**
- **Undo invariant:** undo-a-lot → copy → redo-to-present **must not change the document.** Hence
  undo rewrites redo history and vice versa.
- **Roshi = LWW-element-set on Redis ZSETs**, 3 non-communicating clusters, **quorum writes +
  read-repair**, inline GC. **No consensus.** ~6 developer-months.
- **The one question that decides everything: do you have a central authority?**
  **Yes → skip the CRDT. No → use one.**