# Screencast storyboard (3 minutes)

Record with the terminal on the left and the browser on the right. Every number spoken is in `README.md` and comes from
`research/results/numbers_<day>.md`, `research/results/crossday.md` or `docs/perf.md` — if a number is not in one of
those files, it does not get said.

Set up before recording (nothing here should be typed on camera):

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$cp = "replay\target\classes;core\target\classes;" + (Get-Content cp.txt -Raw).Trim()
```

Tabs to have open: `demo/index.html`, `research/results/queue_cond_12302019.png`, `docs/perf.md`,
`research/results/crossday.md`, `data/derived/12302019/validation.json`.

| t | screen | command | say |
|---|---|---|---|
| 0:00 | terminal | `java -cp $cp sg.phuc.lob.replay.Probe data\itch\12302019.bin` | "This is what NASDAQ actually sends: 269 million length-prefixed binary messages for one day, every symbol. The framing check passes on all of them, and the type histogram is the day — 117 million adds, 114 million deletes." |
| 0:30 | browser, demo | play at 10× from 9:29:30 | "A Level-3 book has every order, so each price shows shares *and* how many orders are resting there. Pre-open the book is legitimately crossed; the opening cross prints here and resolves it." |
| 1:00 | terminal | `type data\derived\12302019\validation.json` | "Zero structural violations. The interesting counter is priority: 0.19 % of executions don't hit the head of the queue, and every one is either a burst reordering inside one match event or an isolated odd-lot skip. None is off the best price, so the book itself is right." |
| 1:20 | editor, `crossday.md` | — | "Three days, 2019, 2020 and 2025 — nine hundred million messages. Structural counters are zero on all three, and the priority rate stays a fifth of a percent. The result is a property of the feed, not of one day." |
| 1:45 | editor, `docs/perf.md` | — | "Naive to optimised: allocation 157 bytes per message down to 6, the p99.9 tail from 15 microseconds to under 3, and zero collections on the subset. Throughput only 1.5×, and the table shows why — the `+long` row cut allocation by a third and moved wall time by nothing, so the day is memory-latency bound, not allocation bound." |
| 2:20 | browser, `queue_cond_12302019.png` | — | "The number that needs order-level data: joining a top-100 name with under 200 shares ahead of you, you are filled within five seconds up to half the time; with 35,000 ahead, about one percent. And 88 % of the queue in front of you leaves by cancelling, not by trading." |
| 2:50 | editor, README | "What I would change to run this at a bank" | "To run this at a bank: sequenced transport with gap recovery and a stated policy for what the desk sees during a gap, symbol-partitioned threads, and the validation counters as live alerts." |

Recording notes:

- The `Probe` run takes about 30 s on the full day; start it, talk over it, and cut to the result.
- In the demo, scrub to 9:29:50 before hitting play so the crossed pre-open book is on screen within a few seconds.
- Keep `validation.json` pretty-printed in the editor rather than raw in the terminal — the counter names are the point.
- End on the README so the last frame is the repository, with the demo link visible.
