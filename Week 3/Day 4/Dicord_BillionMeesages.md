This Discord engineering blog post outlines the massive architectural journey of migrating their message storage system from Apache Cassandra to ScyllaDB. It details how they overcame severe performance bottlenecks to reliably store and serve trillions of chat messages.

Here is a detailed, structured summary of the engineering hurdles they faced, how they rebuilt their system, and the eventual migration strategy.

1. The Scaling Backstory & The Problem with Cassandra
In 2017, Discord famously migrated from MongoDB to Apache Cassandra, scaling up to billions of messages across 12 nodes. However, by 2022, the dataset had swelled to trillions of messages across 177 nodes, and Cassandra was severely buckling under the weight.

The Message Schema: Messages are partitioned by channel_id combined with a bucket (a static time window). Inside each partition, individual messages are organized chronologically using chronologically sortable IDs (Snowflakes).

The Pitfall of "Hot Partitions": Large, highly active servers (like public communities with hundreds of thousands of users) generated massive spikes in traffic to specific channel/bucket partitions. Because Cassandra reads are expensive—requiring queries across in-memory structures (memtables) and multiple on-disk files (SSTables)—concurrently reading from these heavy partitions created severe performance degradation.

Cascading Latency: Because Discord uses quorum consistency for reads and writes, a single overwhelmed node handling a hot partition dragged down response times for the entire database cluster.

Maintenance Toil: The database suffered from heavy Java Virtual Machine (JVM) garbage collection (GC) pauses. Maintenance tasks like "compactions" fell so far behind that operators had to execute a manual "gossip dance" (taking nodes out of rotation to let them catch up on compacting without traffic).

2. Shifting to ScyllaDB
To eliminate the Java garbage collection bottlenecks, Discord turned to ScyllaDB, a Cassandra-compatible database written in C++.

The C++ Advantage: Being written in C++ meant ScyllaDB completely eliminated JVM garbage collection pauses, which had been the primary driver of on-call operational fatigue.

Architecture & Performance Optimization: ScyllaDB utilizes a modern shard-per-core architecture providing excellent workload isolation. To make it viable for Discord, the ScyllaDB team explicitly worked on optimizing "reverse queries"—allowing the team to scan messages in ascending order even if the table layout was naturally sorted descending.

The "Super-Disk" Storage Topology: For their physical layout, Discord provisioned nodes using Local NVMe SSDs for raw speed, coupled with a RAID setup that mirrors data to persistent disks for long-term durability.

3. Buffering with Rust-Based "Data Services"
The team realized that changing the database wasn't an absolute magic fix; they still needed a way to protect the database layer from massive concurrency spikes (like when a server admin tags @everyone). They introduced Data Services written in Rust using the asynchronous Tokio ecosystem.

Request Coalescing: The data services sit directly between the API monolith and the database. If 1,000 users all request the exact same message row simultaneously, the Rust service spawns a single worker task to query the database once. The other 999 requests subscribe to that single task, drastically collapsing database read traffic spikes.

Consistent Hashing: They implemented consistent hash-based routing upstream of these services. Requests are routed using the channel_id as the routing key, ensuring all traffic for a specific channel lands on the exact same data service instance to maximize request coalescing.

4. Executing a Massive 9-Day Migration
The final hurdle was moving trillions of rows safely with zero downtime.

The Original Plan: The team initially set up dual-writing (writing new messages to both Cassandra and ScyllaDB simultaneously) and planned to use ScyllaDB’s Spark migrator to backfill historical data. However, the estimated time to completion was a staggering three months.

The Rust Rewrite: Dissatisfied with the timeline, the engineers rewrote the data migrator in Rust. It read database token ranges, checkpointed progress locally using SQLite, and pushed data into ScyllaDB at blazing speed.

The Result: The new Rust migrator reduced the estimated backfill timeline from three months down to just 9 days.

The Ultimate Impact
After completing the flip-switch migration, Discord saw massive, immediate improvements:

Uniform Latency: Even during intense traffic spikes, read/write latency remained flat and highly predictable.

Massive Resource Savings: ScyllaDB handled the load with significantly less CPU and memory overhead compared to Cassandra.

Peaceful On-Call: The manual "gossip dance" and garbage collection emergency reboots became history, transforming one of Discord's highest-toil systems into a quiet, automated environment.