"""Derived NDJSON -> Parquet (Phase 3 Task 5 Step 2).

Usage: python convert.py --derived ../data/derived/12302019 --out ../data/parquet --date 12302019

One Parquet file per table under <out>/<table>/date=<date>/data.parquet (Hive partitioning on date), rows ordered by
(sym, ts) so a symbol's rows are contiguous and row-group statistics let DuckDB skip the rest. DuckDB streams the NDJSON,
so the 10 GB bbo file never has to fit in memory. Missing tables (episodes on a run without probes) are skipped.
"""
import argparse
import pathlib

import duckdb

TABLES = {
    "bbo": ({"ts": "BIGINT", "sym": "VARCHAR", "bid": "INTEGER", "bidSh": "INTEGER", "ask": "INTEGER", "askSh": "INTEGER"}, "sym, ts"),
    "executions": ({"ts": "BIGINT", "sym": "VARCHAR", "ref": "BIGINT", "side": "VARCHAR", "shares": "INTEGER", "price": "INTEGER",
                    "match": "BIGINT", "printable": "BOOLEAN"}, "sym, ts"),
    "trades": ({"ts": "BIGINT", "sym": "VARCHAR", "kind": "VARCHAR", "shares": "BIGINT", "price": "INTEGER", "match": "BIGINT",
                "cross": "VARCHAR"}, "sym, ts"),
    "episodes": ({"t0": "BIGINT", "sym": "VARCHAR", "side": "VARCHAR", "price": "INTEGER", "ahead0": "BIGINT", "orders0": "INTEGER",
                  "fillTs": "BIGINT", "censor": "VARCHAR", "execAhead": "BIGINT", "cancelAhead": "BIGINT", "anomaly": "INTEGER",
                  "endTs": "BIGINT"}, "sym, t0"),
    "daily": ({"sym": "VARCHAR", "adds": "BIGINT", "execs": "BIGINT", "cancels": "BIGINT", "volEC": "BIGINT", "volP": "BIGINT",
               "volQ": "BIGINT", "liveAtC": "BIGINT"}, "sym"),
    "priority": ({"ts": "BIGINT", "sym": "VARCHAR", "ref": "BIGINT", "side": "VARCHAR", "execShares": "INTEGER", "orderShares": "INTEGER",
                  "orderPrice": "INTEGER", "bestPrice": "INTEGER", "isHead": "BOOLEAN", "headRef": "BIGINT", "queuePos": "INTEGER",
                  "levelCount": "INTEGER", "levelShares": "INTEGER", "state": "VARCHAR", "nsSinceLastExec": "BIGINT",
                  "nsSinceLastChange": "BIGINT"}, "sym, ts"),
}


def _columns(spec: dict) -> str:
    return "{" + ", ".join(f"'{k}': '{v}'" for k, v in spec.items()) + "}"


def convert(derived: pathlib.Path, out: pathlib.Path, date: str, memory: str = "6GB", threads: int = 6, tables=None) -> dict:
    """Returns {table: rows written}. `tables` restricts the run (None = every table whose NDJSON exists)."""
    con = duckdb.connect()
    tmp = out / ".tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    con.execute(f"SET memory_limit='{memory}'")
    con.execute(f"SET threads={threads}")
    con.execute(f"SET temp_directory='{tmp.as_posix()}'")
    con.execute("SET preserve_insertion_order=false")
    written = {}
    for table, (spec, order) in TABLES.items():
        if tables is not None and table not in tables:
            continue
        src = derived / f"{table}.ndjson"
        if not src.exists():
            continue
        dst = out / table / f"date={date}"
        dst.mkdir(parents=True, exist_ok=True)
        target = dst / "data.parquet"
        cols = ", ".join(f'"{c}"' for c in spec)
        source = f"read_ndjson('{src.as_posix()}', columns={_columns(spec)}, format='newline_delimited')"
        if table == "bbo":
            # Several BBO lines can share one nanosecond (one message's book change reported after each step, or several
            # messages at the same ns). Keep the last one in file order — the state after everything at that instant — so
            # (sym, ts) is unique and every ASOF join downstream is deterministic. row_number() over () follows file order
            # only while insertion order is preserved.
            con.execute("SET preserve_insertion_order=true")
            select = (f"SELECT {cols} FROM (SELECT *, row_number() OVER () AS seq FROM {source}) "
                      f"QUALIFY row_number() OVER (PARTITION BY sym, ts ORDER BY seq DESC) = 1")
        else:
            select = f"SELECT {cols} FROM {source}"
        con.execute(f"COPY ({select} ORDER BY {order}) TO '{target.as_posix()}' (FORMAT PARQUET, COMPRESSION ZSTD, ROW_GROUP_SIZE 1048576)")
        con.execute("SET preserve_insertion_order=false")
        written[table] = con.execute(f"SELECT count(*) FROM read_parquet('{target.as_posix()}')").fetchone()[0]
    con.close()
    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--derived", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--date", required=True)
    ap.add_argument("--memory", default="6GB")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--tables", default=None, help="comma-separated subset of tables to convert")
    a = ap.parse_args()
    tables = a.tables.split(",") if a.tables else None
    for table, n in convert(pathlib.Path(a.derived), pathlib.Path(a.out), a.date, a.memory, a.threads, tables).items():
        print(f"{table}: {n:,} rows", flush=True)


if __name__ == "__main__":
    main()
