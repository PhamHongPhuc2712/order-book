"""Choose the queue-probe symbols for one day from daily.ndjson (Phase 3 Task 4 Step 3).

Usage: python pick_probes.py --derived ../data/derived/12302019 [--per-tier 20] [--out ../data/derived/12302019/probe_symbols.txt]

Tier by that day's E + printable C + P share volume rank (Metrics Definitions): top100 = ranks 1-100, 101-1000, rest = 1001+.
Within each tier the symbols are taken at evenly spaced ranks so the sample spans the tier rather than its top. NASDAQ test
symbols (Z?ZZT) are excluded; `rest` is restricted to symbols with at least MIN_EXECS executions so an episode can ever end
in a fill. Deterministic: same daily.ndjson, same list.
"""
import argparse
import pathlib
import re

import pandas as pd

TEST_SYMBOL = re.compile(r"^Z.ZZT$")
MIN_EXECS = 10


def tiers(daily: pd.DataFrame) -> pd.DataFrame:
    d = daily[~daily["sym"].str.match(TEST_SYMBOL)].copy()
    d["vol"] = d["volEC"] + d["volP"]
    d = d.sort_values(["vol", "sym"], ascending=[False, True]).reset_index(drop=True)
    d["rank"] = d.index + 1
    d["tier"] = "rest"
    d.loc[d["rank"] <= 1000, "tier"] = "101-1000"
    d.loc[d["rank"] <= 100, "tier"] = "top100"
    return d


def pick(daily: pd.DataFrame, per_tier: int) -> pd.DataFrame:
    d = tiers(daily)
    out = []
    for tier in ("top100", "101-1000", "rest"):
        g = d[d["tier"] == tier]
        if tier == "rest":
            g = g[g["execs"] >= MIN_EXECS]
        n = min(per_tier, len(g))
        idx = [int(round(i * (len(g) - 1) / max(n - 1, 1))) for i in range(n)]
        out.append(g.iloc[idx])
    return pd.concat(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--derived", required=True)
    ap.add_argument("--per-tier", type=int, default=20)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    daily = pd.read_json(pathlib.Path(a.derived) / "daily.ndjson", lines=True)
    p = pick(daily, a.per_tier)
    print(p[["sym", "rank", "tier", "vol", "execs"]].to_string(index=False))
    syms = ",".join(p["sym"])
    print(syms)
    if a.out:
        pathlib.Path(a.out).write_text(syms + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
