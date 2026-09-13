package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Every EngineTest case again, with the final configuration: LongOrderMap + ArrayBook + OrderPool + dedupe. */
class PooledEngineTest extends EngineTest {
    @Override Engine engine(Set<String> filter) {
        return new Engine(new LongOrderMap(1024), ArrayBook::new, new NullListener(), filter, new OrderPool(4), true);
    }

    @Test void poolRecyclesAcrossAddDeleteCycles() {
        Engine e = open("AAPL", 7);
        for (int i = 1; i <= 1000; i++) feed(e, Msg.add(7, i, i, 'B', 10, "AAPL", 650000), Msg.delete(7, i, i));
        assertEquals(0, e.liveOrders());
        assertTrue(e.pool().created() <= 4, "steady-state add/delete must not allocate: created=" + e.pool().created());
        feed(e, Msg.add(7, 5000, 5000, 'B', 10, "AAPL", 650000), Msg.replace(7, 5001, 5000, 5001, 20, 650100));
        assertEquals(1, e.liveOrders()); assertEquals(650100, e.book(7).bestBid());
        assertTrue(e.pool().created() <= 4);
    }
}
