-- Metric definitions over the Parquet views (04 Reference/Metrics Definitions). Prices at native scale 4; ts ns since midnight.
-- Sessions: open [9:30, 10:00), midday [10:00, 15:30), close [15:30, 16:00). One tick = 100 (one cent).

create or replace macro in_market(ts) as ts >= 34200000000000 and ts < 57600000000000;
create or replace macro session(ts) as
  case when ts < 36000000000000 then 'open' when ts < 55800000000000 then 'midday' else 'close' end;
create or replace macro session_start(s) as
  case when s = 'open' then 34200000000000 when s = 'midday' then 36000000000000 else 55800000000000 end;
create or replace macro session_end(s) as
  case when s = 'open' then 36000000000000 when s = 'midday' then 55800000000000 else 57600000000000 end;

-- mid in price units; only a proper two-sided, uncrossed quote defines one, and a stub quote (relative spread >= 100 %,
-- e.g. bid $0.01 against an ask near the $200,000 maximum) is no quote at all: 2,923 such rows on 318 symbols on 12302019.
create or replace view bbo_mid as
select sym, ts, bid, bidSh, ask, askSh, (bid + ask) / 2.0 as mid
from bbo where bid > 0 and ask > 0 and bid < ask and ask < 3 * bid;

-- signed executions (D23): the resting side is what the feed reports; resting 'B' means a sell aggressor (d = -1),
-- resting 'S' a buy aggressor (d = +1). E and printable C only, market hours only. P and Q are unsigned and excluded.
create or replace view exec_signed as
select sym, ts, ref, shares, price, match, case when side = 'B' then -1 else 1 end as d
from executions where printable and in_market(ts);

-- liquidity tier by that day's E + printable C + P share volume; NASDAQ test symbols (Z?ZZT) excluded
create or replace view daily_tier as
select sym, vol, rnk as rank,
       case when rnk <= 100 then 'top100' when rnk <= 1000 then '101-1000' else 'rest' end as tier
from (select sym, volEC + volP as vol, row_number() over (order by volEC + volP desc, sym) as rnk
      from daily where sym not similar to 'Z.ZZT');

-- Symbol selection for batched queries: spreads.py / ofi.py replace these views with a literal `where sym in (...)`
-- so DuckDB's Parquet scan skips row groups; the defaults select everything.
create or replace view bbo_sel as select * from bbo_mid;
create or replace view exec_sel as select * from exec_signed;

-- effective / realised / impact spread in bps for every signed execution, at horizon h (ns).
-- mid0: the last quote strictly before the execution's timestamp (the execution's own BBO update shares its ts).
-- midh: the last quote at or before ts + h.
create or replace macro eff_real(h) as table
with t as (
  select e.sym, e.ts, e.shares, e.price, e.d, b0.mid as mid0
  from exec_sel e asof join bbo_sel b0 on b0.sym = e.sym and b0.ts < e.ts
), th as (
  select t.*, bh.mid as midh from t asof join bbo_sel bh on bh.sym = t.sym and bh.ts <= t.ts + h
)
select sym, ts, shares, d,
       2.0 * d * (price - mid0) / mid0 * 1e4 as eff_bps,
       2.0 * d * (price - midh) / mid0 * 1e4 as real_bps,
       2.0 * d * (midh - mid0)  / mid0 * 1e4 as impact_bps
from th;

-- OFI (Cont, Kukanov & Stoikov 2014): e_n per quote update, summed over 1-second windows; dmid in ticks between the last
-- mid of consecutive non-empty windows of the same symbol. Market hours only.
create or replace macro ofi_windows() as table
with b as (
  select sym, ts, bid, bidSh, ask, askSh, mid from bbo_sel where in_market(ts)
), l as (
  select *, lag(bid) over w as pb, lag(bidSh) over w as pbs, lag(ask) over w as pa, lag(askSh) over w as pas
  from b window w as (partition by sym order by ts)
), e as (
  select sym, ts, mid, ts // 1000000000 as win,
         (case when bid >= pb then bidSh else 0 end) - (case when bid <= pb then pbs else 0 end)
       - (case when ask <= pa then askSh else 0 end) + (case when ask >= pa then pas else 0 end) as e
  from l where pb is not null
), w as (
  select sym, win, sum(e) as ofi, last(mid order by ts) as mid_end from e group by sym, win
)
select sym, win, ofi, (mid_end - lag(mid_end) over (partition by sym order by win)) / 100.0 as dmid from w;
