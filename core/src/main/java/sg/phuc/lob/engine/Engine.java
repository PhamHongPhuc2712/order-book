package sg.phuc.lob.engine;

import sg.phuc.lob.book.*;
import sg.phuc.lob.itch.Itch;
import java.util.Set;

/** Single-threaded ITCH 5.0 -> Level-3 books. Counts violations; never throws on data. */
public final class Engine {
    public interface BookFactory { Book create(int locate); }
    public static final byte BID = 'B', ASK = 'S';

    private final Book[] books = new Book[65536];
    private final String[] symbols = new String[65536];
    private final byte[] tradingState = new byte[65536];
    private final boolean[] enabled = new boolean[65536];
    private final Set<String> filter;                    // null = all symbols
    private final OrderMap orders;
    private final BookFactory bookFactory;
    private final Listener listener;
    private final Validation v = new Validation();
    private boolean marketHours = false;
    private char session = 0;
    private long msgs;

    public Engine(OrderMap orders, BookFactory bookFactory, Listener listener, Set<String> symbolFilter) {
        this.orders = orders; this.bookFactory = bookFactory; this.listener = listener; this.filter = symbolFilter;
        if (filter == null) java.util.Arrays.fill(enabled, true);
        enabled[0] = true;
    }

    public void apply(byte[] b, int len) {
        msgs++;
        char t = Itch.type(b);
        int exp = Itch.expectedLength(t);
        if (exp < 0) { v.unknownType++; return; }
        if (exp != len) { v.badLength++; return; }
        long ts = Itch.timestamp(b);
        switch (t) {
            case 'S' -> {
                session = (char) b[11];
                if (session == 'Q') marketHours = true; else if (session == 'M') marketHours = false;
                if (session == 'C') v.liveAtC = orders.size();
                listener.onSystemEvent(ts, session);
            }
            case 'R' -> {
                int loc = Itch.locate(b);
                String sym = Itch.alpha(b, 11, 8);
                symbols[loc] = sym;
                if (filter != null) enabled[loc] = filter.contains(sym);
                if (books[loc] == null) books[loc] = bookFactory.create(loc);
            }
            case 'H' -> tradingState[Itch.locate(b)] = b[19];
            case 'A', 'F' -> add(b, ts);
            case 'E' -> exec(b, ts, false);
            case 'C' -> exec(b, ts, true);
            case 'X' -> cancel(b, ts);
            case 'D' -> delete(b, ts);
            case 'U' -> replace(b, ts);
            case 'P' -> { int loc = Itch.locate(b); long m = Itch.u64(b, 36); v.matchSeen(m);
                          if (enabled[loc]) listener.onTrade(ts, loc, 'P', Itch.u32(b, 20), Itch.u32(b, 32), m, (char) 0); }
            case 'Q' -> { int loc = Itch.locate(b); long m = Itch.u64(b, 31); v.matchSeen(m);
                          long sh = Itch.u64(b, 11);
                          if (enabled[loc]) listener.onTrade(ts, loc, 'Q', (int) Math.min(Integer.MAX_VALUE, sh), Itch.u32(b, 27), m, (char) b[39]); }
            case 'B' -> v.broken(Itch.u64(b, 11));
            default -> {}
        }
    }

    private Book bookOrCreate(int loc) {
        Book bk = books[loc];
        if (bk == null) { v.noDirectory++; bk = books[loc] = bookFactory.create(loc); symbols[loc] = "?"; }
        return bk;
    }

    private void add(byte[] b, long ts) {
        int loc = Itch.locate(b);
        if (!enabled[loc]) return;
        insert(loc, Itch.u64(b, 11), b[19], Itch.u32(b, 20), Itch.u32(b, 32), ts);
    }

    private void insert(int loc, long ref, byte side, int shares, int price, long ts) {
        Book bk = bookOrCreate(loc);
        int bb = bk.bestBid(), ba = bk.bestAsk();
        Order o = new Order(ref, loc, side, shares, price, ts);
        Order prev = orders.put(ref, o);
        if (prev != null) {
            v.duplicateRef++; v.sample("dupRef", msgs, ref);
            if (prev.level != null) { Level pl = prev.level; pl.remove(prev); if (pl.count == 0) books[prev.locate].removeLevel(prev.side, pl.price); }
        }
        bk.level(side, price, true).append(o);
        listener.onAdd(ts, loc, ref, side, shares, price);
        afterChange(bk, loc, ts, bb, ba);
    }

