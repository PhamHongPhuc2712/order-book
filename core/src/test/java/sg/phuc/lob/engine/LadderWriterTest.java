package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.io.StringWriter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Top-N ladder snapshots for one symbol on a time grid, plus the cross message: the input of the static demo. */
class LadderWriterTest {
    static final long MS = 1_000_000L;
    static final long FROM = 33_880_000 * MS, TO = 34_320_000 * MS;    // 9:24:40 .. 9:32:00

    static void feed(Engine e, byte[]... msgs) { for (byte[] m : msgs) e.apply(m, m.length); }

    @Test void snapshotsOnTheGridWithDepthAndCounts() {
        StringWriter out = new StringWriter();
        LadderWriter w = new LadderWriter("AAPL", FROM, TO, 100 * MS, 2, out);
        Engine e = new Engine(new LongOrderMap(64), ArrayBook::new, w, null, new OrderPool(4), true);
        w.setEngine(e);
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.dir(8, 2, "MSFT"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'));
        feed(e, Msg.add(7, FROM - 5, 1, 'B', 100, "AAPL", 650000));                    // before the window: no line
        feed(e, Msg.add(7, FROM + 1, 2, 'B', 50, "AAPL", 650000),                      // first change in the window -> snapshot at the 9:24:40.0 grid point
                Msg.add(7, FROM + 2, 3, 'B', 10, "AAPL", 649900),                      // same 100 ms bucket: no line
                Msg.add(7, FROM + 3, 4, 'B', 10, "AAPL", 649800),
                Msg.add(8, FROM + 4, 90, 'S', 10, "MSFT", 1500000),                    // other symbol: ignored
                Msg.add(7, FROM + 150 * MS, 5, 'S', 30, "AAPL", 650100),               // next bucket -> snapshot (depth 2 per side)
                Msg.add(7, FROM + 160 * MS, 6, 'S', 30, "AAPL", 650100));              // same bucket: no line
        assertEquals(2, out.toString().lines().count(), out.toString());
        List<String> lines = out.toString().lines().toList();
        assertEquals("{\"t\":" + (FROM + 1) + ",\"b\":[[650000,150,2]],\"a\":[]}", lines.get(0).replace(" ", ""));   // taken as ref 2 arrives, before ref 3
        assertEquals("{\"t\":" + (FROM + 150 * MS) + ",\"b\":[[650000,150,2],[649900,10,1]],\"a\":[[650100,30,1]]}", lines.get(1).replace(" ", ""));
    }

    @Test void crossMessageAndStateChangesAreRecorded() {
        StringWriter out = new StringWriter();
        LadderWriter w = new LadderWriter("AAPL", FROM, TO, 100 * MS, 10, out);
        Engine e = new Engine(new LongOrderMap(64), ArrayBook::new, w, null, new OrderPool(4), true);
        w.setEngine(e);
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'));
        feed(e, Msg.add(7, FROM + 1, 1, 'B', 100, "AAPL", 650000));
        byte[] q = Msg.hdr('Q', 40, 7, FROM + 1000 * MS);
        Msg.p64(q, 11, 12345); Msg.alpha(q, 19, 8, "AAPL"); Msg.p32(q, 27, 650050); Msg.p64(q, 31, 777); q[39] = 'O';
        e.apply(q, q.length);
        feed(e, Msg.sys(FROM + 2000 * MS, 'Q'));
        feed(e, Msg.add(7, TO + 1, 2, 'B', 1, "AAPL", 650000));                        // after the window: no line
        List<String> lines = out.toString().lines().toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(1).contains("\"cross\":\"O\"") && lines.get(1).contains("\"shares\":12345") && lines.get(1).contains("\"price\":650050"), lines.get(1));
        assertTrue(lines.get(2).contains("\"system\":\"Q\""), lines.get(2));
    }
}
