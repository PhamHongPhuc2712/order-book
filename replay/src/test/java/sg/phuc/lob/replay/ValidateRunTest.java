package sg.phuc.lob.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.phuc.lob.engine.NullListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** --validate and --dump-priority over the golden fixture: match tracking is clean and the dump has one line per violation. */
class ValidateRunTest {
    static long counter(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), name + " missing in " + json);
        return Long.parseLong(m.group(1));
    }

    @Test void matchTrackingIsCleanAndDumpMatchesViolationCount(@TempDir Path dir) throws Exception {
        assumeTrue(Files.exists(GoldenReplayTest.FIXTURE));
        Path dump = dir.resolve("priority.ndjson");
        Replay.Result r = Replay.run(GoldenReplayTest.FIXTURE, Replay.Config.FINAL, null, new NullListener(), false, new Replay.Validate(true, dump, 1_000_000));
        String v = r.validation();
        assertEquals(0, counter(v, "duplicateMatch")); assertEquals(0, counter(v, "brokenUnknown"));
        assertEquals(14, counter(v, "twoSidedMatches"), "the 9:30-9:35 slice has 14 resting-vs-resting matches (N leg + Y leg)");
        long violations = counter(v, "priorityViolations");
        assertTrue(violations > 0, "fixture is expected to contain some violations: " + v);
        assertEquals(violations, Files.lines(dump).count());
        String first = Files.lines(dump).findFirst().orElseThrow();
        for (String k : new String[]{"\"ts\":", "\"sym\":\"", "\"ref\":", "\"side\":\"", "\"execShares\":", "\"orderPrice\":", "\"bestPrice\":", "\"isHead\":", "\"headRef\":", "\"queuePos\":", "\"levelCount\":", "\"levelShares\":", "\"state\":\"T\"", "\"nsSinceLastExec\":", "\"nsSinceLastChange\":"})
            assertTrue(first.contains(k), k + " missing in " + first);
        // the cap is honoured
        Path dump5 = dir.resolve("p5.ndjson");
        Replay.run(GoldenReplayTest.FIXTURE, Replay.Config.FINAL, null, new NullListener(), false, new Replay.Validate(true, dump5, 5));
        assertEquals(Math.min(5, violations), Files.lines(dump5).count());
    }
}
