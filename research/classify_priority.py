"""Classify price-time-priority violations from a Replay --dump-priority run (Phase 3 Task 2 Step 3).

Usage: python classify_priority.py --derived ../data/derived/12302019 [--out out/priority_12302019.md]

Buckets (spec plan, refined with what the dump can see):
  gate_bug     tradingState != 'T'               -> should never occur; a gate bug if it does
  off_best     orderPrice != bestPrice           -> an order behind a better displayed level executed
  burst        at best, not head, and >= 2 executions on the symbol at the same nanosecond (from executions.ndjson)
               -> the fills of one match event reported in non-FIFO order
  isolated     at best, not head, single execution at that nanosecond -> a genuine priority anomaly
Each bucket gets counts and a few summary statistics; the numbers go to numbers.md verbatim.
"""
import argparse
import json
import pathlib

import pandas as pd


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--derived", required=True)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    d = pathlib.Path(a.derived)
    v = pd.read_json(d / "priority.ndjson", lines=True)
    n = len(v)
    print(f"violations dumped: {n:,}")

    # The dump is capped by Replay --dump-priority N, so say whether these are all of the day's violations or the
    # first N of them: every share below is a share of what was dumped, not of the population, when it is a sample.
    total = None
    vj = d / "validation.json"
    if vj.exists():
        total = json.loads(vj.read_text(encoding="utf-8")).get("priorityViolations")
    coverage = (f"Violations dumped: **{n:,}**" if total is None else
                f"Violations classified: **{n:,}** of **{total:,}**" + (" (all of them)" if n >= total else " (the first ones dumped; the shares below are of the sample)"))

    # burst size: executions on the same symbol at the same nanosecond, from the full derived executions table
    ex = pd.read_json(d / "executions.ndjson", lines=True, dtype={"ts": "int64"})[["ts", "sym", "side"]]
    burst = ex.groupby(["sym", "ts"]).size().rename("burstSize").reset_index()
    v = v.merge(burst, on=["sym", "ts"], how="left")
    v["burstSize"] = v["burstSize"].fillna(1).astype(int)

    v["bucket"] = "isolated"
    v.loc[v["burstSize"] >= 2, "bucket"] = "burst"
    v.loc[v["orderPrice"] != v["bestPrice"], "bucket"] = "off_best"
    v.loc[v["state"] != "T", "bucket"] = "gate_bug"
    # sanity: nothing at head should ever be here
    assert not v["isHead"].any(), "a violation with isHead=true means the check itself is wrong"

    lines = [f"# Priority-violation classification — {d.name}", "", coverage, "",
             "| bucket | count | share | median queuePos | median levelCount | median burstSize | median execShares |", "|---|---|---|---|---|---|---|"]
    for b in ["burst", "isolated", "off_best", "gate_bug"]:
        g = v[v["bucket"] == b]
        if len(g) == 0:
            lines.append(f"| {b} | 0 | 0.0 % | — | — | — | — |")
            continue
        lines.append(f"| {b} | {len(g):,} | {100 * len(g) / n:.1f} % | {g['queuePos'].median():.0f} | {g['levelCount'].median():.0f} | {g['burstSize'].median():.0f} | {g['execShares'].median():.0f} |")
    lines += ["", "## Detail", ""]
    b = v[v["bucket"] == "burst"]
    if len(b):
        lines.append(f"- burst: {100 * (b['nsSinceLastExec'] == 0).mean():.1f} % have an execution on the same symbol at the previous message's nanosecond "
                     f"(nsSinceLastExec == 0); queuePos 1 in {100 * (b['queuePos'] == 1).mean():.1f} % of cases, <= 3 in {100 * (b['queuePos'] <= 3).mean():.1f} %.")
    i = v[v["bucket"] == "isolated"]
    if len(i):
        odd = (i["execShares"] < 100).mean()
        lines.append(f"- isolated: {100 * odd:.1f} % are odd-lot executions (< 100 shares); median nsSinceLastExec {i['nsSinceLastExec'].median():,.0f} ns; "
                     f"top symbols: " + ", ".join(f"{s} ({c})" for s, c in i['sym'].value_counts().head(5).items()) + ".")
    o = v[v["bucket"] == "off_best"]
    if len(o):
        gap = ((o["orderPrice"] - o["bestPrice"]).abs() / 100).describe()
        lines.append(f"- off_best: order sits {gap['50%']:.0f} cents (median) behind the best displayed level, max {gap['max']:.0f}; "
                     f"{100 * (o['execShares'] < 100).mean():.1f} % odd lots; top symbols: " + ", ".join(f"{s} ({c})" for s, c in o['sym'].value_counts().head(5).items()) + ".")
    lines += ["", "## Top symbols overall", "", v["sym"].value_counts().head(10).to_frame("violations").to_markdown()]
    text = "\n".join(lines)
    print(text)
    if a.out:
        pathlib.Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        pathlib.Path(a.out).write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
