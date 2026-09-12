package sg.phuc.lob.replay;

import org.HdrHistogram.Histogram;
import sg.phuc.lob.book.*;
import sg.phuc.lob.engine.*;
import sg.phuc.lob.itch.FrameReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

public final class Replay {
    public record Result(long messages, double wallSec, long p50, long p90, long p99, long p999, long max,
                         double bytesPerMsg, int liveOrders, String validation) {
        public double msgsPerSec() { return messages / wallSec; }
        @Override public String toString() {
            return String.format("messages=%d wall=%.1fs msgs/s=%,.0f alloc=%.1f B/msg live=%d%napply ns: p50=%d p90=%d p99=%d p99.9=%d max=%d%nvalidation=%s",
                messages, wallSec, msgsPerSec(), bytesPerMsg, liveOrders, p50, p90, p99, p999, max, validation);
        }
    }

    public static Result run(Path file, Set<String> symbols, Listener listener, Supplier<OrderMap> om, Engine.BookFactory bf, boolean hist) throws IOException {
        Engine eng = new Engine(om.get(), bf, listener, symbols);
        if (listener instanceof DerivedWriter dw) dw.setEngine(eng);
        Histogram h = hist ? new Histogram(10_000_000_000L, 3) : null;
        var tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long alloc0 = tmx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        try (var fr = new FrameReader(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            int len;
            if (h != null) {
                while ((len = fr.next()) >= 0) { long a = System.nanoTime(); eng.apply(fr.buf(), len); h.recordValue(System.nanoTime() - a); }
            } else {
                while ((len = fr.next()) >= 0) eng.apply(fr.buf(), len);
            }
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        long alloc = tmx.getThreadAllocatedBytes(tid) - alloc0;
        long n = eng.messages();
        return new Result(n, wall,
            h == null ? 0 : h.getValueAtPercentile(50), h == null ? 0 : h.getValueAtPercentile(90), h == null ? 0 : h.getValueAtPercentile(99),
            h == null ? 0 : h.getValueAtPercentile(99.9), h == null ? 0 : h.getMaxValue(),
            (double) alloc / n, eng.liveOrders(), eng.validation().toJson());
    }

    public static void main(String[] args) throws IOException {
        Path file = null; Set<String> syms = null; boolean hist = false; Path out = null;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--file" -> file = Path.of(args[++i]);
            case "--symbols" -> syms = Set.of(args[++i].split(","));
            case "--hist" -> hist = true;
            case "--out" -> out = Path.of(args[++i]);
            default -> throw new IllegalArgumentException(args[i]);
        }
        if (file == null) throw new IllegalArgumentException("--file is required");
        Listener l = out == null ? new NullListener() : new DerivedWriter(out);
        Result r = run(file, syms, l, () -> new HashOrderMap(1 << 20), TreeBook::new, hist);
        if (l instanceof DerivedWriter dw) dw.close();
        if (out != null) Files.writeString(out.resolve("validation.json"), r.validation());
        System.out.println(r);
    }
}
