import json

import pytest

import crossday


@pytest.mark.parametrize("text,want", [
    ("768.144", 768144.0),      # Replay under a comma-decimal locale: '.' groups thousands
    ("551,0", 551.0),
    ("5,9", 5.9),
    ("1.924.078", 1924078.0),
    ("1,924,078", 1924078.0),   # the same run under an English locale
    ("156.8", 156.8),           # one decimal place, not a group of three
    ("0", 0.0),
])
def test_num_parses_either_locale(text, want):
    assert crossday.num(text) == want


def day(root, name, *, utf16=False, priority=True, results=True):
    """Write the handful of files crossday.py reads for one day."""
    d = root / "derived" / name
    d.mkdir(parents=True, exist_ok=True)
    enc = "utf-16" if utf16 else "utf-8"
    (d / "probe.txt").write_text("frames=100 bytes=4000 mismatch=0 firstSystemEvent=O\n", encoding=enc)
    (d / "replay.txt").write_text(
        "config=mmap/long/array/pool/dedupe messages=100 wall=2,5s msgs/s=40.000 alloc=5,9 B/msg live=0 poolCreated=7\n",
        encoding=enc)
    (d / "validation.json").write_text(json.dumps({
        "badLength": 0, "unknownType": 0, "duplicateRef": 0, "unknownRef": 0, "execExceeds": 0, "cancelExceeds": 0,
        "crossedInMarket": 0, "crossedAtResume": 3, "priorityChecked": 1000, "priorityViolations": 2,
        "duplicateMatch": 0, "twoSidedMatches": 4, "brokenUnknown": 0, "liveAtC": 0}), encoding="utf-8")
    (d / "daily.ndjson").write_text('{"sym":"A"}\n{"sym":"B"}\n', encoding="utf-8")
    (d / "probe_symbols.txt").write_text("A,B\n", encoding="utf-8")
    if priority:
        out = root / "out"
        out.mkdir(parents=True, exist_ok=True)
        (out / f"priority_{name}.md").write_text(
            "| bucket | count | share |\n|---|---|---|\n"
            "| burst | 419 | 83.8 % | 3 |\n| isolated | 81 | 16.2 % | 1 |\n"
            "| off_best | 0 | 0.0 % | — |\n| gate_bug | 0 | 0.0 % | — |\n", encoding="utf-8")
    if results:
        r = root / "results"
        r.mkdir(parents=True, exist_ok=True)
        (r / f"numbers_{name}.md").write_text("```\nmarks ours=391 meatpy=391 joined=391 only-one-side=0 differing=0\n```\n",
                                              encoding="utf-8")
        (r / f"queue_tier_session_{name}.csv").write_text(
            "tier,session,n,cancel_share,anomaly,median_ahead0,p_fill_5s,p_fill_upper_5s,p_fill_60s,p_fill_upper_60s\n"
            "top100,midday,1000,0.88,0.0,1970,0.032,0.24,0.128,0.411\n", encoding="utf-8")
        (r / f"queue_cond_{name}.csv").write_text(
            "tier,decile,n,p_fill,p_fill_upper\ntop100,1,10,0.04,0.506\ntop100,10,10,0.005,0.012\n", encoding="utf-8")
        (r / f"spreads_{name}.csv").write_text(
            "tier,session,h,eff,real\ntop100,midday,30s,23.44,-15.64\n", encoding="utf-8")
        (r / f"ofi_{name}.csv").write_text(
            "tier,session,beta_med,r2_out_med\ntop100,midday,0.024,0.483\n", encoding="utf-8")
    return d


def test_build_reads_every_stage(tmp_path, monkeypatch):
    monkeypatch.setattr(crossday, "HERE", tmp_path)          # out/priority_<day>.md lives under HERE
    day(tmp_path, "D1")
    text = crossday.build(["D1"], tmp_path / "derived", tmp_path / "results")
    assert "| messages | 100 |" in text
    assert "| peak live orders (`poolCreated`) | 7 |" in text
    assert "| msgs/s (with derived output) | 40,000 |" in text          # grouped, not 40.0
    assert "| structural violations (six counters) | 0 |" in text
    assert "2 / 1,000 = **0.200 %**" in text
    assert "burst 83.8 %, isolated 16.2 %, off-best 0, gate 0" in text
    assert "differing=0" in text
    assert "3.2 % – 24.0 %" in text                                     # P(fill <= 5 s) band, top100 midday
    assert "| P(fill ≤ 5 s), smallest ahead0 decile (upper) | 50.6% |" in text
    assert "| realised spread at 30 s (bps) | -15.6 |" in text
    assert "| OFI out-of-sample R², median symbol | 0.483 |" in text


def test_utf16_logs_are_read(tmp_path, monkeypatch):
    """PowerShell's Tee-Object wrote the first day's logs as UTF-16LE; they must still parse."""
    monkeypatch.setattr(crossday, "HERE", tmp_path)
    day(tmp_path, "D1", utf16=True)
    text = crossday.build(["D1"], tmp_path / "derived", tmp_path / "results")
    assert "| messages | 100 |" in text
    assert "| framing mismatches (`Probe`) | 0 |" in text


def test_missing_day_is_a_dash_not_a_crash(tmp_path, monkeypatch):
    monkeypatch.setattr(crossday, "HERE", tmp_path)
    day(tmp_path, "D1")
    text = crossday.build(["D1", "D2"], tmp_path / "derived", tmp_path / "results")
    assert "| crossday.md — D1 · D2" not in text                        # title line has no leading pipe
    assert "D2" in text.splitlines()[0]
    assert "| messages | 100 | — |" in text
