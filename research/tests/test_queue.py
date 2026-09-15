import pandas as pd
import pytest

import queues as q
from conftest import NS, OPEN


def episodes():
    rows = [
        # filled at 2 s
        dict(t0=OPEN + 100, sym="A", side="B", price=1, ahead0=300, orders0=2, fillTs=OPEN + 100 + 2 * NS, censor="none", execAhead=200, cancelAhead=100, anomaly=0, endTs=OPEN + 100 + 2 * NS),
        # filled at 10 s, anomaly
        dict(t0=OPEN + 100, sym="A", side="S", price=2, ahead0=50, orders0=1, fillTs=OPEN + 100 + 10 * NS, censor="none", execAhead=0, cancelAhead=0, anomaly=1, endTs=OPEN + 100 + 10 * NS),
        # exhausted at 3 s: upper bound counts it from T = 5 s
        dict(t0=OPEN + 100, sym="B", side="B", price=3, ahead0=100, orders0=1, fillTs=None, censor="exhausted", execAhead=100, cancelAhead=0, anomaly=0, endTs=OPEN + 100 + 3 * NS),
        # moved at 1 s: never a fill
        dict(t0=OPEN + 100, sym="B", side="S", price=4, ahead0=1000, orders0=5, fillTs=None, censor="moved", execAhead=0, cancelAhead=500, anomaly=0, endTs=OPEN + 100 + NS),
        # midday, censored at 60 s
        dict(t0=40_000 * NS, sym="A", side="B", price=5, ahead0=10, orders0=1, fillTs=None, censor="T", execAhead=0, cancelAhead=0, anomaly=0, endTs=40_060 * NS),
        # unknown symbol: dropped by enrich
        dict(t0=OPEN + 100, sym="ZZ", side="B", price=6, ahead0=1, orders0=1, fillTs=None, censor="eod", execAhead=0, cancelAhead=0, anomaly=0, endTs=OPEN + 200),
    ]
    return pd.DataFrame(rows)


def test_summary_bounds_and_rates():
    e = q.enrich(episodes(), {"A": "top100", "B": "rest"})
    assert len(e) == 5 and list(e["session"].astype(str)) == ["open", "open", "open", "open", "midday"]
    s = q.summary(e)
    assert list(s["T_s"]) == [1, 5, 30, 60]
    assert list(s["p_fill"]) == pytest.approx([0, 1 / 5, 2 / 5, 2 / 5])
    assert list(s["p_fill_upper"]) == pytest.approx([0, 2 / 5, 3 / 5, 3 / 5])
    assert s.attrs["cancel_share"] == pytest.approx(600 / 900)
    assert s.attrs["anomaly"] == pytest.approx(1 / 5) and s.attrs["censor_exhausted"] == pytest.approx(1 / 5)
    assert s.attrs["censor_T"] == pytest.approx(1 / 5) and s.attrs["filled"] == pytest.approx(2 / 5)


def test_by_group_and_conditional():
    e = q.enrich(episodes(), {"A": "top100", "B": "rest"})
    g = q.by_group(e, keys=("tier", "side"))
    top_b = g[(g["tier"] == "top100") & (g["side"] == "B")].iloc[0]
    assert top_b["n"] == 2 and top_b["p_fill_5s"] == pytest.approx(0.5) and top_b["p_fill_60s"] == pytest.approx(0.5)
    c = q.conditional(e, T=5, q=2)
    assert list(c["tier"].astype(str)) == ["top100", "top100", "rest", "rest"]
    assert list(c["decile"]) == [1, 2, 1, 2]
    rest = c[c["tier"] == "rest"].set_index("decile")
    assert rest.loc[1, "p_fill"] == 0 and rest.loc[1, "p_fill_upper"] == 1      # the exhausted 100-share queue
    assert rest.loc[2, "p_fill_upper"] == 0                                       # the moved 1000-share queue
