import json
import pathlib
import sys

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

NS = 1_000_000_000
OPEN = 34_200 * NS


def write_ndjson(path: pathlib.Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="ascii") as f:
        for r in rows:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")


@pytest.fixture
def derived(tmp_path):
    """A tiny derived directory: one symbol, one execution, a quote before and after, and one daily line."""
    d = tmp_path / "derived"
    write_ndjson(d / "bbo.ndjson", [
        {"ts": OPEN + 100, "sym": "TEST", "bid": 1000000, "bidSh": 300, "ask": 1000200, "askSh": 200},
        {"ts": OPEN + 200, "sym": "TEST", "bid": 1000000, "bidSh": 300, "ask": 1000200, "askSh": 100},   # the execution's own update
        {"ts": OPEN + 200 + NS, "sym": "TEST", "bid": 1000200, "bidSh": 50, "ask": 1000400, "askSh": 70},
    ])
    write_ndjson(d / "executions.ndjson", [
        {"ts": OPEN + 200, "sym": "TEST", "ref": 7, "side": "S", "shares": 100, "price": 1000200, "match": 1, "printable": True},
        {"ts": OPEN + 250, "sym": "TEST", "ref": 8, "side": "B", "shares": 5, "price": 1000000, "match": 2, "printable": False},
    ])
    write_ndjson(d / "trades.ndjson", [
        {"ts": OPEN + 300, "sym": "TEST", "kind": "P", "shares": 10, "price": 1000100, "match": 3, "cross": ""},
        {"ts": OPEN + 400, "sym": "TEST", "kind": "Q", "shares": 1000, "price": 1000100, "match": 4, "cross": "O"},
        {"ts": OPEN + 500, "sym": "TEST", "kind": "P", "shares": 20, "price": 1000100, "match": 5, "cross": ""},
    ])
    write_ndjson(d / "episodes.ndjson", [
        {"t0": OPEN + 100, "sym": "TEST", "side": "B", "price": 1000000, "ahead0": 300, "orders0": 2, "fillTs": OPEN + 100 + 2 * NS,
         "censor": "none", "execAhead": 200, "cancelAhead": 100, "anomaly": 0, "endTs": OPEN + 100 + 2 * NS},
        {"t0": OPEN + 100, "sym": "TEST", "side": "S", "price": 1000200, "ahead0": 200, "orders0": 1, "fillTs": None,
         "censor": "moved", "execAhead": 0, "cancelAhead": 0, "anomaly": 0, "endTs": OPEN + 100 + 3 * NS},
        {"t0": OPEN + 100 + NS, "sym": "TEST", "side": "B", "price": 1000000, "ahead0": 100, "orders0": 1, "fillTs": None,
         "censor": "exhausted", "execAhead": 100, "cancelAhead": 0, "anomaly": 0, "endTs": OPEN + 100 + 4 * NS},
    ])
    write_ndjson(d / "daily.ndjson", [
        {"sym": "TEST", "adds": 10, "execs": 2, "cancels": 8, "volEC": 100, "volP": 30, "volQ": 1000, "liveAtC": 0},
        {"sym": "ZJZZT", "adds": 1, "execs": 0, "cancels": 1, "volEC": 999999, "volP": 0, "volQ": 0, "liveAtC": 0},
        {"sym": "THIN", "adds": 1, "execs": 0, "cancels": 1, "volEC": 0, "volP": 0, "volQ": 0, "liveAtC": 0},
    ])
    return d
