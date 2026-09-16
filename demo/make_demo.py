"""Build demo/data.js from a LadderWriter NDJSON slice (Phase 4 Task 3 Step 2).

Usage: python demo/make_demo.py --ladder data/derived/12302019/ladder_AAPL.ndjson --symbol AAPL --date 12302019 \
                                [--perf data/perf/12302019.txt] [--out demo/data.js]

data.js defines window.SLICE (snapshots with prices in dollars, plus cross/system markers) and window.PERF (the
final-row subset apply-latency percentiles and full-day msgs/s from ops/bench.sh output, when that file is present).
The page makes no network requests, so everything it shows is embedded here.
"""
import argparse
import json
import pathlib
import re
import statistics

LINE = re.compile(r"gc=(?P<gc>\S+) row=(?P<row>\S+) file=(?P<file>\S+) config=\S+ messages=(?P<msgs>\d+) wall=(?P<wall>[\d.,]+)s .*?"
                  r"apply ns: p50=(?P<p50>\d+) p90=(?P<p90>\d+) p99=(?P<p99>\d+) p99\.9=(?P<p999>\d+) max=(?P<max>\d+)")


def num(s):
    return float(s.replace(".", "").replace(",", ".")) if s.count(",") == 1 else float(s.replace(".", "").replace(",", ""))


def perf(path: pathlib.Path):
    """Final-row (+dedupe) G1 numbers: subset percentiles and the median full-day msgs/s."""
    if not path or not path.exists():
        return None
    text = path.read_text(encoding="utf-8", errors="replace").replace("\r", " ")
    sub, full = None, []
    for line in text.split("\n"):
        m = LINE.match(" ".join(line.split()))
        if not m or m["row"] != "+dedupe" or not m["gc"].startswith("G1"):
            continue
        if "sub" in m["file"]:
            sub = sub or m
        else:
            full.append(int(m["msgs"]) / num(m["wall"]))
    if not sub:
        return None
    return {"p50": int(sub["p50"]), "p90": int(sub["p90"]), "p99": int(sub["p99"]), "p999": int(sub["p999"]), "max": int(sub["max"]),
            "msgsPerSec": int(statistics.median(full)) if full else None, "gc": sub["gc"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ladder", required=True)
    ap.add_argument("--symbol", required=True)
    ap.add_argument("--date", required=True)
    ap.add_argument("--perf", default=None)
    ap.add_argument("--out", default=str(pathlib.Path(__file__).resolve().parent / "data.js"))
    a = ap.parse_args()
    slice_ = []
    for line in pathlib.Path(a.ladder).read_text(encoding="ascii").splitlines():
        r = json.loads(line)
        if "b" in r:
            slice_.append({"t": r["t"], "b": [[p / 1e4, sh, n] for p, sh, n in r["b"]], "a": [[p / 1e4, sh, n] for p, sh, n in r["a"]]})
        elif "cross" in r:
            slice_.append({"t": r["t"], "cross": r["cross"], "shares": r["shares"], "price": r["price"] / 1e4})
        else:
            slice_.append({"t": r["t"], "system": r["system"]})
    slice_.sort(key=lambda r: r["t"])
    meta = {"symbol": a.symbol, "date": f"{a.date[4:8]}-{a.date[0:2]}-{a.date[2:4]}" if a.date.isdigit() else a.date,
            "snapshots": sum(1 for r in slice_ if "b" in r)}
    js = ("window.META = " + json.dumps(meta) + ";\nwindow.PERF = " + json.dumps(perf(pathlib.Path(a.perf) if a.perf else None))
          + ";\nwindow.SLICE = " + json.dumps(slice_, separators=(",", ":")) + ";\n")
    pathlib.Path(a.out).write_text(js, encoding="ascii")
    print(f"wrote {a.out}: {meta['snapshots']} snapshots, {len(slice_) - meta['snapshots']} markers, {len(js) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
