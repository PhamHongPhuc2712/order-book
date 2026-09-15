"""DuckDB over the Parquet tables plus the metric views in metrics.sql (Phase 3 Task 5 Step 3)."""
import pathlib

import duckdb

from convert import TABLES

HERE = pathlib.Path(__file__).resolve().parent
NS = 1_000_000_000
OPEN, CLOSE = 34_200 * NS, 57_600 * NS
SESSIONS = {"open": (34_200 * NS, 36_000 * NS), "midday": (36_000 * NS, 55_800 * NS), "close": (55_800 * NS, 57_600 * NS)}
TIERS = ("top100", "101-1000", "rest")


def connect(parquet_root: pathlib.Path, date: str, memory: str = "6GB", threads: int = 6) -> duckdb.DuckDBPyConnection:
    con = duckdb.connect()
    con.execute(f"SET memory_limit='{memory}'")
    con.execute(f"SET threads={threads}")
    tmp = parquet_root / ".tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    con.execute(f"SET temp_directory='{tmp.as_posix()}'")
    for table, (spec, _) in TABLES.items():
        f = parquet_root / table / f"date={date}" / "data.parquet"
        if f.exists():
            con.execute(f"create or replace view {table} as select * from read_parquet('{f.as_posix()}')")
        else:                                            # empty, typed placeholder so every metric view resolves
            cols = ", ".join(f'NULL::{t} as "{c}"' for c, t in spec.items())
            con.execute(f"create or replace view {table} as select {cols} where false")
    con.execute((HERE / "metrics.sql").read_text(encoding="utf-8"))
    return con


def has_table(con: duckdb.DuckDBPyConnection, name: str) -> bool:
    """True if the table has rows (missing tables are empty placeholders)."""
    return con.execute(f"select count(*) from (select * from {name} limit 1)").fetchone()[0] > 0


def select_symbols(con: duckdb.DuckDBPyConnection, symbols) -> None:
    """Restrict bbo_sel / exec_sel to a literal symbol list (None = everything)."""
    if symbols is None:
        con.execute("create or replace view bbo_sel as select * from bbo_mid")
        con.execute("create or replace view exec_sel as select * from exec_signed")
        return
    lit = ", ".join("'" + s.replace("'", "''") + "'" for s in symbols)
    con.execute(f"create or replace view bbo_sel as select * from bbo_mid where sym in ({lit})")
    con.execute(f"create or replace view exec_sel as select * from exec_signed where sym in ({lit})")


def symbol_batches(con: duckdb.DuckDBPyConnection, size: int = 250):
    syms = [r[0] for r in con.execute("select sym from daily_tier order by sym").fetchall()]
    for i in range(0, len(syms), size):
        yield syms[i:i + size]


def tier_map(con: duckdb.DuckDBPyConnection) -> dict:
    return dict(con.execute("select sym, tier from daily_tier").fetchall())