    private Order lookup(byte[] b, long ref) {
        Order o = orders.get(ref);
        if (o == null && enabled[Itch.locate(b)]) { v.unknownRef++; v.sample("unknownRef", msgs, ref); }
        return o;
    }

    private void exec(byte[] b, long ts, boolean withPrice) {
        long ref = Itch.u64(b, 11); int ex = Itch.u32(b, 19); long match = Itch.u64(b, 23);
        v.matchSeen(match);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        int price = withPrice ? Itch.u32(b, 32) : o.price;
        boolean printable = !withPrice || b[31] == 'Y';
        if (ex > o.shares) { v.execExceeds++; v.sample("execExceeds", msgs, ref); ex = o.shares; }
        if (!withPrice && marketHours && tradingState[o.locate] == 'T') {
            v.priorityChecked++;
            Level best = bk.bestLevel(o.side);
            if (best == null || best.price != o.price || best.head != o) { v.priorityViolations++; v.sample("priority", msgs, ref); }
        }
        int bb = bk.bestBid(), ba = bk.bestAsk();
        o.level.reduce(o, ex);
        listener.onExecution(ts, o.locate, ref, o.side, ex, price, match, printable);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void cancel(byte[] b, long ts) {
        long ref = Itch.u64(b, 11); int c = Itch.u32(b, 19);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        if (c > o.shares) { v.cancelExceeds++; v.sample("cancelExceeds", msgs, ref); c = o.shares; }
        int bb = bk.bestBid(), ba = bk.bestAsk();
        o.level.reduce(o, c);
        listener.onCancel(ts, o.locate, ref, o.side, c, o.price);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void delete(byte[] b, long ts) {
        Order o = lookup(b, Itch.u64(b, 11)); if (o == null) return;
        Book bk = books[o.locate];
        int bb = bk.bestBid(), ba = bk.bestAsk();
        listener.onCancel(ts, o.locate, o.ref, o.side, o.shares, o.price);
        removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void replace(byte[] b, long ts) {
        long oldRef = Itch.u64(b, 11), newRef = Itch.u64(b, 19); int shares = Itch.u32(b, 27); int price = Itch.u32(b, 31);
        Order o = lookup(b, oldRef); if (o == null) return;
        Book bk = books[o.locate];
        int loc = o.locate; byte side = o.side;
        int bb = bk.bestBid(), ba = bk.bestAsk();
        listener.onCancel(ts, loc, oldRef, side, o.shares, o.price);          // old order leaves the queue
        removeOrder(o, bk);
        Order n = new Order(newRef, loc, side, shares, price, ts);
        Order prev = orders.put(newRef, n);
        if (prev != null) {
            v.duplicateRef++; v.sample("dupRef", msgs, newRef);
            if (prev.level != null) { Level pl = prev.level; pl.remove(prev); if (pl.count == 0) books[prev.locate].removeLevel(prev.side, pl.price); }
        }
        bk.level(side, price, true).append(n);                                 // D22: back of queue
        listener.onAdd(ts, loc, newRef, side, shares, price);
        afterChange(bk, loc, ts, bb, ba);
    }

    private void removeOrder(Order o, Book bk) {
        Level lv = o.level; lv.remove(o); orders.remove(o.ref);
        if (lv.count == 0) bk.removeLevel(o.side, lv.price);
    }

    private void afterChange(Book bk, int loc, long ts, int bbBefore, int baBefore) {
        int bb = bk.bestBid(), ba = bk.bestAsk();
        if (marketHours && tradingState[loc] == 'T' && bb != 0 && ba != 0 && bb >= ba) { v.crossedInMarket++; v.sample("crossed", msgs, loc); }
        listener.onBbo(ts, loc, bb, bk.bestShares(BID), ba, bk.bestShares(ASK));
    }

    public Validation validation() { return v; }
    public String symbol(int loc) { return symbols[loc]; }
    public Book book(int loc) { return bookOrCreate(loc); }
    public OrderMap orders() { return orders; }
    public int liveOrders() { return orders.size(); }
    public long messages() { return msgs; }
    public boolean marketHours() { return marketHours; }
    public byte tradingState(int loc) { return tradingState[loc]; }
}
