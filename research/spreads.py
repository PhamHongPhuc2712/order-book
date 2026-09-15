"""Effective / realised / impact spreads by tier x session x horizon, volume-weighted, with block-bootstrap CIs.

Computed in DuckDB per symbol batch (the ASOF joins against a 100M-row quote table stay bounded), aggregated to
5-minute blocks, then the volume-weighted means and their 95 % CIs come from resampling blocks (Metrics Definitions).
"""
import numpy as np
import pandas as pd

import db

HORIZONS = {"1s": 1 * db.NS, "5s": 5 * db.NS, "30s": 30 * db.NS}
BLOCK = 300 * db.NS
BOOT = 500


def block_sums(con, batch_size: int = 250, horizons=HORIZONS) -> pd.DataFrame:
    """Per (tier, session, horizon, block): sum of shares*x for each spread and the share volume."""
    parts = []
    for batch in db.symbol_batches(con, batch_size):
        db.select_symbols(con, batch)
        for name, h in horizons.items():
            df = con.execute(f"""
                select t.tier, session(x.ts) as session, x.ts // {BLOCK} as block,
                       count(*) as n, sum(x.shares) as vol,
                       sum(x.shares * x.eff_bps) as w_eff, sum(x.shares * x.real_bps) as w_real, sum(x.shares * x.impact_bps) as w_impact
                from eff_real({h}) x join daily_tier t on t.sym = x.sym
                where x.eff_bps is not null and x.real_bps is not null
                group by 1, 2, 3""").df()
            df["h"] = name
            parts.append(df)
    db.select_symbols(con, None)
    if not parts:
        return pd.DataFrame(columns=["tier", "session", "block", "n", "vol", "w_eff", "w_real", "w_impact", "h"])
    return pd.concat(parts, ignore_index=True).groupby(["tier", "session", "h", "block"], as_index=False).sum()


def summarise(blocks: pd.DataFrame, boot: int = BOOT, seed: int = 0) -> pd.DataFrame:
    """Volume-weighted means per tier x session x horizon and 95 % block-bootstrap CIs."""
    rng = np.random.default_rng(seed)
    rows = []
    for (tier, session, h), g in blocks.groupby(["tier", "session", "h"]):
        w = g["vol"].to_numpy(dtype=float)
        tot = w.sum()
        row = {"tier": tier, "session": session, "h": h, "n_exec": int(g["n"].sum()), "volume": int(tot), "blocks": len(g)}
        for m in ("eff", "real", "impact"):
            x = g[f"w_{m}"].to_numpy(dtype=float)
            row[m] = x.sum() / tot if tot else np.nan
            if len(g) >= 2 and tot:
                idx = rng.integers(0, len(g), size=(boot, len(g)))
                est = x[idx].sum(axis=1) / w[idx].sum(axis=1)
                row[f"{m}_lo"], row[f"{m}_hi"] = np.percentile(est, [2.5, 97.5])
            else:
                row[f"{m}_lo"] = row[f"{m}_hi"] = np.nan
        rows.append(row)
    out = pd.DataFrame(rows)
    if len(out):
        out["tier"] = pd.Categorical(out["tier"], db.TIERS)
        out["session"] = pd.Categorical(out["session"], list(db.SESSIONS))
        out["h"] = pd.Categorical(out["h"], list(HORIZONS))
        out = out.sort_values(["tier", "session", "h"]).reset_index(drop=True)
    return out
