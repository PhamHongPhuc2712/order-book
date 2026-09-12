package sg.phuc.lob.book;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeBookTest {
    @Test void bestAndCrossed() {
        Book bk = new TreeBook(7);
        assertEquals(0, bk.bestBid()); assertEquals(0, bk.bestAsk()); assertFalse(bk.isCrossed());
        bk.level((byte) 'B', 650000, true).append(new Order(1, 7, (byte) 'B', 100, 650000, 0));
        bk.level((byte) 'B', 649900, true).append(new Order(2, 7, (byte) 'B', 100, 649900, 0));
        bk.level((byte) 'S', 650100, true).append(new Order(3, 7, (byte) 'S', 30, 650100, 0));
        assertEquals(650000, bk.bestBid()); assertEquals(650100, bk.bestAsk());
        assertEquals(100, bk.bestShares((byte) 'B')); assertEquals(30, bk.bestShares((byte) 'S'));
        assertEquals(2, bk.levels((byte) 'B'));
        assertNull(bk.level((byte) 'B', 640000, false));
        bk.removeLevel((byte) 'B', 650000);
        assertEquals(649900, bk.bestBid());
        bk.level((byte) 'S', 649900, true).append(new Order(4, 7, (byte) 'S', 1, 649900, 0));
        assertTrue(bk.isCrossed());
    }
}
