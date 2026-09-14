package sg.phuc.lob.replay;

import sg.phuc.lob.engine.Engine;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the first N price-time-priority violations as NDJSON with full context, for research/classify_priority.py. */
public final class PriorityDump implements Engine.PriorityHook, AutoCloseable {
    private final BufferedWriter w;
    private final Engine engine;
    private final int max;
    private int written;
    private final StringBuilder sb = new StringBuilder(320);
    private char[] cbuf = new char[512];

    public PriorityDump(Path file, Engine engine, int max) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.US_ASCII), 1 << 16);
        this.engine = engine; this.max = max;
    }

    @Override public void onViolation(long ts, int locate, long ref, byte side, int execShares, int orderShares, int orderPrice, int bestPrice,
                                      boolean isHead, long headRef, int queuePosition, int levelCount, int levelShares, byte tradingState,
                                      long nsSinceLastExec, long nsSinceLastChange) {
        if (written >= max) return;
        String sym = engine.symbol(locate);
        sb.setLength(0);
        sb.append("{\"ts\":").append(ts).append(",\"sym\":\"").append(sym == null ? "?" : sym).append("\",\"ref\":").append(ref)
          .append(",\"side\":\"").append((char) side).append("\",\"execShares\":").append(execShares).append(",\"orderShares\":").append(orderShares)
          .append(",\"orderPrice\":").append(orderPrice).append(",\"bestPrice\":").append(bestPrice).append(",\"isHead\":").append(isHead)
          .append(",\"headRef\":").append(headRef).append(",\"queuePos\":").append(queuePosition).append(",\"levelCount\":").append(levelCount)
          .append(",\"levelShares\":").append(levelShares).append(",\"state\":\"").append((char) tradingState).append("\",\"nsSinceLastExec\":").append(nsSinceLastExec)
          .append(",\"nsSinceLastChange\":").append(nsSinceLastChange).append("}\n");
        int n = sb.length(); if (n > cbuf.length) cbuf = new char[n]; sb.getChars(0, n, cbuf, 0);
        try { w.write(cbuf, 0, n); } catch (IOException e) { throw new UncheckedIOException(e); }
        written++;
    }
    public int written() { return written; }
    @Override public void close() throws IOException { w.close(); }
}
