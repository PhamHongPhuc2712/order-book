# Screencast storyboard (3 minutes)

Record with the terminal on the left and the browser on the right. Every number spoken is in `README.md` and comes from
`research/results/numbers_<day>.md`, `research/results/crossday.md` or `docs/perf.md` — if a number is not in one of
those files, it does not get said.

Every command below was run on 2026-09-20 with all three days on disk, and every figure in the *say* column was checked
against the generated file it comes from. Re-check before recording if the pipeline has been re-run since.

Set up before recording (nothing here should be typed on camera):

```bash
source ops/env.sh          # JAVA_HOME, PATH, PY and CP for this machine; prints what it resolved
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$cp = "replay\target\classes;core\target\classes;" + (Get-Content cp.txt -Raw).Trim()
```

Tabs to have open: <https://phamhongphuc2712.github.io/order-book/>, `research/results/queue_cond_12302019.png`,
`docs/perf.md`, `research/results/crossday.md`, `data/derived/12302019/validation.json`.

| t | screen | command | say |
|---|---|---|---|
| 0:00 | terminal | `java -cp "$CP" sg.phuc.lob.replay.Probe data/itch/12302019.bin` | "This is what NASDAQ actually sends: 269 million length-prefixed binary messages for one day, every symbol. The framing check passes on all of them, and the type histogram is the day — 117 million adds, 114 million deletes." |
| 0:30 | browser, live demo | play at 10× from 9:29:30 | "A Level-3 book has every order, so each price shows shares *and* how many orders are resting there. Pre-open the book is legitimately crossed; the opening cross prints here and resolves it." |
| 1:00 | editor, `validation.json` | — | "Zero structural violations. The interesting counter is priority: 0.19 % of executions don't hit the head of the queue, and every one is either a burst reordering inside one match event or an isolated odd-lot skip. None on this day is off the best price, so the book itself is right." |
| 1:20 | editor, `crossday.md` | — | "Three days — 2019, 2020 and 2025 — one-point-three-four billion messages. Structural counters are zero on all three, and the priority rate stays around a fifth of a percent. On the 2025 day, 42 violations out of 33,000 do sit off the best price; they are six sweeps in five small-caps, and they are reported rather than explained away." |
| 1:40 | editor, `crossday.md` | scroll to the research table | "And the results replicate. The realised spread at 30 seconds is negative on all three days, so in liquid names the liquidity provider is losing to adverse selection inside 30 seconds — not an artifact of one session." |
| 1:55 | editor, `docs/perf.md` | — | "Naive to optimised: allocation 157 bytes per message down to 6, the p99.9 tail from 15 microseconds to under 3, and zero collections on the subset. Throughput only 1.5×, and the table shows why — the `+long` row cut allocation by a third and moved wall time by nothing, so the day is memory-latency bound, not allocation bound." |
| 2:25 | browser, `queue_cond_12302019.png` | — | "The number that needs order-level data: joining a top-100 name with under 200 shares ahead of you, you are filled within five seconds up to half the time; with 35,000 ahead, about one percent. And 88 % of the queue in front of you leaves by cancelling, not by trading." |
| 2:50 | editor, README | "What I would change to run this at a bank" | "To run this at a bank: sequenced transport with gap recovery and a stated policy for what the desk sees during a gap, symbol-partitioned threads, and the validation counters as live alerts." |

## Where each spoken number comes from

| spoken | value | file |
|---|---|---|
| 269 million messages, framing passes | 268,744,780 · `mismatch=0` | `probe.txt` / `crossday.md` §1 |
| 117 M adds, 114 M deletes | 117,145,568 · 114,360,997 | `probe.txt` type histogram |
| 0.19 % priority | 10,565 / 5,683,178 = 0.186 % | `validation.json` |
| 1.34 billion across three days | 268,744,780 + 423,285,709 + 650,338,709 | `crossday.md` §1 |
| priority rate across days | 0.186 / 0.129 / 0.234 % | `crossday.md` §2 |
| 42 off-best on day 3 | 42 = 0.12 % of 33,674 | `numbers_S120825.md` §2 |
| realised spread negative on all three | −15.6 / −5.1 / −9.5 bps | `crossday.md` §3 |
| 157 → 6 B/msg, p99.9 15 → under 3 µs, 1.5× | 156.8 → 5.9 · 14.8 → 2.7 µs | `docs/perf.md` |
| `+long` cut allocation a third, no wall-time change | 156.8 → 102.3 B/msg | `docs/perf.md` |
| half the time / about one percent | 50.6 % → 1.2 % (upper bound) | `queue_cond_12302019.csv` |
| 88 % cancellations | 88.2 % | `numbers_12302019.md` §7 |

## Recording notes

- The `Probe` run takes about **19 s** on the full day with the `.bin` in page cache, and about **86 s** cold (both
  measured on a 12-core Linux box; 21 s on the reference laptop, cached). Run it once before recording so the take is
  the fast one — then start it, say the line, and cut to the result.
- The demo slice starts at **9:24:40**, not 9:28 — there is more pre-open runway than the old storyboard assumed. Scrub
  to about 9:29:50 before hitting play so the crossed pre-open book is on screen within a few seconds and the cross
  lands shortly after.
- Record the demo from the **published URL**, not the local file: it is the thing a reviewer will actually open, and it
  proves the deploy works. It makes no network requests once loaded.
- Keep `validation.json` pretty-printed in the editor rather than raw in the terminal — the counter names are the point.
- Say "none **on this day** is off the best price" at 1:00. It is true of `12302019` and of `01302020`, but not of
  `S120825`, which the 1:20 shot then handles directly. The project's credibility rests on that distinction being
  audible.
- End on the README so the last frame is the repository, with the demo link visible.
