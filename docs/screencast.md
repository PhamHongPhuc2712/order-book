# Screencast storyboard (3 minutes)

Record with the terminal on the left and the browser on the right. Every number spoken is in `README.md` and comes from
`research/results/numbers_12302019.md` or `docs/perf.md`.

| t | screen | say |
|---|---|---|
| 0:00 | `java -cp … Probe data/itch/12302019.bin 10000` then the full file | "This is what NASDAQ actually sends: 269 million length-prefixed binary messages for one day, every symbol. Framing check passes on all of them." |
| 0:30 | `demo/index.html` at 10×, from 9:29:30 | "A Level-3 book has every order, so each price shows shares *and* how many orders. Pre-open the book is legitimately crossed; the opening cross prints here and resolves it." |
| 1:00 | `data/derived/12302019/validation.json` | "Zero structural violations. The interesting counter is priority: 0.19 % of executions don't hit the head of the queue, and every one is a burst reordering or an isolated odd-lot skip; none is off the best price." |
| 1:40 | `docs/perf.md` G1 table | "Naive to optimised: allocation 157 bytes per message to 6, the p99.9 tail from 15 microseconds to under 3, zero collections. Throughput only 1.5×, and the table shows why: the day is memory-latency bound, not allocation bound." |
| 2:20 | `research/results/queue_cond_12302019.png` | "The number that needs order-level data: joining a top-100 name with under 200 shares ahead, you are filled within five seconds up to half the time; with 35,000 ahead, about one percent. And 88 % of the queue in front of you leaves by cancelling, not trading." |
| 2:50 | `README.md`, "What I would change to run this at a bank" | "To run this at a bank: sequenced transport with gap recovery, symbol-partitioned threads, and the validation counters as live alerts." |
