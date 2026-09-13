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

    static String replayHash(Path out, Replay.Config cfg) throws Exception {
        DerivedWriter w = new DerivedWriter(out);
        Replay.run(FIXTURE, cfg, null, w, false);
        w.close();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String f : FILES) md.update(Files.readAllBytes(out.resolve(f)));
        return HexFormat.of().formatHex(md.digest());
    }

    @Test void derivedOutputIsDeterministicAndMatchesPinnedHash(@TempDir Path a, @TempDir Path b) throws Exception {
        assumeTrue(Files.exists(FIXTURE), "golden fixture not cut yet (Phase 1 Task 8)");
        String ha = replayHash(a, Replay.Config.NAIVE), hb = replayHash(b, Replay.Config.NAIVE);
        assertEquals(ha, hb, "two runs on the same input produced different derived output");
        if (Files.exists(EXPECTED)) assertEquals(Files.readString(EXPECTED).trim(), ha, "derived output changed versus the pinned hash");
        else System.out.println("golden sha256=" + ha + " (pin it in " + EXPECTED + ")");
    }

    /** Every Phase 2 optimisation, alone and all together, must produce byte-identical derived output to the naive baseline. */
    @Test void everyOptimisationMatchesTheNaiveHash(@TempDir Path dir) throws Exception {
        assumeTrue(Files.exists(FIXTURE) && Files.exists(EXPECTED));
        String expected = Files.readString(EXPECTED).trim();
        Replay.Config[] cfgs = {
            new Replay.Config("mmap", "hash", "tree", false, false),
            new Replay.Config("stream", "long", "tree", false, false),
            new Replay.Config("stream", "hash", "array", false, false),
            new Replay.Config("stream", "hash", "tree", true, false),
            new Replay.Config("stream", "hash", "tree", false, true),
            Replay.Config.FINAL };
        for (int i = 0; i < cfgs.length; i++) {
            Path out = Files.createDirectories(dir.resolve("c" + i));
            assertEquals(expected, replayHash(out, cfgs[i]), "derived output differs for " + cfgs[i].label());
        }
    }
}
