"""Diff our MeatPyExport top-of-book against MeatPy's LOBRecorder output at 1-minute marks (Phase 3 Task 3).

Usage: python meatpy_diff.py <ours.csv> <meatpy.csv> [--day YYYY-MM-DD]

ours:   minute (ns since midnight), bid, bidSh, ask, askSh      (prices at scale 4)
meatpy: Timestamp, Type (Bid/Ask), Level, Price, Volume, N Orders (one row per side per mark; Price in dollars)
"""
import sys

import pandas as pd


def main(argv):
    ours = pd.read_csv(argv[0])
    ref = pd.read_csv(argv[1])
    ref["Timestamp"] = pd.to_datetime(ref["Timestamp"])
    day = ref["Timestamp"].dt.normalize().iloc[0]
    ref["minute"] = ((ref["Timestamp"] - day).dt.total_seconds() * 1e9).round().astype("int64")
    ref = ref[ref["Level"] == 1]
    ref["price4"] = (ref["Price"] * 10_000).round().astype("int64")
    bid = ref[ref["Type"] == "Bid"].set_index("minute")[["price4", "Volume"]].rename(columns={"price4": "bid_ref", "Volume": "bidSh_ref"})
    ask = ref[ref["Type"] == "Ask"].set_index("minute")[["price4", "Volume"]].rename(columns={"price4": "ask_ref", "Volume": "askSh_ref"})
    r = bid.join(ask, how="outer").fillna(0).astype("int64").reset_index()
    m = ours.merge(r, on="minute", how="outer", indicator=True)
    missing = m[m["_merge"] != "both"]
    m = m[m["_merge"] == "both"].copy()
    diff = m[(m.bid != m.bid_ref) | (m.ask != m.ask_ref) | (m.bidSh != m.bidSh_ref) | (m.askSh != m.askSh_ref)]
    print(f"marks ours={len(ours)} meatpy={len(r)} joined={len(m)} only-one-side={len(missing)} differing={len(diff)}")
    if len(missing):
        print("marks present on one side only:"); print(missing[["minute", "_merge"]].head(10).to_string(index=False))
    if len(diff):
        d = diff.copy()
        d["hh:mm"] = (d["minute"] // 60_000_000_000).map(lambda s: f"{s // 60:02d}:{s % 60:02d}")
        print(d[["hh:mm", "bid", "bid_ref", "bidSh", "bidSh_ref", "ask", "ask_ref", "askSh", "askSh_ref"]].head(20).to_string(index=False))
    # Pass/fail is decided on marks both sides produced. MeatPy drops marks it never reaches (file ends before them) and
    # cannot record the very first update of an instrument (its LOB is None until then), so a cold-start fixture always has
    # one-sided marks; they are reported, not failed.
    return 0 if len(diff) == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
