# Priority-violation classification — S120825

Violations classified: **33,674** of **33,674** (all of them)

| bucket | count | share | median queuePos | median levelCount | median burstSize | median execShares |
|---|---|---|---|---|---|---|
| burst | 32,531 | 96.6 % | 3 | 154 | 117 | 10 |
| isolated | 1,101 | 3.3 % | 2 | 13 | 1 | 57 |
| off_best | 42 | 0.1 % | 0 | 3 | 8 | 29 |
| gate_bug | 0 | 0.0 % | — | — | — | — |

## Detail

- burst: 92.0 % have an execution on the same symbol at the previous message's nanosecond (nsSinceLastExec == 0); queuePos 1 in 15.5 % of cases, <= 3 in 50.1 %.
- isolated: 59.9 % are odd-lot executions (< 100 shares); median nsSinceLastExec 159,042 ns; top symbols: WVE (114), NVDA (59), IBIT (53), TLT (37), TTSH (31).
- off_best: order sits 3603 cents (median) behind the best displayed level, max 3828; 66.7 % odd lots; top symbols: SMX (22), IBIO (15), VIRC (3), CETX (1), TWG (1).

## Top symbols overall

| sym   |   violations |
|:------|-------------:|
| WULF  |         7429 |
| NFLX  |         3656 |
| NVDA  |         3566 |
| GOOGL |         1106 |
| TSLA  |          834 |
| AMZN  |          769 |
| COST  |          688 |
| GOOG  |          684 |
| AVGO  |          676 |
| INTC  |          571 |