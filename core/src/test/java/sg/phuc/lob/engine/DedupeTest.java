package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import static org.junit.jupiter.api.Assertions.*;

class DedupeTest {
    static final class Counting implements Listener {
        int bbo; long lastTs;
        @Override public void onBbo(long ts, int locate, int bid, int bidSh, int ask, int askSh) { bbo++; lastTs = ts; }
    }
    static byte[][] script() {
        return new byte[][]{
            Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q'),
            Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000),      // bbo change
            Msg.add(7, 11, 2, 'B', 100, "AAPL", 649900),      // deeper: no change
            Msg.add(7, 12, 3, 'S', 50, "AAPL", 650100),       // change
            Msg.cancel(7, 13, 2, 10),                          // deeper: no change
            Msg.exec(7, 14, 1, 40, 900),                       // change (shares)
            Msg.delete(7, 15, 2),                              // no change
            Msg.delete(7, 16, 1)};                             // change
    }
    @Test void onBboOnlyOnChangeWhenDeduping() {
        Counting raw = new Counting(), dd = new Counting();
        Engine a = new Engine(new HashOrderMap(64), TreeBook::new, raw, null, null, false);
        Engine b = new Engine(new HashOrderMap(64), TreeBook::new, dd, null, null, true);
        for (byte[] m : script()) { a.apply(m, m.length); b.apply(m, m.length); }
        assertEquals(7, raw.bbo);
        assertEquals(4, dd.bbo);
        assertEquals(16, dd.lastTs);
    }
}
