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
