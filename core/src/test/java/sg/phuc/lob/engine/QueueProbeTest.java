package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact joiner episodes: what happens to the queue ahead of a hypothetical order that joins the touch.
 * Sampling rule: an episode starts at the first BBO change at or after each grid point (every `everyNs`), and the joiner
 * arrives immediately after that change, so the triggering message's order (if at the touch) is ahead of it.
 */
class QueueProbeTest {
    static final long S = 1_000_000_000L;
    static final long T0 = 34_200 * S;                    // 9:30:00 — a grid point

    record Rig(Engine e, QueueProbe p, StringWriter out) {
        List<String> lines() { return out.toString().lines().toList(); }
    }
    /** Final engine configuration (dedupe on: onBbo only fires when the top of book changes). Book before 9:30: bid 65.00 = ref 1 (100). */
    static Rig rig() {
        StringWriter out = new StringWriter();
        QueueProbe p = new QueueProbe(Set.of("AAPL"), S, 60 * S, out);
        Engine e = new Engine(new LongOrderMap(64), ArrayBook::new, p, null, new OrderPool(4), true);
        p.setEngine(e);
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.dir(8, 2, "MSFT"), Msg.action(7, 3, "AAPL", 'T'), Msg.action(8, 3, "MSFT", 'T'),
                Msg.sys(4, 'S'), Msg.add(7, T0 - 10, 1, 'B', 100, "AAPL", 650000), Msg.sys(T0 - 5, 'Q'));   // Q at 9:29:59.999999995 -> first grid point 9:30:00
        assertEquals(0, p.started(), "pre-open: no sampling");
        return new Rig(e, p, out);
    }
    static void feed(Engine e, byte[]... msgs) { for (byte[] m : msgs) e.apply(m, m.length); }

    @Test void fillAfterQueueAheadIsExhaustedByCancelsAndExecutions() {
        Rig r = rig();
        feed(r.e, Msg.add(7, T0 - 4, 2, 'B', 50, "AAPL", 650000));      // bid 65.00 = ref 1 (100), ref 2 (50); market hours but before the 9:30:00 grid point
        feed(r.e, Msg.add(7, T0 + 1, 9, 'S', 10, "AAPL", 650100));      // first BBO change after 9:30 -> both sides sampled at t0 = T0+1
        assertEquals(2, r.p.started());
        feed(r.e, Msg.add(7, T0 + 2, 3, 'B', 30, "AAPL", 650000));      // arrives behind the joiner: irrelevant to `ahead`
        feed(r.e, Msg.cancel(7, T0 + 3, 1, 60));                         // X 60 on ref 1 -> cancelAhead 60, ahead 90
        feed(r.e, Msg.exec(7, T0 + 4, 1, 40, 900));                      // E 40 on ref 1 -> execAhead 40, ahead 50 (ref 1 gone)
        feed(r.e, Msg.delete(7, T0 + 5, 2));                             // D ref 2 -> cancelAhead 110, ahead 0: at head
        assertEquals(0, r.lines().size(), "nothing finished yet");
        feed(r.e, Msg.exec(7, T0 + 6, 3, 10, 901));                      // first execution at 65.00 after we reached the head: fill
        List<String> lines = r.lines();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals("{\"t0\":" + (T0 + 1) + ",\"sym\":\"AAPL\",\"side\":\"B\",\"price\":650000,\"ahead0\":150,\"orders0\":2,\"fillTs\":" + (T0 + 6)
                + ",\"censor\":\"none\",\"execAhead\":40,\"cancelAhead\":110,\"anomaly\":0,\"endTs\":" + (T0 + 6) + "}", lines.get(0));
        assertEquals(1, r.p.started() - r.p.finished(), "the ask-side episode is still open");
    }

    @Test void priceMovingAwayIsMovedAndAnEmptiedLevelIsExhausted() {
        Rig r = rig();
        feed(r.e, Msg.add(7, T0 + 1, 9, 'S', 10, "AAPL", 650100));      // sample: bid ahead0 100 (ref 1), ask ahead0 10 (ref 9)
        feed(r.e, Msg.add(7, T0 + 2, 10, 'S', 10, "AAPL", 650050));     // better ask: best leaves 65.01 while ref 9 is still ahead -> moved
        feed(r.e, Msg.exec(7, T0 + 3, 1, 100, 900));                     // ref 1 fully executes, nobody behind the joiner: level gone with ahead 0 -> exhausted
        List<String> lines = r.lines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"side\":\"S\",\"price\":650100,\"ahead0\":10,\"orders0\":1,\"fillTs\":null,\"censor\":\"moved\",\"execAhead\":0,\"cancelAhead\":0,\"anomaly\":0"), lines.get(0));
        assertTrue(lines.get(1).contains("\"side\":\"B\",\"price\":650000,\"ahead0\":100,\"orders0\":1,\"fillTs\":null,\"censor\":\"exhausted\",\"execAhead\":100,\"cancelAhead\":0,\"anomaly\":0,\"endTs\":" + (T0 + 3) + "}"), lines.get(1));
    }

    @Test void executionBehindTheJoinerWhileOrdersAreAheadIsAnAnomalyFill() {
        Rig r = rig();
        feed(r.e, Msg.add(7, T0 + 1, 9, 'S', 10, "AAPL", 650100));      // sample
        feed(r.e, Msg.add(7, T0 + 2, 2, 'B', 10, "AAPL", 650000));      // ref 2 joins behind us
        feed(r.e, Msg.exec(7, T0 + 3, 2, 10, 900));                      // ref 2 fills before ref 1: priority anomaly, counted as a fill
        List<String> lines = r.lines();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"side\":\"B\",\"price\":650000,\"ahead0\":100,\"orders0\":1,\"fillTs\":" + (T0 + 3) + ",\"censor\":\"none\",\"execAhead\":0,\"cancelAhead\":0,\"anomaly\":1"), lines.get(0));
    }

    @Test void replaceAheadCountsAsCancelAndSamplesAlignToTheGrid() {
        Rig r = rig();
        feed(r.e, Msg.add(7, T0 + 700_000_000L, 9, 'S', 10, "AAPL", 650100));  // 9:30:00.7: first touch change after the 9:30:00 grid point -> sample
        feed(r.e, Msg.cancel(7, T0 + 900_000_000L, 9, 1));                      // 9:30:00.9: touch change, but the next grid point is 9:30:01 -> no sample
        assertEquals(2, r.p.started());
        feed(r.e, Msg.replace(7, T0 + S + 1, 1, 4, 80, 650000));                // U at 9:30:01.000000001: ref 1 leaves (cancelAhead 100), ref 4 at the back; touch changed -> sample
        assertEquals(4, r.p.started());
        feed(r.e, Msg.exec(7, T0 + S + 2, 4, 50, 900));                         // fills the first bid episode (ref 4 is behind it) and reduces the second
        List<String> lines = r.lines();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"t0\":" + (T0 + 700_000_000L) + ",\"sym\":\"AAPL\",\"side\":\"B\",\"price\":650000,\"ahead0\":100,\"orders0\":1,\"fillTs\":" + (T0 + S + 2)
                + ",\"censor\":\"none\",\"execAhead\":0,\"cancelAhead\":100,\"anomaly\":0"), lines.get(0));
        feed(r.e, Msg.delete(7, T0 + S + 3, 4));                                // second bid episode: ref 4 (80) had 50 executed, 30 cancelled -> exhausted
        String second = r.lines().get(1);
        assertTrue(second.contains("\"t0\":" + (T0 + S + 1) + ",\"sym\":\"AAPL\",\"side\":\"B\",\"price\":650000,\"ahead0\":80,\"orders0\":1,\"fillTs\":null,\"censor\":\"exhausted\",\"execAhead\":50,\"cancelAhead\":30"), second);
    }

    @Test void timeCensorAndEndOfDay() {
        Rig r = rig();
        feed(r.e, Msg.add(7, T0 + 1, 9, 'S', 10, "AAPL", 650100));              // sample both sides at T0+1
        feed(r.e, Msg.add(7, T0 + 61 * S, 3, 'B', 1, "AAPL", 650000));           // 61 s later: both episodes censored T, two new ones start
        List<String> lines = r.lines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.stream().allMatch(l -> l.contains("\"fillTs\":null,\"censor\":\"T\"")), lines.toString());
        assertEquals(4, r.p.started());
        feed(r.e, Msg.sys(T0 + 62 * S, 'M'));
        assertEquals(4, r.lines().size());
        assertTrue(r.lines().subList(2, 4).stream().allMatch(l -> l.contains("\"censor\":\"eod\"")), r.lines().toString());
        assertEquals(4, r.p.finished());
    }

    @Test void otherSymbolsAndNonTradingStatesAreIgnored() {
        Rig r = rig();
        feed(r.e, Msg.add(8, T0 + 1, 50, 'B', 100, "MSFT", 1500000), Msg.add(8, T0 + 2, 51, 'S', 100, "MSFT", 1500100));
        assertEquals(0, r.p.started(), "MSFT is not probed");
        feed(r.e, Msg.action(7, T0 + 3, "AAPL", 'H'), Msg.add(7, T0 + 4, 9, 'S', 10, "AAPL", 650100));
        assertEquals(0, r.p.started(), "halted: no sample");
        feed(r.e, Msg.action(7, T0 + 5, "AAPL", 'T'), Msg.add(7, T0 + 6, 10, 'S', 5, "AAPL", 650100));
        assertEquals(2, r.p.started());
    }
}
