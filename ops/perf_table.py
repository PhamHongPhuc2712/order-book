"""Summarise ops/bench.sh output into Markdown tables: one per GC, rows cumulative, full-day = median of runs.

Usage: python ops/perf_table.py data/perf/12302019.txt
"""
import re
import statistics
import sys
from collections import defaultdict

ROWS = ["naive", "+mmap", "+long", "+array", "+pool", "+dedupe"]
LINE = re.compile(
    r"gc=(?P<gc>\S+) row=(?P<row>\S+) file=(?P<file>\S+) config=\S+ messages=(?P<msgs>\d+) wall=(?P<wall>[\d.,]+)s "
    r"msgs/s=(?P<mps>[\d.,]+) alloc=(?P<alloc>[\d.,]+) B/msg live=\d+ poolCreated=(?P<pool>\d+) "
    r"apply ns: p50=(?P<p50>\d+) p90=(?P<p90>\d+) p99=(?P<p99>\d+) p99\.9=(?P<p999>\d+) max=(?P<max>\d+)")


def num(s):
    """The JVM prints with the system locale: '1.378.761' (thousands) and '11,3' (decimal)."""
    if s.count(",") == 1:
        return float(s.replace(".", "").replace(",", "."))
    return float(s.replace(".", "").replace(",", ""))


def records(path):
    # Java prints CRLF on Windows and bench.sh only folds LF, so a record can contain a bare CR: fold it before splitting.
    text = open(path, encoding="utf-8", newline="").read().replace("\r", " ")
    for line in text.split("\n"):
        m = LINE.match(" ".join(line.split()))
        if m:
            yield m.groupdict()


def main(path):
    runs = defaultdict(list)     # (gc, row, kind) -> list of dicts
    for d in records(path):
        kind = "subset" if "sub" in d["file"] else "full"
        runs[(d["gc"], d["row"], kind)].append(d)

    for gc in ["G1", "Z"]:
        print(f"\n### {'G1GC' if gc == 'G1' else 'ZGC'} (`-Xms8g -Xmx8g -XX:+Use{gc}GC` full day; subset with `--hist` at 4g)\n")
        print("| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |")
        print("|---|---|---|---|---|---|---|---|---|---|")
        base = None
        for row in ROWS:
            full, sub = runs.get((gc, row, "full"), []), runs.get((gc, row, "subset"), [])
            if not full and not sub:
                continue
            walls = sorted(num(r["wall"]) for r in full)
            wall = statistics.median(walls) if walls else float("nan")
            msgs = int(full[0]["msgs"]) if full else 0
            mps = msgs / wall if walls else float("nan")
            if base is None and walls:
                base = mps
            speed = f" ({mps / base:.1f}x)" if base and walls else ""
            alloc = num(full[0]["alloc"]) if full else (num(sub[0]["alloc"]) if sub else float("nan"))
            s = sub[0] if sub else {}
            pct = [s.get(k, "—") for k in ("p50", "p90", "p99", "p999", "max")]
            print(f"| {row} | {wall:.1f} s | {mps:,.0f}{speed} | {len(full)} | {alloc:.1f} | " + " | ".join(str(p) for p in pct) + " |")
        spread = {row: [num(r["wall"]) for r in runs.get((gc, row, "full"), [])] for row in ROWS}
        spread = {k: v for k, v in spread.items() if v}
        if spread:
            print("\nFull-day run spread (s): " + "; ".join(f"{k}: {', '.join(f'{w:.0f}' for w in v)}" for k, v in spread.items()))


if __name__ == "__main__":
    main(sys.argv[1])
