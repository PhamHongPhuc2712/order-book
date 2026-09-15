package sg.phuc.lob.engine;

import sg.phuc.lob.book.Book;
import sg.phuc.lob.book.Level;
import sg.phuc.lob.book.Order;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Set;

/**
 * Exact queue-position episodes (Phase 3 Task 4, D24). At the first BBO change at or after each grid point (every
 * {@code everyNs}, market hours, trading state T) a hypothetical infinitesimal order joins the back of the best level on
 * each side; the orders ahead of it are exactly that level's FIFO list at that instant. Every later execution or cancel of
 * one of those orders reduces {@code ahead}. The joiner is filled at the first execution at its price on its side once
 * {@code ahead} is 0 (an execution of an order behind it while {@code ahead > 0} contradicts price-time priority: recorded
 * as {@code anomaly=1} and still counted as a fill). Censoring: {@code moved} (the side's best price left P while orders were
 * still ahead or behind), {@code exhausted} (ahead reached 0 and the level then disappeared, so nobody was behind the joiner
 * to reveal a fill), {@code T} ({@code censorNs} elapsed), {@code eod} (system event M). One NDJSON line per episode;
 * {@code endTs} is when it ended (the fill, or the censoring event), so time-to-exhaustion is available as an upper bound.
 */
public final class QueueProbe implements Listener, AutoCloseable {
    private static final class Episode {
        long t0; byte side; int price; int ahead0, orders0; long ahead;
        long[] refs; int[] rem; int n;
        long execAhead, cancelAhead; boolean atHead; int anomaly;
        int indexOf(long ref) { for (int i = 0; i < n; i++) if (refs[i] == ref) return i; return -1; }
    }
    private static final int N = 65536;
    private final Set<String> symbols;
    private final long everyNs, censorNs;
    private final Writer out;
    private Engine engine;
    private final byte[] on = new byte[N];                     // 0 unknown, 1 probed, 2 not probed
    private final long[] nextSample = new long[N];
    @SuppressWarnings("unchecked") private final ArrayList<Episode>[] active = new ArrayList[N];
    private final StringBuilder sb = new StringBuilder(256);
    private char[] cbuf = new char[512];
    private long started, finished;
    private long openGrid = Long.MAX_VALUE;                    // first grid point at or after system event Q; sampling starts there

    public QueueProbe(Set<String> symbols, long everyNs, long censorNs, Writer out) {
        this.symbols = symbols; this.everyNs = everyNs; this.censorNs = censorNs; this.out = out;
    }
    public void setEngine(Engine e) { engine = e; }
    public long started() { return started; }
    public long finished() { return finished; }
    /** Episodes still open (not yet filled or censored) are left unreported: a run that ends before M is a partial day. */
    @Override public void close() throws IOException { out.close(); }

    private boolean enabled(int loc) {
        if (on[loc] == 0) {
            String s = engine.symbol(loc);
            on[loc] = (byte) (s != null && symbols.contains(s) ? 1 : 2);
            if (on[loc] == 1) active[loc] = new ArrayList<>();
        }
        return on[loc] == 1;
    }

    private void expire(int loc, long ts) {
        ArrayList<Episode> a = active[loc];
        for (int i = a.size() - 1; i >= 0; i--) { Episode e = a.get(i); if (ts - e.t0 > censorNs) finish(loc, i, ts, -1, "T"); }
    }

    @Override public void onBbo(long ts, int loc, int bid, int bidSh, int ask, int askSh) {
        if (!enabled(loc)) return;
        expire(loc, ts);
        ArrayList<Episode> a = active[loc];
        Book bk = null;
        for (int i = a.size() - 1; i >= 0; i--) {
            Episode e = a.get(i);
            int best = e.side == 'B' ? bid : ask;
            if (best == e.price) continue;
            if (bk == null) bk = engine.book(loc);
            boolean levelGone = bk.level(e.side, e.price, false) == null;
            finish(loc, i, ts, -1, levelGone && e.atHead ? "exhausted" : "moved");
        }
        if (ts >= Math.max(nextSample[loc], openGrid) && engine.marketHours() && engine.tradingState(loc) == 'T') {
            nextSample[loc] = (ts / everyNs + 1) * everyNs;
            if (bk == null) bk = engine.book(loc);
            start(ts, loc, bk, Engine.BID, bid); start(ts, loc, bk, Engine.ASK, ask);
        }
    }

