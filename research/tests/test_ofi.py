import numpy as np
import pytest
from hypothesis import given, settings, strategies as st

import convert
import db
import ofi
from conftest import NS, OPEN, write_ndjson


def test_ofi_windows_from_sql(tmp_path):
    d = tmp_path / "derived"
    write_ndjson(d / "daily.ndjson", [{"sym": "T", "adds": 1, "execs": 1, "cancels": 0, "volEC": 1, "volP": 0, "volQ": 0, "liveAtC": 0}])
    write_ndjson(d / "bbo.ndjson", [
        {"ts": OPEN + int(0.1 * NS), "sym": "T", "bid": 1000000, "bidSh": 100, "ask": 1000200, "askSh": 100},   # first quote: no e_n
        {"ts": OPEN + int(0.5 * NS), "sym": "T", "bid": 1000000, "bidSh": 150, "ask": 1000200, "askSh": 100},   # e = +150 -100 -100 +100 = 50
        {"ts": OPEN + int(1.2 * NS), "sym": "T", "bid": 1000100, "bidSh": 100, "ask": 1000300, "askSh": 100},   # e = +100 + 0 - 0 + 100 = 200, mid +1 tick
        {"ts": OPEN + int(1.7 * NS), "sym": "T", "bid": 1000100, "bidSh": 100, "ask": 1000300, "askSh": 40},    # e = +100 -100 -40 +100 = 60
        {"ts": OPEN + int(3.0 * NS), "sym": "T", "bid": 1000000, "bidSh": 100, "ask": 1000300, "askSh": 40},    # e = 0 -100 -40 +40 = -100, mid -0.5 tick
    ])
    out = tmp_path / "parquet"
    convert.convert(d, out, "x", memory="1GB", threads=2)
    con = db.connect(out, "x", memory="1GB", threads=2)
    w = con.execute("select win - 34200, ofi, dmid from ofi_windows() order by win").fetchall()
    assert w == [(0, 50, None), (1, 260, 1.0), (3, -100, -0.5)]     # window 2 is empty and absent


def test_regression_sums_and_fit_agree_with_numpy():
    rng = np.random.default_rng(1)
    x = rng.normal(size=200); y = 0.5 + 2.0 * x + rng.normal(scale=0.1, size=200)
    a, b, r2 = ofi.fit_from_sums(len(x), x.sum(), y.sum(), (x * x).sum(), (x * y).sum(), (y * y).sum())
    bb, aa = np.polyfit(x, y, 1)
    assert a == pytest.approx(aa) and b == pytest.approx(bb)
    assert r2 == pytest.approx(np.corrcoef(x, y)[0, 1] ** 2)
    # out-of-sample on the same data equals in-sample R^2 for an OLS fit
    assert ofi.oos_r2(a, b, len(x), x.sum(), y.sum(), (x * x).sum(), (x * y).sum(), (y * y).sum()) == pytest.approx(r2)


@settings(max_examples=50, deadline=None)
@given(st.lists(st.tuples(st.floats(-5, 5), st.floats(-5, 5)), min_size=3, max_size=40))
def test_fit_is_scale_consistent(pts):
    x = np.array([p[0] for p in pts]); y = np.array([p[1] for p in pts])
    if np.var(x) < 1e-9 or np.var(y) < 1e-9:
        return
    sums = (len(x), x.sum(), y.sum(), (x * x).sum(), (x * y).sum(), (y * y).sum())
    a, b, r2 = ofi.fit_from_sums(*sums)
    assert 0 <= r2 <= 1 + 1e-9
    # doubling y doubles beta and the intercept, leaves R^2 alone
    y2 = 2 * y
    a2, b2, r22 = ofi.fit_from_sums(len(x), x.sum(), y2.sum(), (x * x).sum(), (x * y2).sum(), (y2 * y2).sum())
    assert b2 == pytest.approx(2 * b, rel=1e-6, abs=1e-9) and r22 == pytest.approx(r2, abs=1e-9)
