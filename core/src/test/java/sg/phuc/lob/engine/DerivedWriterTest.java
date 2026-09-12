package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.phuc.lob.book.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DerivedWriterTest {
    @Test void writesBboChangesAndExecutions(@TempDir Path dir) throws IOException {
        DerivedWriter w = new DerivedWriter(dir);
        Engine e = new Engine(new HashOrderMap(1024), TreeBook::new, w, null);
        w.setEngine(e);
        for (byte[] m : new byte[][]{
                Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q'),
                Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'S', 50, "AAPL", 650100),
                Msg.exec(7, 12, 1, 40, 900), Msg.delete(7, 13, 1)}) e.apply(m, m.length);
        w.close();

        List<String> bbo = Files.readAllLines(dir.resolve("bbo.ndjson"));
        assertEquals(4, bbo.size());                                     // add bid, add ask, exec (shares changed), delete
        assertEquals("{\"ts\":10,\"sym\":\"AAPL\",\"bid\":650000,\"bidSh\":100,\"ask\":0,\"askSh\":0}", bbo.get(0));
        assertEquals("{\"ts\":12,\"sym\":\"AAPL\",\"bid\":650000,\"bidSh\":60,\"ask\":650100,\"askSh\":50}", bbo.get(2));

        List<String> ex = Files.readAllLines(dir.resolve("executions.ndjson"));
        assertEquals(1, ex.size());
        assertTrue(ex.get(0).contains("\"ref\":1,\"side\":\"B\",\"shares\":40,\"price\":650000,\"match\":900,\"printable\":true"));

        List<String> daily = Files.readAllLines(dir.resolve("daily.ndjson"));
        assertEquals(1, daily.size());
        assertTrue(daily.get(0).contains("\"sym\":\"AAPL\",\"adds\":2,\"execs\":1,\"cancels\":1,\"volEC\":40,\"volP\":0,\"volQ\":0,\"liveAtC\":1}"));
        assertEquals(0, Files.readAllLines(dir.resolve("trades.ndjson")).size());
    }
}
