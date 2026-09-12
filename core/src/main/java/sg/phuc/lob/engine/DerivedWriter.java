package sg.phuc.lob.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the regenerable derived outputs as NDJSON: bbo (on change of any of the four fields), executions, trades,
 * and daily per-symbol counters on close(). Symbol names come from the engine, set after construction.
 */
public final class DerivedWriter implements Listener, AutoCloseable {
    private static final int N = 65536;
    private final BufferedWriter bbo, executions, trades;
    private final Path dir;
    private Engine engine;
    private final StringBuilder sb = new StringBuilder(256);
    private final int[] lastBid = new int[N], lastBidSh = new int[N], lastAsk = new int[N], lastAskSh = new int[N];
    private final long[] msgs = new long[N], adds = new long[N], execs = new long[N], cancels = new long[N], deletes = new long[N],
                         replaces = new long[N], volEC = new long[N], volC = new long[N], volP = new long[N], volQ = new long[N];
    private final boolean[] seen = new boolean[N];

    public DerivedWriter(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        bbo = open("bbo.ndjson"); executions = open("executions.ndjson"); trades = open("trades.ndjson");
    }
    private BufferedWriter open(String name) throws IOException {
        return new BufferedWriter(Files.newBufferedWriter(dir.resolve(name), StandardCharsets.US_ASCII), 1 << 20);
    }
    public void setEngine(Engine e) { this.engine = e; }
    private String sym(int loc) { String s = engine == null ? null : engine.symbol(loc); return s == null ? "?" : s; }
    private void write(BufferedWriter w) { try { w.append(sb); } catch (IOException e) { throw new UncheckedIOException(e); } }

    @Override public void onAdd(long ts, int locate, long ref, byte side, int shares, int price) { seen[locate] = true; adds[locate]++; }

    @Override public void onBbo(long ts, int locate, int bid, int bidSh, int ask, int askSh) {
        if (bid == lastBid[locate] && bidSh == lastBidSh[locate] && ask == lastAsk[locate] && askSh == lastAskSh[locate]) return;
        lastBid[locate] = bid; lastBidSh[locate] = bidSh; lastAsk[locate] = ask; lastAskSh[locate] = askSh;
        sb.setLength(0);
        sb.append("{\"ts\":").append(ts).append(",\"sym\":\"").append(sym(locate)).append("\",\"bid\":").append(bid)
          .append(",\"bidSh\":").append(bidSh).append(",\"ask\":").append(ask).append(",\"askSh\":").append(askSh).append("}\n");
        write(bbo);
    }

    @Override public void onExecution(long ts, int locate, long ref, byte side, int shares, int price, long match, boolean printable) {
        seen[locate] = true; execs[locate]++;
        if (printable) volEC[locate] += shares;          // C non-printable excluded from volume per spec
        sb.setLength(0);
        sb.append("{\"ts\":").append(ts).append(",\"sym\":\"").append(sym(locate)).append("\",\"ref\":").append(ref)
          .append(",\"side\":\"").append((char) side).append("\",\"shares\":").append(shares).append(",\"price\":").append(price)
          .append(",\"match\":").append(match).append(",\"printable\":").append(printable).append("}\n");
        write(executions);
    }

    @Override public void onCancel(long ts, int locate, long ref, byte side, int shares, int price) { seen[locate] = true; cancels[locate]++; }

    @Override public void onTrade(long ts, int locate, char kind, int shares, int price, long match, char crossType) {
        seen[locate] = true;
        if (kind == 'P') volP[locate] += shares; else volQ[locate] += shares;
        sb.setLength(0);
        sb.append("{\"ts\":").append(ts).append(",\"sym\":\"").append(sym(locate)).append("\",\"kind\":\"").append(kind)
          .append("\",\"shares\":").append(shares).append(",\"price\":").append(price).append(",\"match\":").append(match)
          .append(",\"cross\":\"").append(crossType == 0 ? "" : String.valueOf(crossType)).append("\"}\n");
        write(trades);
    }

    @Override public void close() throws IOException {
        bbo.close(); executions.close(); trades.close();
        try (BufferedWriter daily = open("daily.ndjson")) {
            for (int loc = 0; loc < N; loc++) {
                if (!seen[loc]) continue;
                int live = engine == null ? 0 : engine.book(loc).orderCount();
                sb.setLength(0);
                sb.append("{\"sym\":\"").append(sym(loc)).append("\",\"adds\":").append(adds[loc]).append(",\"execs\":").append(execs[loc])
                  .append(",\"cancels\":").append(cancels[loc]).append(",\"volEC\":").append(volEC[loc]).append(",\"volP\":").append(volP[loc])
                  .append(",\"volQ\":").append(volQ[loc]).append(",\"liveAtC\":").append(live).append("}\n");
                daily.append(sb);
            }
        }
    }
}
