"""Write numbers.md and the charts for one day (Phase 3 Task 5 Step 6). Every number in the write-up comes from here.

Usage: python report.py --derived ../data/derived/12302019 --parquet ../data/parquet --date 12302019 [--results results]

Sections: validation counters (validation.json), priority classification (out/priority_<date>.md if present), MeatPy
cross-check (meatpy_diff on the two CSVs if present), perf reference (docs/perf.md), spreads by tier x session x horizon,
OFI by tier x session, queue tables and the conditional curve. Charts: queue_<date>.png, queue_cond_<date>.png,
spreads_<date>.png. Intermediate tables are saved as CSV next to numbers.md so nothing has to be recomputed to be quoted.
"""
import argparse
import io
import json
import pathlib
import sys
from contextlib import redirect_stdout

import matplotlib
import pandas as pd

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

import db  # noqa: E402
import ofi  # noqa: E402
import queues  # noqa: E402
import spreads  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
# dataviz reference palette, categorical slots 1-3 (validated adjacent and all-pairs, light surface)
TIER_COLOR = {"top100": "#2a78d6", "101-1000": "#eb6834", "rest": "#1baf7a"}
SURFACE, INK, INK2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e6e5e1"


def md(df: pd.DataFrame, floatfmt=".3f") -> str:
    return df.to_markdown(index=False, floatfmt=floatfmt)


def style(ax, title, xlabel, ylabel):
    ax.set_facecolor(SURFACE)
    ax.set_title(title, loc="left", color=INK, fontsize=11, pad=10)
    ax.set_xlabel(xlabel, color=INK2)
    ax.set_ylabel(ylabel, color=INK2)
    ax.grid(True, axis="y", color=GRID, linewidth=0.8)
    ax.grid(False, axis="x")
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(GRID)
    ax.tick_params(colors=INK2, labelsize=9)


