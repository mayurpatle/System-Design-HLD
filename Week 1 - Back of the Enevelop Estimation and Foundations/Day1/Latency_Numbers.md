# Latency Comparison Numbers (~2012)

| Operation | ns | us | ms | Notes |
| --- | --- | --- | --- | --- |
| L1 cache reference | 0.5 ns | | | |
| Branch mispredict | 5 ns | | | |
| L2 cache reference | 7 ns | | | 14x L1 cache |
| Mutex lock/unlock | 25 ns | | | |
| Main memory reference | 100 ns | | | 20x L2 cache, 200x L1 cache |
| Compress 1K bytes with Zippy | 3,000 ns | 3 us | | |
| Send 1K bytes over 1 Gbps network | 10,000 ns | 10 us | | |
| Read 4K randomly from SSD* | 150,000 ns | 150 us | | ~1GB/sec SSD |
| Read 1 MB sequentially from memory | 250,000 ns | 250 us | | |
| Round trip within same datacenter | 500,000 ns | 500 us | | |
| Read 1 MB sequentially from SSD* | 1,000,000 ns | 1,000 us | 1 ms | ~1GB/sec SSD, 4X memory |
| Disk seek | 10,000,000 ns | 10,000 us | 10 ms | 20x datacenter roundtrip |
| Local LLM, generate 1 token | 15,000,000 ns | 15,000 us | 15 ms | Small model on consumer GPU (2026) |
| Read 1 MB sequentially from disk | 20,000,000 ns | 20,000 us | 20 ms | 80x memory, 20X SSD |
| Frontier LLM, generate 1 token | 20,000,000 ns | 20,000 us | 20 ms | Hosted model output (2026) |
| Local LLM, time to first token | 75,000,000 ns | 75,000 us | 75 ms | Small model, short prompt (2026) |
| Local LLM (CPU), generate 1 token | 100,000,000 ns | 100,000 us | 100 ms | Small model, no GPU (2026) |
| Send packet CA->Netherlands->CA | 150,000,000 ns | 150,000 us | 150 ms | |
| Fast LLM, time to first token | 250,000,000 ns | 250,000 us | 250 ms | Specialized inference hardware (2026) |
| Frontier LLM, time to first token | | | 1,000 ms | Short prompt, no cache (2026) |
| Frontier LLM, short response | | | 3,000 ms | ~100 output tokens (2026) |
| Frontier LLM, long context prefill | | | 10,000 ms | ~100K input tokens, no cache (2026) |
| Frontier LLM, reasoning response | | | 30,000 ms | Single call with thinking (2026) |

## Notes

- 1 ns = 10^-9 seconds
- 1 us = 10^-6 seconds = 1,000 ns
- 1 ms = 10^-3 seconds = 1,000 us = 1,000,000 ns

## Credit

- By Jeff Dean: http://research.google.com/people/jeff/
- Originally by Peter Norvig: http://norvig.com/21-days.html#answers

## Contributions

- 'Humanized' comparison: https://gist.github.com/hellerbarde/2843375
- Visual comparison chart: http://i.imgur.com/k0t1e.png
- Interactive Prezi version: https://prezi.com/pdkvgys-r0y6/latency-numbers-for-programmers-web-development/latency.txt
