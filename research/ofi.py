"""Order flow imbalance (Cont, Kukanov & Stoikov 2014): per-symbol OLS of 1-second mid changes (ticks) on OFI.

The window table comes from metrics.sql (`ofi_windows()`), computed per symbol batch. The fit uses only the regression
sums, so per-symbol beta, in-sample R^2 and out-of-sample R^2 are exact and the raw windows never leave DuckDB. Split:
within each session, windows in the first 70 % of the session's clock are in-sample, the last 30 % out-of-sample.
Empty windows (no quote update) are excluded from the fit.
"""
import numpy as np
import pandas as pd

import db

MIN_IN, MIN_OUT = 30, 10
SPLIT = 0.7


def regression_sums(con, batch_size: int = 250) -> pd.DataFrame:
    parts = []
    for batch in db.symbol_batches(con, batch_size):
        db.select_symbols(con, batch)
        parts.append(con.execute(f"""
            with w as (select sym, win * 1000000000 as ts, ofi, dmid from ofi_windows() where dmid is not null),
            s as (select sym, ofi, dmid, session(ts) as session,
                         ts < session_start(session(ts)) + {SPLIT} * (session_end(session(ts)) - session_start(session(ts))) as insample
                  from w)
            select sym, session, insample, count(*) as n, sum(ofi) as sx, sum(dmid) as sy,
                   sum(ofi * ofi) as sxx, sum(ofi * dmid) as sxy, sum(dmid * dmid) as syy
            from s group by 1, 2, 3""").df())
    db.select_symbols(con, None)
    cols = ["sym", "session", "insample", "n", "sx", "sy", "sxx", "sxy", "syy"]
    return pd.concat(parts, ignore_index=True)[cols] if parts else pd.DataFrame(columns=cols)


def fit_from_sums(n, sx, sy, sxx, sxy, syy):
    """OLS y = a + b x from raw sums. Returns (a, b, r2)."""
    if n < 2:
        return np.nan, np.nan, np.nan
    cxx = sxx - sx * sx / n
    cxy = sxy - sx * sy / n
    cyy = syy - sy * sy / n
    if cxx <= 0 or cyy <= 0:
        return np.nan, np.nan, np.nan
    b = cxy / cxx
    a = sy / n - b * sx / n
    return a, b, cxy * cxy / (cxx * cyy)


def oos_r2(a, b, n, sx, sy, sxx, sxy, syy):
    """Out-of-sample R^2 of fixed (a, b) from the OOS sums: 1 - SSE / SST."""
    if n < 2 or not np.isfinite(a) or not np.isfinite(b):
        return np.nan
    sse = syy - 2 * a * sy - 2 * b * sxy + n * a * a + 2 * a * b * sx + b * b * sxx
    sst = syy - sy * sy / n
    return 1 - sse / sst if sst > 0 else np.nan


def per_symbol(sums: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (sym, session), g in sums.groupby(["sym", "session"]):
        i = g[g["insample"]]
        o = g[~g["insample"]]
        if len(i) != 1 or len(o) != 1 or i["n"].iloc[0] < MIN_IN or o["n"].iloc[0] < MIN_OUT:
            continue
        i, o = i.iloc[0], o.iloc[0]
        a, b, r2 = fit_from_sums(i.n, i.sx, i.sy, i.sxx, i.sxy, i.syy)
        rows.append({"sym": sym, "session": session, "n_in": int(i.n), "n_out": int(o.n), "beta": b, "r2_in": r2,
                     "r2_out": oos_r2(a, b, o.n, o.sx, o.sy, o.sxx, o.sxy, o.syy)})
    return pd.DataFrame(rows, columns=["sym", "session", "n_in", "n_out", "beta", "r2_in", "r2_out"])


def summarise(per_sym: pd.DataFrame, tiers: dict) -> pd.DataFrame:
    """Medians and quartiles across symbols by tier x session; beta reported in ticks per 1,000 shares."""
    p = per_sym.copy()
    p["tier"] = p["sym"].map(tiers)
    p = p.dropna(subset=["tier", "beta"])
    rows = []
    for (tier, session), g in p.groupby(["tier", "session"]):
        b = g["beta"] * 1000                                   # ticks per 1,000 shares of imbalance
        rows.append({"tier": tier, "session": session, "symbols": len(g),
                     "beta_med": b.median(), "beta_q1": b.quantile(0.25), "beta_q3": b.quantile(0.75),
                     "r2_in_med": g["r2_in"].median(), "r2_out_med": g["r2_out"].median(),
                     "r2_out_q1": g["r2_out"].quantile(0.25), "r2_out_q3": g["r2_out"].quantile(0.75),
                     "share_r2_out_pos": float((g["r2_out"] > 0).mean())})
    out = pd.DataFrame(rows)
    if len(out):
        out["tier"] = pd.Categorical(out["tier"], db.TIERS)
        out["session"] = pd.Categorical(out["session"], list(db.SESSIONS))
        out = out.sort_values(["tier", "session"]).reset_index(drop=True)
    return out
