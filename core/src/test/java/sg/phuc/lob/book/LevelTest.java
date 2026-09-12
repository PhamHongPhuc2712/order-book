package sg.phuc.lob.book;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelTest {
    static Order o(long ref, int sh) { return new Order(ref, 1, (byte) 'B', sh, 650000, 0); }
    @Test void fifoAppendRemoveReduce() {
        var lv = new Level(650000);
        Order a = o(1, 100), b = o(2, 50), c = o(3, 25);
        lv.append(a); lv.append(b); lv.append(c);
        assertSame(a, lv.head); assertSame(c, lv.tail); assertEquals(175, lv.shares); assertEquals(3, lv.count);
        lv.reduce(a, 40); assertEquals(60, a.shares); assertEquals(135, lv.shares);
        lv.remove(b);                                   // middle
        assertSame(c, a.next); assertSame(a, c.prev); assertEquals(85, lv.shares); assertEquals(2, lv.count);
        lv.remove(a);                                   // head
        assertSame(c, lv.head); assertNull(c.prev);
        lv.remove(c);                                   // last
        assertNull(lv.head); assertNull(lv.tail); assertEquals(0, lv.shares); assertEquals(0, lv.count);
    }
}
