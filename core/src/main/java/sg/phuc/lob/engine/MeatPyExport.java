package sg.phuc.lob.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Top-of-book for one symbol at 1-minute marks, in the exact form MeatPy's LOBRecorder produces so the two can be diffed
 * (Phase 3 Task 3). MeatPy records the state at the first instrument update whose microsecond timestamp is strictly after
 * the mark, i.e. the state after every message with ts < mark + 1 us. We keep the last BBO and, whenever an update for the
 * symbol arrives at or beyond mark + 1 us, emit that last BBO for every mark passed; marks never reached are emitted at close.
 * Output: minute (ns since midnight), bid, bidSh, ask, askSh — prices at native scale 4.
 */
public final class MeatPyExport implements Listener, AutoCloseable {
    private static final long MINUTE = 60_000_000_000L, MEATPY_RESOLUTION = 1_000L;
    private final String symbol;
    private final long firstMark, lastMark;
    private final BufferedWriter w;
    private Engine engine;
    private int locate = -1;
    private long nextMark;
    private int bid, bidSh, ask, askSh;
    private boolean seen;
    private int emitted;

    public MeatPyExport(Path file, String symbol, long firstMarkNs, long lastMarkNs) throws IOException {
        this.symbol = symbol; this.firstMark = firstMarkNs; this.lastMark = lastMarkNs; this.nextMark = firstMarkNs;
        Files.createDirectories(file.toAbsolutePath().getParent());
        w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.US_ASCII));
        w.write("minute,bid,bidSh,ask,askSh\n");
    }
    public void setEngine(Engine e) { engine = e; }

    @Override public void onBbo(long ts, int loc, int b, int bs, int a, int as) {
        if (locate < 0) { String s = engine == null ? null : engine.symbol(loc); if (symbol.equals(s)) locate = loc; else return; }
        else if (loc != locate) return;
        while (nextMark <= lastMark && ts >= nextMark + MEATPY_RESOLUTION) { emit(nextMark); nextMark += MINUTE; }
        bid = b; bidSh = bs; ask = a; askSh = as; seen = true;
    }
    private void emit(long mark) {
        try { w.write(mark + "," + bid + "," + bidSh + "," + ask + "," + askSh + "\n"); } catch (IOException e) { throw new UncheckedIOException(e); }
        emitted++;
    }
    public int emitted() { return emitted; }
    @Override public void close() throws IOException {
        if (seen) while (nextMark <= lastMark) { emit(nextMark); nextMark += MINUTE; }
        w.close();
    }
}
