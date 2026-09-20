package sg.phuc.lob.engine;

import sg.phuc.lob.book.Book;
import sg.phuc.lob.book.Level;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Top-N ladder of one symbol inside a time window, one NDJSON line per grid point at which the top of book changed
 * ({@code {"t":ts,"b":[[price,shares,orders]...],"a":[...]}}, best first), plus the symbol's cross trades
 * ({@code {"t":ts,"cross":"O","shares":..,"price":..}}) and system events ({@code {"t":ts,"system":"Q"}}).
 * Phase 4 demo input: 9:24:40-9:32 through the opening cross. Not on the hot path; walks the book with {@link Book#levelAt}.
 */
public final class LadderWriter implements Listener, AutoCloseable {
    private final String symbol;
    private final long from, to, everyNs;
    private final int depth;
    private final Writer out;
    private Engine engine;
    private int locate = -1;
    private long nextTick;
    private final StringBuilder sb = new StringBuilder(1024);

    public LadderWriter(String symbol, long fromNs, long toNs, long everyNs, int depth, Writer out) {
        this.symbol = symbol; this.from = fromNs; this.to = toNs; this.everyNs = everyNs; this.depth = depth; this.out = out;
        this.nextTick = fromNs;
    }
    public void setEngine(Engine e) { engine = e; }

    private boolean mine(int loc) {
        if (locate < 0) { String s = engine == null ? null : engine.symbol(loc); if (symbol.equals(s)) locate = loc; else return false; }
        return loc == locate;
    }

    @Override public void onBbo(long ts, int loc, int bid, int bidSh, int ask, int askSh) {
        if (ts < from || ts >= to || !mine(loc) || ts < nextTick) return;
        nextTick = (ts / everyNs + 1) * everyNs;
        Book bk = engine.book(loc);
        sb.setLength(0);
        sb.append("{\"t\":").append(ts).append(",\"b\":");
        side(bk, Engine.BID);
        sb.append(",\"a\":");
        side(bk, Engine.ASK);
        sb.append("}\n");
        write();
    }

    private void side(Book bk, byte s) {
        sb.append('[');
        for (int i = 0; i < depth; i++) {
            Level lv = bk.levelAt(s, i);
            if (lv == null) break;
            if (i > 0) sb.append(',');
            sb.append('[').append(lv.price).append(',').append(lv.shares).append(',').append(lv.count).append(']');
        }
        sb.append(']');
    }

    @Override public void onTrade(long ts, int loc, char kind, int shares, int price, long match, char crossType) {
        if (kind != 'Q' || ts < from || ts >= to || !mine(loc)) return;
        sb.setLength(0);
        sb.append("{\"t\":").append(ts).append(",\"cross\":\"").append(crossType).append("\",\"shares\":").append(shares).append(",\"price\":").append(price).append("}\n");
        write();
    }

    @Override public void onSystemEvent(long ts, char code) {
        if (ts < from || ts >= to) return;
        sb.setLength(0);
        sb.append("{\"t\":").append(ts).append(",\"system\":\"").append(code).append("\"}\n");
        write();
    }

    private void write() { try { out.write(sb.toString()); } catch (IOException e) { throw new UncheckedIOException(e); } }
    @Override public void close() throws IOException { out.close(); }
}
