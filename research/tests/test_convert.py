import pyarrow.parquet as pq

import convert
import db


def test_ndjson_to_parquet_types_and_counts(derived, tmp_path):
    out = tmp_path / "parquet"
    n = convert.convert(derived, out, "12302019", memory="1GB", threads=2)
    assert n == {"bbo": 3, "executions": 2, "trades": 3, "episodes": 3, "daily": 3}
    bbo = pq.read_table(out / "bbo" / "date=12302019" / "data.parquet")
    assert [str(t) for t in bbo.schema.types] == ["int64", "string", "int32", "int32", "int32", "int32"]
    ex = pq.read_table(out / "executions" / "date=12302019" / "data.parquet").to_pandas()
    assert ex["printable"].dtype == bool and list(ex["ts"]) == sorted(ex["ts"])
    ep = pq.read_table(out / "episodes" / "date=12302019" / "data.parquet").to_pandas()
    assert ep["fillTs"].isna().sum() == 2 and ep["fillTs"].dtype.kind in "fi"


def test_bbo_keeps_the_last_state_per_nanosecond(derived, tmp_path):
    from conftest import OPEN, write_ndjson
    write_ndjson(derived / "bbo.ndjson", [
        {"ts": OPEN + 100, "sym": "TEST", "bid": 1000000, "bidSh": 300, "ask": 1000200, "askSh": 200},
        {"ts": OPEN + 100, "sym": "TEST", "bid": 1000000, "bidSh": 300, "ask": 1000200, "askSh": 150},   # same ns, later in the file
        {"ts": OPEN + 100, "sym": "OTHER", "bid": 500000, "bidSh": 1, "ask": 500100, "askSh": 1},
        {"ts": OPEN + 100, "sym": "TEST", "bid": 1000000, "bidSh": 300, "ask": 1000200, "askSh": 120},   # the state after everything at that ns
        {"ts": OPEN + 50, "sym": "TEST", "bid": 999900, "bidSh": 10, "ask": 1000300, "askSh": 10},
    ])
    out = tmp_path / "parquet"
    assert convert.convert(derived, out, "d", memory="1GB", threads=2, tables=["bbo"]) == {"bbo": 3}
    rows = pq.read_table(out / "bbo" / "date=d" / "data.parquet").to_pandas()
    assert rows[["sym", "ts", "askSh"]].values.tolist() == [["OTHER", OPEN + 100, 1], ["TEST", OPEN + 50, 10], ["TEST", OPEN + 100, 120]]


def test_views_and_tiers(derived, tmp_path):
    out = tmp_path / "parquet"
    convert.convert(derived, out, "12302019", memory="1GB", threads=2)
    con = db.connect(out, "12302019", memory="1GB", threads=2)
    assert con.execute("select count(*) from bbo").fetchone()[0] == 3
    assert con.execute("select count(*) from exec_signed").fetchone()[0] == 1          # non-printable C excluded
    tiers = con.execute("select sym, rank, tier from daily_tier order by rank").fetchall()
    assert tiers == [("TEST", 1, "top100"), ("THIN", 2, "top100")]                    # test symbol ZJZZT excluded
    assert con.execute("select session(34200000000000), session(36000000000000), session(57599999999999)").fetchone() == ("open", "midday", "close")
    assert db.symbol_batches(con, 1).__next__() == ["TEST"]