def chart_queue(groups: pd.DataFrame, path: pathlib.Path, T_list=queues.T_LIST):
    fig, axes = plt.subplots(1, 3, figsize=(12, 4), sharey=True, facecolor=SURFACE)
    xs = list(range(len(T_list)))
    for ax, session in zip(axes, db.SESSIONS):
        g = groups[groups["session"].astype(str) == session]
        for tier in db.TIERS:
            r = g[g["tier"].astype(str) == tier]
            if r.empty:
                continue
            lo = [r[f"p_fill_{T}s"].iloc[0] for T in T_list]
            up = [r[f"p_fill_upper_{T}s"].iloc[0] for T in T_list]
            ax.plot(xs, lo, color=TIER_COLOR[tier], linewidth=2, marker="o", markersize=6, label=tier)
            ax.plot(xs, up, color=TIER_COLOR[tier], linewidth=2, linestyle=(0, (4, 3)), marker="o", markersize=6, markerfacecolor=SURFACE)
        ax.set_xticks(xs, [f"{T} s" for T in T_list])
        style(ax, f"{session}", "T", "P(fill within T)" if session == "open" else "")
        ax.set_ylim(0, max(0.2, min(1.0, float(groups[[f"p_fill_upper_{T}s" for T in T_list]].max().max()) * 1.15)))
    axes[0].legend(title="tier (solid: fills only; dashed: + exhausted)", frameon=False, fontsize=8, title_fontsize=8, loc="upper left")
    fig.suptitle("Joiner at the touch: fill probability within T, both sides pooled", x=0.01, ha="left", color=INK, fontsize=12)
    fig.tight_layout()
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def chart_conditional(cond: pd.DataFrame, path: pathlib.Path, T: int = 5):
    fig, ax = plt.subplots(figsize=(8, 4.2), facecolor=SURFACE)
    for tier in db.TIERS:
        r = cond[cond["tier"].astype(str) == tier]
        if r.empty:
            continue
        ax.plot(r["decile"], r["p_fill"], color=TIER_COLOR[tier], linewidth=2, marker="o", markersize=6, label=tier)
        ax.plot(r["decile"], r["p_fill_upper"], color=TIER_COLOR[tier], linewidth=2, linestyle=(0, (4, 3)), marker="o", markersize=6, markerfacecolor=SURFACE)
    ax.set_xticks(range(1, 11))
    ax.set_ylim(0, max(0.2, min(1.0, float(cond["p_fill_upper"].max()) * 1.15)))
    style(ax, f"P(fill within {T} s) by initial queue size ahead, within-tier deciles (1 = smallest)", "ahead0 decile", f"P(fill within {T} s)")
    ax.legend(title="tier (solid: fills only; dashed: + exhausted)", frameon=False, fontsize=8, title_fontsize=8, loc="upper right")
    fig.tight_layout()
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def chart_spreads(s: pd.DataFrame, path: pathlib.Path):
    fig, axes = plt.subplots(1, 3, figsize=(12, 4), facecolor=SURFACE)
    hs = list(spreads.HORIZONS)
    xs = list(range(len(hs)))
    for ax, session in zip(axes, db.SESSIONS):
        g = s[s["session"].astype(str) == session]
        for tier in db.TIERS:
            r = g[g["tier"].astype(str) == tier].set_index(g[g["tier"].astype(str) == tier]["h"].astype(str))
            if r.empty:
                continue
            ax.plot(xs, [r.loc[h, "real"] for h in hs], color=TIER_COLOR[tier], linewidth=2, marker="o", markersize=6, label=tier)
            ax.plot(xs, [r.loc[h, "impact"] for h in hs], color=TIER_COLOR[tier], linewidth=2, linestyle=(0, (4, 3)), marker="o", markersize=6, markerfacecolor=SURFACE)
            ax.axhline(r["eff"].iloc[0], color=TIER_COLOR[tier], linewidth=1, alpha=0.5)
        ax.set_xticks(xs, hs)
        ax.axhline(0, color=GRID, linewidth=1)
        style(ax, session, "horizon", "bps" if session == "open" else "")
    axes[0].legend(title="tier (solid: realised; dashed: impact; thin: effective)", frameon=False, fontsize=8, title_fontsize=8)
    fig.suptitle("Effective, realised and impact spread (volume-weighted, E and printable C only)", x=0.01, ha="left", color=INK, fontsize=12)
    fig.tight_layout()
    fig.savefig(path, dpi=150, facecolor=SURFACE)
    plt.close(fig)


def validation_section(derived: pathlib.Path) -> str:
    f = derived / "validation.json"
    if not f.exists():
        return "_validation.json missing_\n"
    v = json.loads(f.read_text())
    rows = [(k, v[k]) for k in ("badLength", "unknownType", "duplicateRef", "unknownRef", "execExceeds", "cancelExceeds", "noDirectory",
                                 "crossedInMarket", "crossedAtResume", "crossedAtResumeMaxLagNs", "priorityChecked", "priorityViolations",
                                 "duplicateMatch", "twoSidedMatches", "brokenUnknown", "liveAtC") if k in v]
    t = pd.DataFrame(rows, columns=["counter", "value"])
    rate = v["priorityViolations"] / v["priorityChecked"] if v.get("priorityChecked") else float("nan")
    structural = sum(v[k] for k in ("badLength", "unknownType", "duplicateRef", "unknownRef", "execExceeds", "cancelExceeds"))
    return (f"Structural violations (six counters): **{structural}**. Priority violation rate: **{100 * rate:.3f} %** "
            f"({v['priorityViolations']:,} / {v['priorityChecked']:,}).\n\n" + t.to_markdown(index=False) + "\n")


