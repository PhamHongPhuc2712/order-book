package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.phuc.lob.book.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MeatPyExportTest {
    static final long M0 = 34_200_000_000_000L;            // 9:30:00
    static final long MIN = 60_000_000_000L;

    @Test void emitsLastStateBeforeEachMarkAtMeatPyResolution(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("aapl.csv");
        MeatPyExport x = new MeatPyExport(out, "AAPL", M0, M0 + 3 * MIN);   // marks 9:30, 9:31, 9:32, 9:33
        Engine e = new Engine(new HashOrderMap(64), TreeBook::new, x, null);
        x.setEngine(e);
        byte[][] script = {
            Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.dir(8, 2, "MSFT"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q'),
            Msg.add(7, M0 - 5, 1, 'B', 100, "AAPL", 650000),               // before 9:30
            Msg.add(7, M0 + 999, 2, 'S', 50, "AAPL", 650100),              // same microsecond as the 9:30 mark: MeatPy includes it in the 9:30 state
            Msg.add(8, M0 + 1_000, 90, 'B', 1, "MSFT", 100),               // other symbol: ignored
            Msg.add(7, M0 + 1_000, 3, 'B', 10, "AAPL", 650000),            // first AAPL update strictly after 9:30 (us): triggers the 9:30 record
            Msg.exec(7, M0 + MIN + 5_000_000, 1, 40, 900),                 // 9:31:00.005 -> triggers 9:31 record with state before it
            Msg.delete(7, M0 + 2 * MIN + 1_000, 2)};                        // 9:32 + 1 us -> triggers 9:32 with state before it
        for (byte[] m : script) e.apply(m, m.length);
        x.close();                                                          // 9:33 never reached: emitted with the final state
        List<String> lines = Files.readAllLines(out);
        assertEquals("minute,bid,bidSh,ask,askSh", lines.get(0));
        assertEquals(M0 + ",650000,100,650100,50", lines.get(1));            // 9:30: after ref 2 (same us), before ref 3
        assertEquals((M0 + MIN) + ",650000,110,650100,50", lines.get(2));    // 9:31: ref 3 added, exec not yet applied
        assertEquals((M0 + 2 * MIN) + ",650000,70,650100,50", lines.get(3)); // 9:32: exec applied, delete not yet
        assertEquals((M0 + 3 * MIN) + ",650000,70,0,0", lines.get(4));       // 9:33: final state (ask deleted)
        assertEquals(5, lines.size()); assertEquals(4, x.emitted());
    }
}
