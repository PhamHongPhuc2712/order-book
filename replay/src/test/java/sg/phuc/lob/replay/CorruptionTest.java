package sg.phuc.lob.replay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.phuc.lob.engine.NullListener;
import sg.phuc.lob.itch.FrameReader;
import sg.phuc.lob.itch.Frames;
import sg.phuc.lob.itch.Itch;
import sg.phuc.lob.itch.MappedFrameReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Corrupt the golden fixture in the ways the spec names (§8/§10) and check that the readers throw with a byte offset for
 * framing damage, while the engine counts and continues for damage the framing cannot detect.
 */
class CorruptionTest {
    static final Path FIXTURE = GoldenReplayTest.FIXTURE;
    record Frame(long offset, char type, int len) {}          // offset = position of the 2-byte length prefix
    static byte[] clean;
    static List<Frame> frames;

    @BeforeAll static void load() throws IOException {
        assumeTrue(Files.exists(FIXTURE), "golden fixture not cut yet");
        clean = Files.readAllBytes(FIXTURE);
        frames = new ArrayList<>();
        long off = 0;
        try (var fr = new FrameReader(new BufferedInputStream(Files.newInputStream(FIXTURE)))) {
            int len;
            while ((len = fr.next()) >= 0) { frames.add(new Frame(off, Itch.type(fr.buf()), len)); off += 2 + len; }
        }
        assertTrue(frames.size() > 10_000);
    }

    static Frame frameOfType(char t, int skip) {
        int seen = 0;
        for (Frame f : frames) if (f.type == t && seen++ == skip) return f;
        throw new AssertionError("no frame of type " + t);
    }
    static Path write(Path dir, String name, byte[] data) throws IOException { Path p = dir.resolve(name); Files.write(p, data); return p; }
    static List<Frames> readers(Path p) throws IOException {
        return List.of(new FrameReader(new BufferedInputStream(Files.newInputStream(p))), new MappedFrameReader(p));
    }
    /** Drive a reader to the end; return the exception it threw, or null if it reached clean EOF. */
    static IOException drain(Frames r) {
        try (r) { while (r.next() >= 0) {} return null; } catch (IOException e) { return e; }
    }
    static Replay.Result run(Path p) throws IOException { return Replay.run(p, Replay.Config.NAIVE, null, new NullListener(), false); }

    @Test void truncationInsideAMessageThrowsWithTheFrameOffset(@TempDir Path dir) throws IOException {
        Frame f = frames.get(frames.size() / 2);
        long cut = f.offset + 2 + f.len / 2;                   // mid-message
        Path p = write(dir, "cut.bin", java.util.Arrays.copyOf(clean, (int) cut));
        for (Frames r : readers(p)) {
            IOException ex = drain(r);
            assertNotNull(ex, r.getClass().getSimpleName() + " must throw on truncation");
            assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
            assertTrue(ex.getMessage().endsWith("at byte " + f.offset), r.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Test void truncationInsideALengthPrefixThrows(@TempDir Path dir) throws IOException {
        Frame f = frames.get(frames.size() / 3);
        Path p = write(dir, "cut1.bin", java.util.Arrays.copyOf(clean, (int) f.offset + 1));   // one byte of the prefix
        for (Frames r : readers(p)) {
            IOException ex = drain(r);
            assertNotNull(ex);
            assertTrue(ex.getMessage().contains("truncated length prefix at byte " + f.offset), ex.getMessage());
        }
    }

    @Test void zeroLengthPrefixThrows(@TempDir Path dir) throws IOException {
        Frame f = frames.get(1234);
        byte[] d = clean.clone(); d[(int) f.offset] = 0; d[(int) f.offset + 1] = 0;
        Path p = write(dir, "zero.bin", d);
        for (Frames r : readers(p)) {
            IOException ex = drain(r);
            assertNotNull(ex);
            assertTrue(ex.getMessage().contains("bad frame length 0 at byte " + f.offset), ex.getMessage());
        }
    }

    /**
     * A 2-byte prefix can never exceed the 64 KB frame buffer, so an oversized prefix is not a framing error: the readers hand
     * the engine one huge "frame" and the engine rejects it as badLength (length != spec length for its type). The stream is
     * desynchronised from there; whatever follows is counted, never thrown, unless the framing itself becomes impossible.
     */
    @Test void oversizedLengthPrefixIsCaughtByTheEngineNotTheReader(@TempDir Path dir) throws IOException {
        Frame f = frames.get(2000);
        byte[] d = clean.clone(); d[(int) f.offset] = (byte) 0xFF; d[(int) f.offset + 1] = (byte) 0xFF;
        Path p = write(dir, "big.bin", d);
        for (Frames r : readers(p)) {
            try (r) {
                for (int i = 0; i < 2000; i++) assertTrue(r.next() > 0);
                assertEquals(65535, r.next(), "the corrupted frame is delivered at the prefix's length");
                assertEquals(f.type, Itch.type(r.buf()));
            }
        }
        try {
            Replay.Result res = run(p);
            assertTrue(res.validation().contains("\"badLength\":") && !res.validation().contains("\"badLength\":0,"), res.validation());
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("at byte"), e.getMessage());      // desync ran into an impossible frame: also acceptable
        }
    }

    /** Flipping the type byte of a P (non-cross trade: no book or state effect) changes exactly one counter. */
    @Test void unknownTypeIsCountedAndNothingElseChanges(@TempDir Path dir) throws IOException {
        Frame f = frameOfType('P', 5);
        byte[] d = clean.clone(); d[(int) f.offset + 2] = 'z';
        Path p = write(dir, "flip.bin", d);
        for (Frames r : readers(p)) assertNull(drain(r), "readers must not throw on an unknown type");
        String cleanV = run(FIXTURE).validation(), flipV = run(p).validation();
        assertTrue(cleanV.contains("\"unknownType\":0,"), cleanV);
        assertTrue(flipV.contains("\"unknownType\":1,"), flipV);
        assertEquals(cleanV, flipV.replace("\"unknownType\":1,", "\"unknownType\":0,"), "every other counter and sample must be unchanged");
    }
}
