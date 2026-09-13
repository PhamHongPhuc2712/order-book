package sg.phuc.lob.book;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderPoolTest {
    @Test void recyclesAndResets() {
        OrderPool p = new OrderPool(0);
        Order a = p.take(1, 7, (byte) 'B', 10, 100, 1), b = p.take(2, 7, (byte) 'S', 20, 200, 2), c = p.take(3, 7, (byte) 'B', 30, 300, 3);
        assertEquals(3, p.created()); assertEquals(0, p.free());
        Level lv = new Level(100); lv.append(a); lv.remove(a);          // simulate a lived life
        p.give(a); p.give(b); p.give(c);
        assertEquals(3, p.free());
        Order d = p.take(4, 8, (byte) 'S', 40, 400, 4);
        assertSame(c, d);                                                 // LIFO: last given is first taken
        assertEquals(4, d.ref); assertEquals(8, d.locate); assertEquals('S', d.side); assertEquals(40, d.shares); assertEquals(400, d.price); assertEquals(4, d.ts);
        assertNull(d.prev); assertNull(d.next); assertNull(d.level);
        p.take(5, 8, (byte) 'S', 1, 1, 5); p.take(6, 8, (byte) 'S', 1, 1, 6);
        assertEquals(3, p.created(), "no new allocations while the free list has orders");
        p.take(7, 8, (byte) 'S', 1, 1, 7);
        assertEquals(4, p.created());
    }
    @Test void preallocates() {
        OrderPool p = new OrderPool(5);
        assertEquals(5, p.free()); assertEquals(5, p.created());
    }
}
