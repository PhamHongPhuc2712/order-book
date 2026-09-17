
### G1GC (`-Xms8g -Xmx8g -XX:+UseG1GC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 240.5 s | 1,117,442 (1.0x) | 3 | 156.8 | 400 | 900 | 2501 | 20111 | 25739263 |
| +mmap | 239.3 s | 1,123,045 (1.0x) | 3 | 156.8 | 300 | 600 | 1400 | 5603 | 32194559 |
| +long | 243.4 s | 1,104,128 (1.0x) | 3 | 102.3 | 300 | 600 | 1400 | 5603 | 21561343 |
| +array | 167.7 s | 1,602,533 (1.4x) | 3 | 34.7 | 200 | 700 | 1400 | 2801 | 22413311 |
| +pool | 158.7 s | 1,693,414 (1.5x) | 3 | 5.9 | 200 | 700 | 1400 | 2701 | 18612223 |
| +dedupe | 165.3 s | 1,625,800 (1.5x) | 3 | 5.9 | 201 | 700 | 1501 | 3201 | 19955711 |

Full-day run spread (s): naive: 294, 240, 229; +mmap: 237, 243, 239; +long: 243, 242, 244; +array: 168, 169, 164; +pool: 158, 159, 161; +dedupe: 171, 165, 162

### G1GC (`-Xms4g -Xmx4g -XX:+UseG1GC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 219.2 s | 1,226,025 (1.0x) | 1 | 156.8 | 300 | 700 | 1600 | 13407 | 26263551 |
| +dedupe | 166.4 s | 1,615,053 (1.3x) | 1 | 5.9 | 200 | 700 | 1400 | 2601 | 16891903 |

Full-day run spread (s): naive: 219; +dedupe: 166

### ZGC (non-generational, abandoned — see text) (`-Xms8g -Xmx8g -XX:+UseZGC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 276.8 s | 970,899 (1.0x) | 1 | 189.2 | 300 | 800 | 2701 | 6503 | 23363583 |
| +mmap | 359.1 s | 748,384 (0.8x) | 1 | 189.2 | 300 | 800 | 2501 | 6603 | 56950783 |
| +long | nan s | nan | 0 | 124.0 | 400 | 800 | 2901 | 7203 | 20430847 |

Full-day run spread (s): naive: 277; +mmap: 359

### Generational ZGC (`-Xms4g -Xmx4g -XX:+UseZGC -XX:+ZGenerational` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 329.3 s | 816,109 (1.0x) | 1 | 189.2 | 400 | 1100 | 3401 | 19215 | 53313535 |
| +mmap | 321.2 s | 836,690 (1.0x) | 1 | 189.2 | 400 | 1100 | 3601 | 9103 | 17956863 |
| +long | 341.1 s | 787,877 (1.0x) | 1 | 130.4 | 400 | 900 | 2601 | 7603 | 21315583 |
| +array | 194.5 s | 1,381,721 (1.7x) | 1 | 44.5 | 300 | 900 | 2801 | 7003 | 18104319 |
| +pool | 186.4 s | 1,441,764 (1.8x) | 1 | 7.4 | 300 | 1000 | 3201 | 7303 | 20692991 |
| +dedupe | 193.6 s | 1,388,145 (1.7x) | 1 | 7.4 | 300 | 900 | 2801 | 6703 | 20004863 |

Full-day run spread (s): naive: 329; +mmap: 321; +long: 341; +array: 194; +pool: 186; +dedupe: 194
