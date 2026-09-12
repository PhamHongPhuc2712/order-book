package sg.phuc.lob.itch;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class FrameReaderTest {
    static byte[] framed(byte[]... msgs) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] m : msgs) { out.write(m.length >> 8); out.write(m.length & 0xFF); out.writeBytes(m); }
        return out.toByteArray();
    }
    @Test void readsFramesInOrder() throws IOException {
        byte[] a = new byte[12]; a[0] = 'S'; byte[] b = new byte[19]; b[0] = 'D';
        var fr = new FrameReader(new ByteArrayInputStream(framed(a, b)));
        assertEquals(12, fr.next()); assertEquals('S', fr.buf()[0]);
        assertEquals(19, fr.next()); assertEquals('D', fr.buf()[0]);
        assertEquals(-1, fr.next());
        assertEquals(2, fr.frames()); assertEquals(2 + 12 + 2 + 19, fr.bytes());
    }
    @Test void truncatedMessageThrowsWithOffset() {
        byte[] a = new byte[12]; a[0] = 'S';
        byte[] data = framed(a); byte[] cut = java.util.Arrays.copyOf(data, data.length - 3);
        var fr = new FrameReader(new ByteArrayInputStream(cut));
        var ex = assertThrows(IOException.class, fr::next);
        assertTrue(ex.getMessage().contains("truncated"));
        assertTrue(ex.getMessage().contains("at byte 0"));
    }
    @Test void zeroLengthThrows() {
        var fr = new FrameReader(new ByteArrayInputStream(new byte[]{0, 0, 1}));
        assertThrows(IOException.class, fr::next);
    }
}
