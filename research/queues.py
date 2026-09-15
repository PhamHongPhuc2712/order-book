"""Queue-episode aggregation (Phase 3 Task 5 Step 5). Episodes are computed in Java (QueueProbe); this only aggregates.

An episode is a hypothetical joiner at the back of the touch. Outcomes: `none` = filled at fillTs; `moved` = the side's
best price left the joiner's price; `exhausted` = every order ahead executed or cancelled and the level then emptied, so
no execution at that price could reveal the fill (the joiner would have been alone at the touch); `T` = censored at 60 s;
`eod` = market close. P(fill <= T) is reported as a range: the lower bound counts only observed fills, the upper bound
also counts `exhausted` episodes at their exhaustion time.
"""
import numpy as np
import pandas as pd

import db

T_LIST = (1, 5, 30, 60)


def enrich(ep: pd.DataFrame, tiers: dict) -> pd.DataFrame:
    e = ep.copy()
    e["tier"] = e["sym"].map(tiers)
    e = e.dropna(subset=["tier"])
    e["session"] = pd.cut(e["t0"], [db.SESSIONS["open"][0], db.SESSIONS["midday"][0], db.SESSIONS["close"][0], db.SESSIONS["close"][1]],
                          right=False, labels=list(db.SESSIONS))
    e = e.dropna(subset=["session"])
    e["dt"] = (e["fillTs"] - e["t0"]) / db.NS
    e["dtEnd"] = (e["endTs"] - e["t0"]) / db.NS
    e["tier"] = pd.Categorical(e["tier"], db.TIERS)
    return e


def summary(ep: pd.DataFrame, T_list=T_LIST) -> pd.DataFrame:
    """P(fill <= T) lower (observed fills) and upper (fills + exhausted) bounds; attrs carry the episode-level rates."""
    filled = ep["fillTs"].notna()
    exhausted = ep["censor"] == "exhausted"
    n = len(ep)
    rows = []
    for T in T_list:
        lo = (filled & (ep["dt"] <= T)).sum()
        up = lo + (exhausted & (ep["dtEnd"] <= T)).sum()
        rows.append({"T_s": T, "p_fill": lo / n if n else np.nan, "p_fill_upper": up / n if n else np.nan, "n": n})
    s = pd.DataFrame(rows)
    tot = ep["execAhead"].sum() + ep["cancelAhead"].sum()
    s.attrs.update(
        n=n,
        cancel_share=float(ep["cancelAhead"].sum() / tot) if tot else float("nan"),
        filled=float(filled.mean()) if n else float("nan"),
        anomaly=float(ep["anomaly"].mean()) if n else float("nan"),
        median_ahead0=float(ep["ahead0"].median()) if n else float("nan"),
        median_orders0=float(ep["orders0"].median()) if n else float("nan"),
        **{f"censor_{k}": float((ep["censor"] == k).mean()) if n else float("nan") for k in ("moved", "exhausted", "T", "eod")},
    )
    return s


def by_group(ep: pd.DataFrame, keys=("tier", "session", "side"), T_list=T_LIST) -> pd.DataFrame:
    rows = []
    for key, g in ep.groupby(list(keys), observed=True):
        s = summary(g, T_list)
        base = dict(zip(keys, key))
        base.update({k: v for k, v in s.attrs.items()})
        for _, r in s.iterrows():
            base[f"p_fill_{int(r.T_s)}s"] = r.p_fill
            base[f"p_fill_upper_{int(r.T_s)}s"] = r.p_fill_upper
        rows.append(base)
    return pd.DataFrame(rows)


def conditional(ep: pd.DataFrame, T: float = 5, q: int = 10) -> pd.DataFrame:
    """P(fill <= T | ahead0 decile within tier): the headline curve. Deciles by rank so ties (e.g. many 100-share queues) split evenly."""
    rows = []
    for tier, g in ep.groupby("tier", observed=True):
        if len(g) < q:
            continue
        r = g["ahead0"].rank(method="first")
        bucket = pd.qcut(r, q, labels=False) + 1
        for b, gb in g.groupby(bucket):
            filled = gb["fillTs"].notna() & (gb["dt"] <= T)
            upper = filled | ((gb["censor"] == "exhausted") & (gb["dtEnd"] <= T))
            rows.append({"tier": tier, "decile": int(b), "n": len(gb), "ahead0_min": int(gb["ahead0"].min()),
                         "ahead0_median": float(gb["ahead0"].median()), "ahead0_max": int(gb["ahead0"].max()),
                         "p_fill": float(filled.mean()), "p_fill_upper": float(upper.mean()),
                         "cancel_share": float(gb["cancelAhead"].sum() / max(gb["cancelAhead"].sum() + gb["execAhead"].sum(), 1))})
    out = pd.DataFrame(rows)
    if len(out):
        out["tier"] = pd.Categorical(out["tier"], db.TIERS)
        out = out.sort_values(["tier", "decile"]).reset_index(drop=True)
    return out
