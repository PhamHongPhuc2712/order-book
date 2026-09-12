package sg.phuc.lob.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.phuc.lob.book.HashOrderMap;
import sg.phuc.lob.book.TreeBook;
import sg.phuc.lob.engine.DerivedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Derived output over the 5-minute fixture must be byte-identical across runs, and equal to the pinned hash. */
class GoldenReplayTest {
    static final Path FIXTURE = Path.of("src/test/resources/golden/subset-5min.bin");
    static final Path EXPECTED = Path.of("src/test/resources/golden/expected.sha256");
    static final String[] FILES = {"bbo.ndjson", "executions.ndjson", "trades.ndjson", "daily.ndjson"};

    static String replayHash(Path out) throws Exception {
        DerivedWriter w = new DerivedWriter(out);
        Replay.run(FIXTURE, null, w, () -> new HashOrderMap(1 << 16), TreeBook::new, false);
        w.close();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String f : FILES) md.update(Files.readAllBytes(out.resolve(f)));
        return HexFormat.of().formatHex(md.digest());
    }

    @Test void derivedOutputIsDeterministicAndMatchesPinnedHash(@TempDir Path a, @TempDir Path b) throws Exception {
        assumeTrue(Files.exists(FIXTURE), "golden fixture not cut yet (Phase 1 Task 8)");
        String ha = replayHash(a), hb = replayHash(b);
        assertEquals(ha, hb, "two runs on the same input produced different derived output");
        if (Files.exists(EXPECTED)) assertEquals(Files.readString(EXPECTED).trim(), ha, "derived output changed versus the pinned hash");
        else System.out.println("golden sha256=" + ha + " (pin it in " + EXPECTED + ")");
    }
}
