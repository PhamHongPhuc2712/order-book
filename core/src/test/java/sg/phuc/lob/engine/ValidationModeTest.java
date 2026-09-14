package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 3 validation mode: match-number tracking and the priority-violation hook. */
class ValidationModeTest {
    static Engine open() {
        Engine e = new Engine(new LongOrderMap(1024), ArrayBook::new, new NullListener(), null, new OrderPool(4), true);
        LongSet pr = new LongSet(16), np = new LongSet(16);
        e.validation().enableMatchTracking(new Validation.MatchTracker() {
            @Override public boolean add(long m, boolean p) { return p ? pr.add(m) : np.add(m); }
            @Override public boolean paired(long m) { return pr.contains(m) && np.contains(m); }
            @Override public boolean contains(long m) { return pr.contains(m) || np.contains(m); }
        });
        for (byte[] m : new byte[][]{Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q')}) e.apply(m, m.length);
        return e;
    }
    static void feed(Engine e, byte[]... msgs) { for (byte[] m : msgs) e.apply(m, m.length); }

    @Test void duplicateMatchNumbersAndUnknownBrokenTradesAreCounted() {
        Engine e = open();
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'S', 100, "AAPL", 650100));
        feed(e, Msg.exec(7, 12, 1, 10, 900), Msg.exec(7, 13, 1, 10, 901), Msg.execPx(7, 14, 2, 5, 902, 'Y', 650100));
        assertEquals(0, e.validation().duplicateMatch);
        feed(e, Msg.exec(7, 15, 1, 10, 900));                       // 900 again
        assertEquals(1, e.validation().duplicateMatch);
        feed(e, Msg.broken(7, 16, 901), Msg.broken(7, 17, 902));    // seen: E and C match numbers
        assertEquals(0, e.validation().brokenUnknown);
        feed(e, Msg.broken(7, 18, 12345));                          // never seen
        assertEquals(1, e.validation().brokenUnknown);
        assertTrue(e.validation().toJson().contains("\"duplicateMatch\":1,\"twoSidedMatches\":0,\"brokenUnknown\":1"));
    }

    /** A trade between two resting displayed orders is reported once per leg with one match number: N leg then Y leg. */
    @Test void twoSidedMatchIsNotADuplicateButAThirdLegIs() {
        Engine e = open();
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'S', 100, "AAPL", 650000));
        feed(e, Msg.execPx(7, 12, 1, 100, 777, 'N', 650000), Msg.execPx(7, 12, 2, 100, 777, 'Y', 650000));
        assertEquals(0, e.validation().duplicateMatch); assertEquals(1, e.validation().twoSidedMatches);
        feed(e, Msg.add(7, 13, 3, 'B', 10, "AAPL", 650000), Msg.execPx(7, 14, 3, 10, 777, 'Y', 650000));   // third leg, printable again
        assertEquals(1, e.validation().duplicateMatch); assertEquals(1, e.validation().twoSidedMatches);
        feed(e, Msg.broken(7, 15, 777));
        assertEquals(0, e.validation().brokenUnknown);
    }

    @Test void priorityHookReceivesFullContext() {
        Engine e = open();
        List<String> got = new ArrayList<>();
        e.setPriorityHook((ts, loc, ref, side, ex, osh, op, bp, isHead, headRef, pos, lc, ls, st, dExec, dChange) ->
            got.add(ts + "," + loc + "," + ref + "," + (char) side + "," + ex + "," + osh + "," + op + "," + bp + "," + isHead + "," + headRef + "," + pos + "," + lc + "," + ls + "," + (char) st + "," + dExec + "," + dChange));
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'B', 50, "AAPL", 650000), Msg.add(7, 12, 3, 'B', 25, "AAPL", 650000),
                Msg.add(7, 13, 4, 'B', 10, "AAPL", 649900));
        feed(e, Msg.exec(7, 20, 1, 10, 900));                       // head: no violation, but sets lastExec
        assertTrue(got.isEmpty());
        feed(e, Msg.exec(7, 25, 3, 5, 901));                        // third in queue at best
        assertEquals(1, got.size());
        // ts, loc, ref, side, execShares, orderShares(before), orderPrice, bestPrice, isHead, headRef, queuePos, levelCount, levelShares, state, nsSinceLastExec, nsSinceLastChange
        assertEquals("25,7,3,B,5,25,650000,650000,false,1,2,3,165,T,5,5", got.get(0));
        feed(e, Msg.exec(7, 30, 4, 10, 902));                       // off-best level
        assertEquals(2, got.size());
        assertTrue(got.get(1).startsWith("30,7,4,B,10,10,649900,650000,false,1,0,1,10,T,5,"), got.get(1));
        assertEquals(2, e.validation().priorityViolations); assertEquals(3, e.validation().priorityChecked);
    }
}
