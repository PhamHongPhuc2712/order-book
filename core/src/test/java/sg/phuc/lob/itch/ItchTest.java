package sg.phuc.lob.itch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItchTest {
    @Test void bigEndianAccessors() {
        byte[] b = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertEquals(0x0102, Itch.u16(b, 0));
        assertEquals(0x01020304, Itch.u32(b, 0));
        assertEquals(0x010203040506L, Itch.u48(b, 0));
        assertEquals(0x0102030405060708L, Itch.u64(b, 0));
    }
    @Test void priceMaxFitsInt() {
        byte[] b = {0x77, 0x35, (byte) 0x94, 0x00};     // 2,000,000,000 = 200,000.0000
        assertEquals(2_000_000_000, Itch.u32(b, 0));
    }
    @Test void alphaTrimsRightPadding() {
        byte[] b = "AAPL    ".getBytes();
        assertEquals("AAPL", Itch.alpha(b, 0, 8));
    }
    @Test void lengthsMatchSpec() {
        assertEquals(36, Itch.expectedLength('A')); assertEquals(40, Itch.expectedLength('F'));
        assertEquals(31, Itch.expectedLength('E')); assertEquals(35, Itch.expectedLength('U'));
        assertEquals(-1, Itch.expectedLength('z'));
    }
}