def meatpy_section(derived: pathlib.Path, date: str) -> str:
    ours = derived / "meatpy_AAPL.csv"                                  # written by Replay --meatpy AAPL (make.ps1)
    if not ours.exists():
        ours = derived.parent / f"{date}_meatpy" / "meatpy_AAPL.csv"    # the Phase 3 Task 3 location
    ref = HERE / "out" / f"meatpy_{date}_AAPL.csv"                      # MeatPy's own output (meatpy_ref.py, ~1 h per day)
    if not (ours.exists() and ref.exists()):
        return f"_not run on this day (need {ours.name} and {ref.name})_\n"
    import meatpy_diff
    buf = io.StringIO()
    with redirect_stdout(buf):
        meatpy_diff.main([str(ours), str(ref)])
    return "```\n" + buf.getvalue().strip() + "\n```\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--derived", required=True)
    ap.add_argument("--parquet", required=True)
    ap.add_argument("--date", required=True)
    ap.add_argument("--results", default=str(HERE / "results"))
    ap.add_argument("--memory", default="6GB")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--skip", default="", help="comma-separated sections to skip: spreads,ofi,queue")
    ap.add_argument("--reuse", default="", help="comma-separated sections to load from the results CSVs instead of recomputing: spreads,ofi")
    a = ap.parse_args(argv)
    derived, parquet, date = pathlib.Path(a.derived), pathlib.Path(a.parquet), a.date
    results = pathlib.Path(a.results)
    if not (derived / "validation.json").exists():              # data/ cleared: the run's small files were kept
        derived = results / "runs" / date
    results.mkdir(parents=True, exist_ok=True)
    skip = set(filter(None, a.skip.split(",")))
    reuse = set(filter(None, a.reuse.split(",")))
    con = db.connect(parquet, date, a.memory, a.threads)
    tiers = db.tier_map(con)

    out = [f"# numbers.md — {date}", "",
           "Every figure below is produced by `research/report.py` from the derived output of one full-day replay; nothing is typed in.",
           "Prices in bps of the mid; sessions open [9:30, 10:00), midday [10:00, 15:30), close [15:30, 16:00) ET; tiers by that day's",
           "E + printable C + P share volume rank (top100, 101-1000, rest), NASDAQ test symbols excluded.", ""]

    out += ["## 1. Validation counters", "", validation_section(derived)]
    pri = HERE / "out" / f"priority_{date}.md"
    if not pri.exists():                                        # data/ cleared: use the copy kept with the results
        pri = results / "runs" / date / f"priority_{date}.md"
    out += ["## 2. Priority-violation classification", "", (pri.read_text(encoding="utf-8").split("\n", 1)[1].strip() if pri.exists() else "_not classified_"), ""]
    out += ["## 3. MeatPy cross-check (AAPL top-of-book at 1-minute marks)", "", meatpy_section(derived, date)]
    out += ["## 4. Performance", "", "See `docs/perf.md` (naive -> optimised tables, G1 and Generational ZGC, JFR). Not regenerated here.", ""]

    if "spreads" not in skip and db.has_table(con, "executions"):
        if "spreads" in reuse:
            blocks = pd.read_csv(results / f"spread_blocks_{date}.csv")
        else:
            print("spreads...", flush=True)
            blocks = spreads.block_sums(con)
            blocks.to_csv(results / f"spread_blocks_{date}.csv", index=False)
        s = spreads.summarise(blocks)
        s.to_csv(results / f"spreads_{date}.csv", index=False)
        chart_spreads(s, results / f"spreads_{date}.png")
        cols = ["tier", "session", "h", "n_exec", "volume", "eff", "eff_lo", "eff_hi", "real", "real_lo", "real_hi", "impact", "impact_lo", "impact_hi"]
        out += ["## 5. Spreads by tier x session x horizon (volume-weighted bps; 95 % CI by 5-minute block bootstrap)", "",
                "`eff` does not depend on the horizon; it is repeated per row for the CI. `impact = eff - real`.", "", md(s[cols], ".2f"), "",
                f"![spreads](spreads_{date}.png)", ""]

    if "ofi" not in skip and db.has_table(con, "bbo"):
        if "ofi" in reuse:
            per = pd.read_csv(results / f"ofi_symbols_{date}.csv")
        else:
            print("ofi...", flush=True)
            per = ofi.per_symbol(ofi.regression_sums(con))
            per.to_csv(results / f"ofi_symbols_{date}.csv", index=False)
        o = ofi.summarise(per, tiers)
        o.to_csv(results / f"ofi_{date}.csv", index=False)
        out += ["## 6. OFI: 1-second mid change (ticks) on OFI, per-symbol OLS, in-sample first 70 % of each session", "",
                "Medians and quartiles across symbols with at least 30 in-sample and 10 out-of-sample windows; `beta` in ticks of mid change per",
                "1,000 shares of imbalance; `share_r2_out_pos` = fraction of symbols whose out-of-sample R^2 is positive.", "", md(o, ".3f"), ""]

    if "queue" not in skip and db.has_table(con, "episodes"):
        print("queue...", flush=True)
        ep = queues.enrich(con.execute("select * from episodes").df(), tiers)
        overall = queues.summary(ep)
        groups = queues.by_group(ep)
        groups.to_csv(results / f"queue_groups_{date}.csv", index=False)
        pooled = queues.by_group(ep, keys=("tier", "session"))
        pooled.to_csv(results / f"queue_tier_session_{date}.csv", index=False)
        cond = queues.conditional(ep)
        cond.to_csv(results / f"queue_cond_{date}.csv", index=False)
        chart_queue(pooled, results / f"queue_{date}.png")
        chart_conditional(cond, results / f"queue_cond_{date}.png")
        at = overall.attrs
        out += ["## 7. Queue-position fill probability (exact joiner episodes)", "",
                f"Episodes: **{at['n']:,}** over {ep['sym'].nunique()} probed symbols ({(ep['tier'] == 'top100').sum():,} top100, "
                f"{(ep['tier'] == '101-1000').sum():,} 101-1000, {(ep['tier'] == 'rest').sum():,} rest), both sides, sampled at the first",
                "top-of-book change at or after each 1-second grid point, censored at 60 s. Outcome shares: filled "
                f"{100 * at['filled']:.1f} %, moved {100 * at['censor_moved']:.1f} %, exhausted {100 * at['censor_exhausted']:.1f} %, "
                f"T {100 * at['censor_T']:.1f} %, eod {100 * at['censor_eod']:.1f} %. Anomaly rate (an order behind the joiner filled first) "
                f"{100 * at['anomaly']:.2f} %. Cancellation share of queue movement ahead of joiners: **{100 * at['cancel_share']:.1f} %**.", "",
                "`p_fill` counts observed fills only (a lower bound); `p_fill_upper` also counts `exhausted` episodes at their exhaustion time.", "",
                "### 7.1 Overall", "", md(overall, ".3f"), "",
                "### 7.2 By tier x session (both sides)", "",
                md(pooled[["tier", "session", "n", "p_fill_1s", "p_fill_5s", "p_fill_30s", "p_fill_60s", "p_fill_upper_5s", "p_fill_upper_60s",
                           "cancel_share", "censor_moved", "censor_exhausted", "anomaly", "median_ahead0"]], ".3f"), "",
                f"![queue](queue_{date}.png)", "",
                "### 7.3 By tier x session x side", "",
                md(groups[["tier", "session", "side", "n", "p_fill_5s", "p_fill_60s", "p_fill_upper_5s", "cancel_share", "median_ahead0"]], ".3f"), "",
                "### 7.4 Conditional on initial queue size: P(fill within 5 s | ahead0 decile within tier) — the headline finding", "",
                md(cond, ".3f"), "", f"![conditional](queue_cond_{date}.png)", ""]

    (results / f"numbers_{date}.md").write_text("\n".join(out), encoding="utf-8")
    print(f"wrote {results / f'numbers_{date}.md'}")


if __name__ == "__main__":
    main()
