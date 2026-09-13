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
    private final long[] resumeTs = new long[65536];      // ts of the last H state=T received in market hours (halt/pause/IPO resume)
    /** A crossed book within this long after a resumption is the halt cross draining, not an error. Reported separately with max lag. */
    public static final long RESUME_WINDOW_NS = 1_000_000_000L;
    private final boolean[] enabled = new boolean[65536];
    private final Set<String> filter;                    // null = all symbols
    private final OrderMap orders;
    private final BookFactory bookFactory;
    private final Listener listener;
    private final OrderPool pool;                        // null = allocate an Order per add (the naive baseline)
    private final boolean dedupe;                        // true = call onBbo only when one of the four BBO fields changed
    private final int[] lastBid, lastBidSh, lastAsk, lastAskSh;
    private final Validation v = new Validation();
    private boolean marketHours = false;
    private char session = 0;
    private long msgs;

    public Engine(OrderMap orders, BookFactory bookFactory, Listener listener, Set<String> symbolFilter) {
        this(orders, bookFactory, listener, symbolFilter, null, false);
    }
    public Engine(OrderMap orders, BookFactory bookFactory, Listener listener, Set<String> symbolFilter, OrderPool pool, boolean dedupe) {
        this.orders = orders; this.bookFactory = bookFactory; this.listener = listener; this.filter = symbolFilter;
        this.pool = pool; this.dedupe = dedupe;
        if (dedupe) { lastBid = new int[65536]; lastBidSh = new int[65536]; lastAsk = new int[65536]; lastAskSh = new int[65536]; }
        else lastBid = lastBidSh = lastAsk = lastAskSh = null;
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
            case 'H' -> { int loc = Itch.locate(b); byte st = b[19]; tradingState[loc] = st; if (st == 'T' && marketHours) resumeTs[loc] = ts; }
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

    private Order newOrder(long ref, int loc, byte side, int shares, int price, long ts) {
        return pool == null ? new Order(ref, loc, side, shares, price, ts) : pool.take(ref, loc, side, shares, price, ts);
    }

    private void add(byte[] b, long ts) {
        int loc = Itch.locate(b);
        if (!enabled[loc]) return;
        insert(loc, Itch.u64(b, 11), b[19], Itch.u32(b, 20), Itch.u32(b, 32), ts);
    }

    private void insert(int loc, long ref, byte side, int shares, int price, long ts) {
        Book bk = bookOrCreate(loc);
        Order o = newOrder(ref, loc, side, shares, price, ts);
        Order prev = orders.put(ref, o);
        if (prev != null) { v.duplicateRef++; v.sample("dupRef", msgs, ref, ts); unlinkStale(prev); }
        bk.level(side, price, true).append(o);
        listener.onAdd(ts, loc, ref, side, shares, price);
        afterChange(bk, loc, ts);
    }

    /** A stale order displaced by a duplicate reference: take it off its level and recycle it. */
    private void unlinkStale(Order prev) {
        if (prev.level != null) { Level pl = prev.level; pl.remove(prev); if (pl.count == 0) books[prev.locate].removeLevel(prev.side, pl.price); }
        if (pool != null) pool.give(prev);
    }

    private Order lookup(byte[] b, long ref) {
        Order o = orders.get(ref);
        if (o == null && enabled[Itch.locate(b)]) { v.unknownRef++; v.sample("unknownRef", msgs, ref, Itch.timestamp(b)); }
        return o;
    }

    private void exec(byte[] b, long ts, boolean withPrice) {
        long ref = Itch.u64(b, 11); int ex = Itch.u32(b, 19); long match = Itch.u64(b, 23);
        v.matchSeen(match);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        int price = withPrice ? Itch.u32(b, 32) : o.price;
        boolean printable = !withPrice || b[31] == 'Y';
        if (ex > o.shares) { v.execExceeds++; v.sample("execExceeds", msgs, ref, ts); ex = o.shares; }
        if (!withPrice && marketHours && tradingState[o.locate] == 'T') {
            v.priorityChecked++;
            Level best = bk.bestLevel(o.side);
            if (best == null || best.price != o.price || best.head != o) { v.priorityViolations++; v.sample("priority", msgs, ref, ts); }
        }
        int loc = o.locate;
        o.level.reduce(o, ex);
        listener.onExecution(ts, loc, ref, o.side, ex, price, match, printable);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, loc, ts);
    }

    private void cancel(byte[] b, long ts) {
        long ref = Itch.u64(b, 11); int c = Itch.u32(b, 19);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        if (c > o.shares) { v.cancelExceeds++; v.sample("cancelExceeds", msgs, ref, ts); c = o.shares; }
        int loc = o.locate;
        o.level.reduce(o, c);
        listener.onCancel(ts, loc, ref, o.side, c, o.price);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, loc, ts);
    }

    private void delete(byte[] b, long ts) {
        Order o = lookup(b, Itch.u64(b, 11)); if (o == null) return;
        Book bk = books[o.locate];
        int loc = o.locate;
        listener.onCancel(ts, loc, o.ref, o.side, o.shares, o.price);
        removeOrder(o, bk);
        afterChange(bk, loc, ts);
    }

    private void replace(byte[] b, long ts) {
        long oldRef = Itch.u64(b, 11), newRef = Itch.u64(b, 19); int shares = Itch.u32(b, 27); int price = Itch.u32(b, 31);
        Order o = lookup(b, oldRef); if (o == null) return;
        Book bk = books[o.locate];
        int loc = o.locate; byte side = o.side;
        listener.onCancel(ts, loc, oldRef, side, o.shares, o.price);          // old order leaves the queue
        removeOrder(o, bk);
        Order n = newOrder(newRef, loc, side, shares, price, ts);
        Order prev = orders.put(newRef, n);
        if (prev != null) { v.duplicateRef++; v.sample("dupRef", msgs, newRef, ts); unlinkStale(prev); }
        bk.level(side, price, true).append(n);                                 // D22: back of queue
        listener.onAdd(ts, loc, newRef, side, shares, price);
        afterChange(bk, loc, ts);
    }

    /** Unlink from level and map; recycle last, after every listener has seen the order. */
    private void removeOrder(Order o, Book bk) {
        Level lv = o.level; lv.remove(o); orders.remove(o.ref);
        if (lv.count == 0) bk.removeLevel(o.side, lv.price);
        if (pool != null) pool.give(o);
    }

    private void afterChange(Book bk, int loc, long ts) {
        int bb = bk.bestBid(), ba = bk.bestAsk();
        if (marketHours && tradingState[loc] == 'T' && bb != 0 && ba != 0 && bb >= ba) {
            long lag = ts - resumeTs[loc];
            if (resumeTs[loc] != 0 && lag >= 0 && lag < RESUME_WINDOW_NS) { v.crossedAtResume++; if (lag > v.crossedAtResumeMaxLagNs) v.crossedAtResumeMaxLagNs = lag; }
            else { v.crossedInMarket++; v.sample("crossed", msgs, loc, ts); }
        }
        int bs = bk.bestShares(BID), as = bk.bestShares(ASK);
        if (dedupe) {
            if (bb == lastBid[loc] && bs == lastBidSh[loc] && ba == lastAsk[loc] && as == lastAskSh[loc]) return;
            lastBid[loc] = bb; lastBidSh[loc] = bs; lastAsk[loc] = ba; lastAskSh[loc] = as;
        }
        listener.onBbo(ts, loc, bb, bs, ba, as);
    }

    public Validation validation() { return v; }
    public String symbol(int loc) { return symbols[loc]; }
    public Book book(int loc) { return bookOrCreate(loc); }
    public OrderMap orders() { return orders; }
    public OrderPool pool() { return pool; }
    public int liveOrders() { return orders.size(); }
    public long messages() { return msgs; }
    public boolean marketHours() { return marketHours; }
    public byte tradingState(int loc) { return tradingState[loc]; }
}
