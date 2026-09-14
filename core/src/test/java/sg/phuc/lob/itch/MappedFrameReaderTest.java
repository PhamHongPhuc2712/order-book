package sg.phuc.lob.itch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MappedFrameReaderTest {
    static byte[] randomFramed(int n, long seed) {
        Random r = new Random(seed);
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            byte[] m = new byte[12 + r.nextInt(40)]; r.nextBytes(m);
            out.write(m.length >> 8); out.write(m.length & 0xFF); out.writeBytes(m);
        }
        return out.toByteArray();
    }

    @Test void identicalSequenceToStreamReader(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("x.bin"); Files.write(f, randomFramed(5000, 1));
        try (Frames a = new FrameReader(new BufferedInputStream(Files.newInputStream(f)));
             Frames b = new MappedFrameReader(f)) {
            int la, lb;
            while ((la = a.next()) >= 0) {
                lb = b.next();
                assertEquals(la, lb);
                assertArrayEquals(Arrays.copyOf(a.buf(), la), Arrays.copyOf(b.buf(), lb));
            }
            assertEquals(-1, b.next());
            assertEquals(a.frames(), b.frames()); assertEquals(a.bytes(), b.bytes());
        }
    }

    /** Frames that straddle a window boundary must be re-mapped from the frame start, not split or skipped. */
    @Test void framesStraddlingWindowBoundariesAreReadIntact(@TempDir Path dir) throws IOException {
        long window = 2 + 65536;                                   // the smallest legal window: ~3 MB of 12..51-byte frames crosses it ~45 times
        Path f = dir.resolve("straddle.bin"); Files.write(f, randomFramed(100_000, 3));
        assertTrue(Files.size(f) > 40 * window, "fixture must span many windows");
        try (Frames a = new FrameReader(new BufferedInputStream(Files.newInputStream(f)));
             MappedFrameReader b = new MappedFrameReader(f, window)) {
            int la;
            while ((la = a.next()) >= 0) {
                assertEquals(la, b.next());
                assertArrayEquals(Arrays.copyOf(a.buf(), la), Arrays.copyOf(b.buf(), la), "frame " + a.frames() + " differs");
            }
            assertEquals(-1, b.next());
            assertEquals(a.frames(), b.frames()); assertEquals(a.bytes(), b.bytes());
            assertTrue(b.remaps() >= 40, "expected many window re-maps, got " + b.remaps());
        }
    }

    @Test void windowSmallerThanAFrameIsRejected(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("x.bin"); Files.write(f, randomFramed(1, 4));
        assertThrows(IllegalArgumentException.class, () -> new MappedFrameReader(f, 1024));
    }

    @Test void truncatedFileThrowsWithOffset(@TempDir Path dir) throws IOException {
        byte[] data = randomFramed(3, 2);
        Path f = dir.resolve("cut.bin"); Files.write(f, Arrays.copyOf(data, data.length - 5));
        try (Frames b = new MappedFrameReader(f)) {
            assertTrue(b.next() > 0); assertTrue(b.next() > 0);
            var ex = assertThrows(IOException.class, b::next);
            assertTrue(ex.getMessage().contains("truncated") && ex.getMessage().contains("at byte"));
        }
    }

    @Test void zeroLengthPrefixThrows(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("zero.bin"); Files.write(f, new byte[]{0, 0, 1});
        try (Frames b = new MappedFrameReader(f)) {
            var ex = assertThrows(IOException.class, b::next);
            assertTrue(ex.getMessage().contains("bad frame length 0"));
        }
    }
}
