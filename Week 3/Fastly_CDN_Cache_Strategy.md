Fastly’s engineering blog posts and documentation focus heavily on shifting from traditional, rigid caching architectures to a high-concurrency, developer-controlled modern edge network.

Instead of measuring success solely by the traditional Cache Hit Ratio, Fastly frames its strategy around maximizing Origin Offload (minimizing both the raw request volume and the total bytes that hit your backend infrastructure) and moving computation closer to the end user.

The core pillars of Fastly's CDN caching and origin architecture include:

### 1. The Core Philosophy: Instant Purging Changes Everything
Historically, engineers avoided caching dynamic or rapidly changing data at the edge because cache invalidation was slow and unreliable. Fastly’s core strategy relies on Instant Purging (~150ms globally). When invalidation is this fast, your caching philosophy flips: you can cache almost everything by default with a massive Time to Live (TTL) and surgically evict data the exact millisecond it changes in your database.

### 2. Advanced Invalidation Strategies
Fastly avoids the brute-force method of wiping the entire cache (a "Full Purge"), which crashes down on backend origins as a "thundering herd." Instead, they utilize:

Surrogate Keys (Cache Tags): Responses are tagged with custom tracking headers (e.g., Surrogate-Key: product-12345 user-99). When a specific entity updates, an API call purges only the edge nodes holding that exact key, leaving the rest of the global cache completely untouched.

Soft Purging vs. Hard Purging: Instead of deleting an object entirely (Hard Purge), a Soft Purge marks the asset as stale. When a user requests it, Fastly serves the stale content instantly to the client while asynchronously fetching the fresh copy from the origin in the background using stale-while-revalidate. This keeps user latency at zero and prevents origin spikes.

### 3. Advanced Origin Offload & "Request Collapsing"
To protect backend servers during high-concurrency spikes (like live media events or flash sales), Fastly utilizes Request Collapsing.
If a popular asset expires and 10,000 concurrent requests hit an edge POP at the exact same millisecond, Fastly intercepts them. It sends exactly one request back to the origin server to fetch the fresh data, holds the other 9,999 requests in a waiting state, and streams the incoming response to all of them simultaneously.

### 4. Advanced Multi-CDN Chaining (Origin Shielding)
For massive enterprises utilizing multiple CDN networks simultaneously for redundancy, Fastly promotes CDN Chaining via tools like Media Shield:

Traditional multi-CDN setups result in every single CDN hitting your origin independently when they experience a cache miss.

Fastly’s strategy inserts a centralized Shield CDN layer right in front of your core infrastructure. Other CDNs (like Akamai or Cloudflare) treat the Fastly Shield as their "origin." Fastly handles the global request collapsing and heavy caching, ensuring your actual backend servers see minimal traffic.

### 5. Caching the "Uncacheable" via Edge Logic
Fastly shifts heavy application logic out of the centralized backend and into the edge network using Compute (running WebAssembly/WASM code at the edge):

Dynamic Content Normalization: Edge logic can strip unnecessary query strings, normalize headers, or evaluate user cookies directly at the POP to map highly fragmented requests down to a single cached object.

Vary Header Optimization: For authenticated variations (e.g., displaying slightly different content based on a User-role like admin vs subscriber), Fastly normalizes the authorization state at the edge and handles up to 50 distinct variants of an object directly in memory at each Point of Presence (POP).