    private void start(long ts, int loc, Book bk, byte side, int price) {
        if (price == 0) return;
        Level lv = bk.bestLevel(side);
        if (lv == null || lv.price != price || lv.count == 0) return;
        Episode e = new Episode();
        e.t0 = ts; e.side = side; e.price = price;
        e.refs = new long[lv.count]; e.rem = new int[lv.count];
        for (Order o = lv.head; o != null && e.n < lv.count; o = o.next) { e.refs[e.n] = o.ref; e.rem[e.n] = o.shares; e.n++; e.ahead += o.shares; }
        e.ahead0 = (int) Math.min(Integer.MAX_VALUE, e.ahead); e.orders0 = e.n; e.atHead = e.ahead == 0;
        active[loc].add(e); started++;
    }

    @Override public void onExecution(long ts, int loc, long ref, byte side, int shares, int price, long match, boolean printable) {
        if (!enabled(loc)) return;
        expire(loc, ts);
        ArrayList<Episode> a = active[loc];
        for (int i = a.size() - 1; i >= 0; i--) {
            Episode e = a.get(i);
            if (e.side != side) continue;
            int k = e.indexOf(ref);
            if (k >= 0) { reduce(e, k, shares, true); continue; }         // ahead of us, whatever price the C reported
            if (e.price != price) continue;
            if (!e.atHead) e.anomaly = 1;                                  // an order behind us filled first
            finish(loc, i, ts, ts, "none");
        }
    }

    @Override public void onCancel(long ts, int loc, long ref, byte side, int shares, int price) {
        if (!enabled(loc)) return;
        expire(loc, ts);
        for (Episode e : active[loc]) {
            if (e.side != side) continue;
            int k = e.indexOf(ref);
            if (k >= 0) reduce(e, k, shares, false);
        }
    }

    private static void reduce(Episode e, int k, int shares, boolean exec) {
        int d = Math.min(shares, e.rem[k]);
        e.rem[k] -= d; e.ahead -= d;
        if (exec) e.execAhead += d; else e.cancelAhead += d;
        if (e.ahead <= 0) e.atHead = true;
    }

    @Override public void onSystemEvent(long ts, char code) {
        if (code == 'Q') { openGrid = ((ts + everyNs - 1) / everyNs) * everyNs; return; }
        if (code != 'M') return;
        openGrid = Long.MAX_VALUE;
        for (int loc = 0; loc < N; loc++) {
            ArrayList<Episode> a = active[loc];
            if (a == null) continue;
            for (int i = a.size() - 1; i >= 0; i--) finish(loc, i, ts, -1, "eod");
        }
    }

    private void finish(int loc, int idx, long ts, long fillTs, String censor) {
        Episode e = active[loc].remove(idx);
        finished++;
        sb.setLength(0);
        sb.append("{\"t0\":").append(e.t0).append(",\"sym\":\"").append(engine.symbol(loc)).append("\",\"side\":\"").append((char) e.side)
          .append("\",\"price\":").append(e.price).append(",\"ahead0\":").append(e.ahead0).append(",\"orders0\":").append(e.orders0)
          .append(",\"fillTs\":"); if (fillTs < 0) sb.append("null"); else sb.append(fillTs);
        sb.append(",\"censor\":\"").append(censor).append("\",\"execAhead\":").append(e.execAhead).append(",\"cancelAhead\":").append(e.cancelAhead)
          .append(",\"anomaly\":").append(e.anomaly).append(",\"endTs\":").append(ts).append("}\n");
        int n = sb.length(); if (n > cbuf.length) cbuf = new char[n]; sb.getChars(0, n, cbuf, 0);
        try { out.write(cbuf, 0, n); } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
