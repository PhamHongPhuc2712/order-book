package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    static Engine engine(Set<String> filter) { return new Engine(new HashOrderMap(1024), TreeBook::new, new NullListener(), filter); }
    static void feed(Engine e, byte[]... msgs) { for (byte[] m : msgs) e.apply(m, m.length); }
    static Engine open(String stock, int loc) {           // directory, trading, market hours
        Engine e = engine(null);
        feed(e, Msg.sys(1, 'O'), Msg.dir(loc, 2, stock), Msg.action(loc, 3, stock, 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q'));
        return e;
    }

    @Test void addExecDeleteAndBest() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'S', 50, "AAPL", 650100));
        assertEquals(650000, e.book(7).bestBid()); assertEquals(650100, e.book(7).bestAsk()); assertEquals(2, e.liveOrders());
        feed(e, Msg.exec(7, 12, 1, 40, 900));
        assertEquals(60, e.book(7).bestShares((byte) 'B'));
        feed(e, Msg.delete(7, 13, 1));
        assertEquals(0, e.book(7).bestBid()); assertEquals(1, e.liveOrders());
        assertTrue(e.validation().structurallyClean());
        assertEquals(1, e.validation().priorityChecked); assertEquals(0, e.validation().priorityViolations);
    }
    @Test void replaceGoesToBackOfQueueWithNewRef() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'B', 100, "AAPL", 650000));
        feed(e, Msg.replace(7, 12, 1, 3, 100, 650000));
        Level lv = e.book(7).level((byte) 'B', 650000, false);
        assertEquals(2, lv.head.ref); assertEquals(3, lv.tail.ref); assertEquals(2, lv.count);
        assertNull(e.orders().get(1)); assertNotNull(e.orders().get(3));
    }
    @Test void executionWithPriceReducesButIsNotPriorityChecked() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'S', 100, "AAPL", 650100));
        feed(e, Msg.execPx(7, 11, 1, 30, 901, 'Y', 650050));
        assertEquals(70, e.book(7).bestShares((byte) 'S'));
        assertEquals(0, e.validation().priorityChecked);
        assertTrue(e.validation().structurallyClean());
    }
    @Test void executionOffBestIsPriorityViolation() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'B', 100, "AAPL", 649900));
        feed(e, Msg.exec(7, 12, 2, 10, 901));            // 649900 is not best
        assertEquals(1, e.validation().priorityViolations);
        feed(e, Msg.exec(7, 13, 1, 10, 902));            // head of best
        assertEquals(2, e.validation().priorityChecked); assertEquals(1, e.validation().priorityViolations);
    }
    @Test void crossedIsCountedOnlyInMarketHoursAndStateT() {
        Engine e = engine(null);
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'));
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650100), Msg.add(7, 11, 2, 'S', 100, "AAPL", 650000));   // crossed pre-open
        assertEquals(0, e.validation().crossedInMarket);
        feed(e, Msg.sys(12, 'Q'), Msg.add(7, 13, 3, 'B', 1, "AAPL", 650100));
        assertEquals(1, e.validation().crossedInMarket);
        feed(e, Msg.action(7, 14, "AAPL", 'H'), Msg.add(7, 15, 4, 'B', 1, "AAPL", 650100));
        assertEquals(1, e.validation().crossedInMarket);                  // halted: not counted
    }
    @Test void unknownRefAndExceedsAreCountedAndClamped() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.exec(7, 10, 99, 10, 1));
        assertEquals(1, e.validation().unknownRef);
        feed(e, Msg.add(7, 11, 1, 'B', 10, "AAPL", 650000), Msg.cancel(7, 12, 1, 50));
        assertEquals(1, e.validation().cancelExceeds); assertEquals(0, e.liveOrders());
    }
    @Test void duplicateRefReplacesAndCounts() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.add(7, 11, 1, 'B', 20, "AAPL", 650000));
        assertEquals(1, e.validation().duplicateRef); assertEquals(20, e.book(7).bestShares((byte) 'B')); assertEquals(1, e.liveOrders());
    }
    @Test void symbolFilterSkipsOtherLocates() {
        Engine e = engine(Set.of("AAPL"));
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.dir(8, 2, "MSFT"), Msg.sys(3, 'Q'));
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.add(8, 11, 2, 'B', 10, "MSFT", 3000000));
        assertEquals(1, e.liveOrders()); assertEquals(0, e.book(8).bestBid());
    }
    @Test void badLengthAndUnknownTypeAreCounted() {
        Engine e = open("AAPL", 7);
        byte[] a = Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000);
        e.apply(a, a.length - 1);
        byte[] z = Msg.hdr('z', 12, 7, 11);
        e.apply(z, z.length);
        assertEquals(1, e.validation().badLength); assertEquals(1, e.validation().unknownType); assertEquals(0, e.liveOrders());
    }
    @Test void liveAtCReported() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.sys(11, 'M'), Msg.sys(12, 'E'), Msg.sys(13, 'C'));
        assertEquals(1, e.validation().liveAtC);
    }
}
