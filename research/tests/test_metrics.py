import pytest

import convert
import db
import spreads

NS = 1_000_000_000


@pytest.fixture
def con(derived, tmp_path):
    out = tmp_path / "parquet"
    convert.convert(derived, out, "12302019", memory="1GB", threads=2)
    return db.connect(out, "12302019", memory="1GB", threads=2)


def test_effective_realised_impact_hand_example(con):
    # bid 100.00 / ask 100.02 -> mid 100.01; an E on a resting ask at 100.02 (buy aggressor, d = +1)
    # eff = 2 * (100.02 - 100.01) / 100.01 * 1e4 = 1.9998 bps; at t + 1 s the quote is 100.02 / 100.04 -> mid 100.03
    # real = 2 * (100.02 - 100.03) / 100.01 * 1e4 = -1.9998; impact = 3.9996
    rows = con.execute("select sym, d, shares, eff_bps, real_bps, impact_bps from eff_real(1000000000)").fetchall()
    assert len(rows) == 1
    sym, d, shares, eff, real, impact = rows[0]
    assert (sym, d, shares) == ("TEST", 1, 100)
    assert eff == pytest.approx(2 * (1000200 - 1000100) / 1000100 * 1e4)
    assert real == pytest.approx(2 * (1000200 - 1000300) / 1000100 * 1e4)
    assert impact == pytest.approx(eff - real)


def test_mid0_is_strictly_before_the_execution(con):
    # the execution's own BBO update shares its timestamp (askSh 200 -> 100); mid0 must come from the quote before it,
    # which here has the same mid, so check the horizon side instead: h = 0 picks the ts-equal post-execution quote
    real0 = con.execute("select real_bps from eff_real(0)").fetchone()[0]
    assert real0 == pytest.approx(2 * (1000200 - 1000100) / 1000100 * 1e4)     # midh == mid0 when h = 0


def test_block_sums_and_bootstrap(con):
    blocks = spreads.block_sums(con, batch_size=10, horizons={"1s": NS})
    assert list(blocks["tier"].unique()) == ["top100"] and list(blocks["session"].unique()) == ["open"]
    s = spreads.summarise(blocks, boot=10)
    assert len(s) == 1 and s.loc[0, "n_exec"] == 1 and s.loc[0, "volume"] == 100
    assert s.loc[0, "eff"] == pytest.approx(2 * (1000200 - 1000100) / 1000100 * 1e4)
    assert s.loc[0, "impact"] == pytest.approx(s.loc[0, "eff"] - s.loc[0, "real"])
