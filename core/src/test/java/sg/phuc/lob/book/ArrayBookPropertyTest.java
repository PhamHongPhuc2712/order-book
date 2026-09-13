package sg.phuc.lob.book;

import net.jqwik.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** ArrayBook must be observationally identical to TreeBook under random add/remove sequences. */
class ArrayBookPropertyTest {
    @Provide Arbitrary<List<int[]>> ops() {
        // [side 0=B 1=S, price tick 1..300 (x100 = cents), qty 0..5]; qty 0 = remove the head order at that level if any
        return Combinators.combine(Arbitraries.integers().between(0, 1), Arbitraries.integers().between(1, 300), Arbitraries.integers().between(0, 5))
            .as((s, p, q) -> new int[]{s, p, q}).list().ofMinSize(1).ofMaxSize(2000);
    }

    /** Mirrors Engine's level/append/remove logic on one book. */
    static void apply(Book bk, int[] op, long ref) {
        byte side = (byte) (op[0] == 0 ? 'B' : 'S'); int price = op[1] * 100;
        if (op[2] > 0) {
            bk.level(side, price, true).append(new Order(ref, bk.locate(), side, op[2], price, 0));
        } else {
            Level lv = bk.level(side, price, false);
            if (lv != null) { lv.remove(lv.head); if (lv.count == 0) bk.removeLevel(side, price); }
        }
    }

    @Property(tries = 400) void matchesTreeBook(@ForAll("ops") List<int[]> ops) {
        Book t = new TreeBook(1), a = new ArrayBook(1);
        long ref = 1;
        for (int[] op : ops) {
            apply(t, op, ref); apply(a, op, ref); ref++;
            assertEquals(t.bestBid(), a.bestBid()); assertEquals(t.bestAsk(), a.bestAsk());
            assertEquals(t.bestShares((byte) 'B'), a.bestShares((byte) 'B')); assertEquals(t.bestShares((byte) 'S'), a.bestShares((byte) 'S'));
            assertEquals(t.levels((byte) 'B'), a.levels((byte) 'B')); assertEquals(t.levels((byte) 'S'), a.levels((byte) 'S'));
            assertEquals(t.isCrossed(), a.isCrossed()); assertEquals(t.orderCount(), a.orderCount());
        }
        // full level sequences agree: walk every possible price on both sides
        for (byte s : new byte[]{'B', 'S'}) {
            for (int p = 100; p <= 30000; p += 100) {
                Level lt = t.level(s, p, false), la = a.level(s, p, false);
                assertEquals(lt == null, la == null);
                if (lt != null) { assertEquals(lt.shares, la.shares); assertEquals(lt.count, la.count); assertEquals(lt.head.ref, la.head.ref); assertEquals(lt.tail.ref, la.tail.ref); }
            }
        }
    }

    @Example void levelAtWalksBestFirst() {
        ArrayBook a = new ArrayBook(1);
        a.level((byte) 'B', 100, true); a.level((byte) 'B', 300, true); a.level((byte) 'B', 200, true);
        a.level((byte) 'S', 500, true); a.level((byte) 'S', 400, true);
        assertEquals(300, a.levelAt((byte) 'B', 0).price); assertEquals(200, a.levelAt((byte) 'B', 1).price); assertEquals(100, a.levelAt((byte) 'B', 2).price);
        assertNull(a.levelAt((byte) 'B', 3));
        assertEquals(400, a.levelAt((byte) 'S', 0).price); assertEquals(500, a.levelAt((byte) 'S', 1).price);
    }
}
