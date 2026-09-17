"""Cross-day comparison table (Phase 4 Task 4 Step 1): one table per theme, one column per day.

Usage: python crossday.py --days 12302019 01302020 S120825 [--derived ../data/derived] [--results results]
       [--out results/crossday.md]

Nothing is measured here. Every figure is read back from a file some other stage generated — `validation.json` and
`replay.txt` from the replay, `out/priority_<day>.md` from the classifier, and the CSVs `report.py` writes next to
`numbers_<day>.md` — so the README can quote a cross-day number under the same rule as a single-day one. A day whose
files are missing is reported as `—` rather than dropped, so an unfinished day is visible instead of silently absent.
"""
import argparse
import json
import pathlib
import re

import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
DASH = "—"


def read_any(path: pathlib.Path) -> str:
    """Read a log whatever wrote it: PowerShell's Tee-Object left UTF-16LE files, Start-Process leaves UTF-8."""
    b = path.read_bytes()
    if b[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return b.decode("utf-16")
    return b.decode("utf-8", errors="replace")


def num(s: str) -> float:
    """Parse a grouped number whichever locale Replay printed it in: '768.144' = 768144, '551,0' = 551.0, '5,9' = 5.9.

    With both separators the last one is the decimal point. With one, a group of exactly three digits after it is
    thousands grouping — which is safe here because every value Replay prints with a decimal has one decimal place.
    """
    s = s.strip()
    if "," in s and "." in s:
        cut = max(s.rfind(","), s.rfind("."))
        return float(s[:cut].replace(",", "").replace(".", "") + "." + s[cut + 1:])
    for sep in (",", "."):
        if sep in s:
            head, _, tail = s.rpartition(sep)
            return float(s.replace(sep, "")) if len(tail) == 3 else float(head + "." + tail)
    return float(s)


def replay_stats(path: pathlib.Path) -> dict:
    """messages / wall / msgs_per_s / b_per_msg / pool_created from a Replay summary line."""
    if not path.exists():
        return {}
    m = re.search(r"messages=(\S+) wall=(\S+?)s msgs/s=(\S+) alloc=(\S+) B/msg live=(\S+) poolCreated=(\S+)", read_any(path))
    if not m:
        return {}
    return {"messages": int(num(m[1])), "wall_s": num(m[2]), "msgs_per_s": num(m[3]), "b_per_msg": num(m[4]),
            "live_at_end": int(num(m[5])), "pool_created": int(num(m[6]))}


def probe_stats(path: pathlib.Path) -> dict:
    if not path.exists():
        return {}
    m = re.search(r"frames=(\d+) bytes=(\d+) mismatch=(\d+)", read_any(path))
    return {"frames": int(m[1]), "bytes": int(m[2]), "mismatch": int(m[3])} if m else {}


def priority_buckets(path: pathlib.Path) -> dict:
    """{bucket: (count, share)} from the classifier's markdown table."""
    if not path.exists():
        return {}
    out = {}
    for line in read_any(path).splitlines():
        m = re.match(r"\|\s*(burst|isolated|off_best|gate_bug)\s*\|\s*([\d,]+)\s*\|\s*([\d.]+) %", line)
        if m:
            out[m[1]] = (int(m[2].replace(",", "")), float(m[3]))
    return out


def meatpy_line(numbers_md: pathlib.Path) -> str:
    if not numbers_md.exists():
        return ""
    m = re.search(r"marks ours=\d+.*differing=\d+", read_any(numbers_md))
    return m[0] if m else ""


def csv(path: pathlib.Path) -> pd.DataFrame:
    return pd.read_csv(path) if path.exists() else pd.DataFrame()


def day_facts(day: str, derived: pathlib.Path, results: pathlib.Path) -> dict:
    """Everything the tables below quote, for one day; missing files simply leave keys out."""
    d = derived / day
    f = {"day": day}
    f.update(probe_stats(d / "probe.txt"))
    f.update(replay_stats(d / "replay.txt"))
    v = d / "validation.json"
    f["validation"] = json.loads(v.read_text(encoding="utf-8")) if v.exists() else {}
    f["priority"] = priority_buckets(HERE / "out" / f"priority_{day}.md")
    f["meatpy"] = meatpy_line(results / f"numbers_{day}.md")
    f["queue"] = csv(results / f"queue_tier_session_{day}.csv")
    f["cond"] = csv(results / f"queue_cond_{day}.csv")
    f["spreads"] = csv(results / f"spreads_{day}.csv")
    f["ofi"] = csv(results / f"ofi_{day}.csv")
    daily = d / "daily.ndjson"
    f["symbols"] = sum(1 for _ in daily.open("rb")) if daily.exists() else None
    probes = d / "probe_symbols.txt"
    f["probed"] = len(probes.read_text(encoding="utf-8").strip().split(",")) if probes.exists() else None
    return f


def pick(df: pd.DataFrame, col: str, **where):
    """One cell out of a results CSV, or None if the row isn't there."""
    if df.empty or col not in df.columns:
        return None
    m = pd.Series(True, index=df.index)
    for k, val in where.items():
        m &= df[k].astype(str) == str(val)
    rows = df[m]
    return None if rows.empty else rows[col].iloc[0]


def fmt(x, spec="{:,.0f}"):
    return DASH if x is None or (isinstance(x, float) and pd.isna(x)) else spec.format(x)


def table(title: str, rows: list, days: list, note: str = "") -> list:
    """rows = [(label, [cell per day])]; every cell already a string."""
    out = [title, ""]
    if note:
        out += [note, ""]
    out += ["| " + " | ".join(["check"] + days) + " |", "|" + "---|" * (len(days) + 1)]
    out += ["| " + " | ".join([label] + cells) + " |" for label, cells in rows]
    return out + [""]


def scale_rows(facts: list) -> list:
    return [
        ("file size (decompressed bytes)", [fmt(f.get("bytes")) for f in facts]),
        ("messages", [fmt(f.get("messages") or f.get("frames")) for f in facts]),
        ("symbols with a daily line", [fmt(f.get("symbols")) for f in facts]),
        ("peak live orders (`poolCreated`)", [fmt(f.get("pool_created")) for f in facts]),
        ("replay wall with derived output", [fmt(f.get("wall_s"), "{:,.0f} s") for f in facts]),
        ("msgs/s (with derived output)", [fmt(f.get("msgs_per_s")) for f in facts]),
        ("allocation B/msg", [fmt(f.get("b_per_msg"), "{:,.1f}") for f in facts]),
    ]


def correctness_rows(facts: list) -> list:
    def v(f, k, spec="{:,.0f}"):
        return fmt(f["validation"].get(k), spec)

    def structural(f):
        keys = ("badLength", "unknownType", "duplicateRef", "unknownRef", "execExceeds", "cancelExceeds")
        return fmt(sum(f["validation"][k] for k in keys)) if all(k in f["validation"] for k in keys) else DASH

    def rate(f):
        val = f["validation"]
        if not val.get("priorityChecked"):
            return DASH
        return f"{val['priorityViolations']:,} / {val['priorityChecked']:,} = **{100 * val['priorityViolations'] / val['priorityChecked']:.3f} %**"

    def buckets(f):
        p = f["priority"]
        if not p:
            return DASH
        return (f"burst {p['burst'][1]:.1f} %, isolated {p['isolated'][1]:.1f} %, "
                f"off-best {p['off_best'][0]}, gate {p['gate_bug'][0]}")

    def anomaly(f):
        q = f["queue"]
        if q.empty:
            return DASH
        n = q["n"].sum()
        return f"{100 * (q['anomaly'] * q['n']).sum() / n:.2f} % of {n:,.0f}"

    return [
        ("framing mismatches (`Probe`)", [fmt(f.get("mismatch")) for f in facts]),
        ("structural violations (six counters)", [structural(f) for f in facts]),
        ("crossed in market hours, state T", [v(f, "crossedInMarket") for f in facts]),
        ("… within 1 s of a resumption (D26)", [v(f, "crossedAtResume") for f in facts]),
        ("duplicate match numbers", [v(f, "duplicateMatch") for f in facts]),
        ("broken trades referencing an unseen match", [v(f, "brokenUnknown") for f in facts]),
        ("two-sided matches (D28)", [v(f, "twoSidedMatches") for f in facts]),
        ("live orders at system event `C`", [v(f, "liveAtC") for f in facts]),
        ("price-time priority violations", [rate(f) for f in facts]),
        ("… classified (500 sampled)", [buckets(f) for f in facts]),
        ("MeatPy AAPL top-of-book, 1-minute marks", [f["meatpy"] or DASH for f in facts]),
        ("queue anomalies (order behind a joiner filled first)", [anomaly(f) for f in facts]),
    ]


def research_rows(facts: list, tier="top100", session="midday") -> list:
    def q(f, col, spec="{:.1%}"):
        return fmt(pick(f["queue"], col, tier=tier, session=session), spec)

    def band(f, lo, up):
        a, b = pick(f["queue"], lo, tier=tier, session=session), pick(f["queue"], up, tier=tier, session=session)
        return DASH if a is None or b is None else f"{100 * a:.1f} % – {100 * b:.1f} %"

    def dec(f, decile, col="p_fill_upper"):
        return fmt(pick(f["cond"], col, tier=tier, decile=decile), "{:.1%}")

    def sp(f, col, h="30s"):
        return fmt(pick(f["spreads"], col, tier=tier, session=session, h=h), "{:.1f}")

    def of(f, col):
        return fmt(pick(f["ofi"], col, tier=tier, session=session), "{:.3f}")

    return [
        ("episodes (all tiers, both sides)", [fmt(f["queue"]["n"].sum()) if not f["queue"].empty else DASH for f in facts]),
        ("probed symbols", [fmt(f.get("probed")) for f in facts]),
        ("P(fill ≤ 5 s), lower – upper", [band(f, "p_fill_5s", "p_fill_upper_5s") for f in facts]),
        ("P(fill ≤ 60 s), lower – upper", [band(f, "p_fill_60s", "p_fill_upper_60s") for f in facts]),
        ("P(fill ≤ 5 s), smallest ahead0 decile (upper)", [dec(f, 1) for f in facts]),
        ("P(fill ≤ 5 s), largest ahead0 decile (upper)", [dec(f, 10) for f in facts]),
        ("cancellation share of queue movement", [q(f, "cancel_share") for f in facts]),
        ("median shares ahead at the touch", [fmt(pick(f["queue"], "median_ahead0", tier=tier, session=session)) for f in facts]),
        ("effective spread (bps)", [sp(f, "eff") for f in facts]),
        ("realised spread at 30 s (bps)", [sp(f, "real") for f in facts]),
        ("OFI out-of-sample R², median symbol", [of(f, "r2_out_med") for f in facts]),
        ("OFI β (ticks per 1,000 shares), median symbol", [of(f, "beta_med") for f in facts]),
    ]


def build(days: list, derived: pathlib.Path, results: pathlib.Path) -> str:
    facts = [day_facts(d, derived, results) for d in days]
    out = ["# crossday.md — " + " · ".join(days), "",
           "Generated by `research/crossday.py` from each day's `validation.json`, `replay.txt`, `out/priority_<day>.md`",
           "and the CSVs beside `numbers_<day>.md`. No figure is recomputed here, and none is typed in.", ""]
    out += table("## 1. Scale", scale_rows(facts), days)
    out += table("## 2. Correctness", correctness_rows(facts), days,
                 "The same checks the spec asks for, on every day. Structural counters are expected to be zero on all of them.")
    out += table("## 3. Research, top100 at midday", research_rows(facts), days,
                 "One tier and one session so the days are comparable at a glance; the full grids are in each `numbers_<day>.md`.")
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", nargs="+", required=True)
    ap.add_argument("--derived", default=str(HERE.parent / "data" / "derived"))
    ap.add_argument("--results", default=str(HERE / "results"))
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    results = pathlib.Path(a.results)
    text = build(a.days, pathlib.Path(a.derived), results)
    out = pathlib.Path(a.out) if a.out else results / "crossday.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
