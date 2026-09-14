package sg.phuc.lob.replay;

import org.HdrHistogram.Histogram;
import sg.phuc.lob.book.*;
import sg.phuc.lob.engine.*;
import sg.phuc.lob.itch.FrameReader;
import sg.phuc.lob.itch.Frames;
import sg.phuc.lob.itch.MappedFrameReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Replays one ITCH file through the engine and reports throughput, apply-latency percentiles (--hist), bytes allocated per
 * message, and the validation counters. Every Phase 2 optimisation is a flag so any combination is one command:
 *   --reader stream|mmap  --map hash|long  --book tree|array  --pool  --dedupe
 */
public final class Replay {
    /** One measurement configuration. Defaults are the naive Phase 1 baseline. */
    public record Config(String reader, String map, String book, boolean pool, boolean dedupe) {
        public static final Config NAIVE = new Config("stream", "hash", "tree", false, false);
        public static final Config FINAL = new Config("mmap", "long", "array", true, true);
        public Frames open(Path file) throws IOException {
            return switch (reader) {
                case "stream" -> new FrameReader(new BufferedInputStream(Files.newInputStream(file), 1 << 20));
                case "mmap" -> new MappedFrameReader(file);
                default -> throw new IllegalArgumentException("--reader " + reader);
            };
        }
        public OrderMap orderMap(int expected) {
            return switch (map) {
                case "hash" -> new HashOrderMap(expected);
                case "long" -> new LongOrderMap(expected);
                default -> throw new IllegalArgumentException("--map " + map);
            };
        }
        public Engine.BookFactory bookFactory() {
            return switch (book) {
                case "tree" -> TreeBook::new;
                case "array" -> ArrayBook::new;
                default -> throw new IllegalArgumentException("--book " + book);
            };
        }
        public String label() { return reader + "/" + map + "/" + book + (pool ? "/pool" : "") + (dedupe ? "/dedupe" : ""); }
    }

    public record Result(String config, long messages, double wallSec, long p50, long p90, long p99, long p999, long max,
                         double bytesPerMsg, int liveOrders, int poolCreated, String validation) {
        public double msgsPerSec() { return messages / wallSec; }
        @Override public String toString() {
            return String.format("config=%s messages=%d wall=%.1fs msgs/s=%,.0f alloc=%.1f B/msg live=%d poolCreated=%d%napply ns: p50=%d p90=%d p99=%d p99.9=%d max=%d%nvalidation=%s",
                config, messages, wallSec, msgsPerSec(), bytesPerMsg, liveOrders, poolCreated, p50, p90, p99, p999, max, validation);
        }
    }

    /** Validation-mode options: match-number tracking (D25) and the priority-violation dump. */
    public record Validate(boolean matches, Path priorityDump, int dumpMax) {
        public static final Validate OFF = new Validate(false, null, 0);
    }

    public static Result run(Path file, Config cfg, Set<String> symbols, Listener listener, boolean hist) throws IOException {
        return run(file, cfg, symbols, listener, hist, Validate.OFF);
    }

    public static Result run(Path file, Config cfg, Set<String> symbols, Listener listener, boolean hist, Validate val) throws IOException {
        OrderPool pool = cfg.pool() ? new OrderPool(1 << 16) : null;
        Engine eng = new Engine(cfg.orderMap(1 << 20), cfg.bookFactory(), listener, symbols, pool, cfg.dedupe());
        if (listener instanceof DerivedWriter dw) dw.setEngine(eng);
        if (val.matches()) {                                  // ~7 M printable matches per day: 2^25 slots, 256 MB; non-printable legs are rare
            LongSet printable = new LongSet(1 << 24), nonPrintable = new LongSet(1 << 16);
            eng.validation().enableMatchTracking(new Validation.MatchTracker() {
                @Override public boolean add(long m, boolean p) { return p ? printable.add(m) : nonPrintable.add(m); }
                @Override public boolean paired(long m) { return printable.contains(m) && nonPrintable.contains(m); }
                @Override public boolean contains(long m) { return printable.contains(m) || nonPrintable.contains(m); }
            });
        }
        PriorityDump dump = val.priorityDump() == null ? null : new PriorityDump(val.priorityDump(), eng, val.dumpMax());
        if (dump != null) eng.setPriorityHook(dump);
        Histogram h = hist ? new Histogram(10_000_000_000L, 3) : null;
        var tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long alloc0 = tmx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        try (Frames fr = cfg.open(file)) {
            int len;
            if (h != null) {
                while ((len = fr.next()) >= 0) { long a = System.nanoTime(); eng.apply(fr.buf(), len); h.recordValue(System.nanoTime() - a); }
            } else {
                while ((len = fr.next()) >= 0) eng.apply(fr.buf(), len);
            }
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        long alloc = tmx.getThreadAllocatedBytes(tid) - alloc0;
        if (dump != null) dump.close();
        long n = eng.messages();
        return new Result(cfg.label(), n, wall,
            h == null ? 0 : h.getValueAtPercentile(50), h == null ? 0 : h.getValueAtPercentile(90), h == null ? 0 : h.getValueAtPercentile(99),
            h == null ? 0 : h.getValueAtPercentile(99.9), h == null ? 0 : h.getMaxValue(),
            (double) alloc / n, eng.liveOrders(), pool == null ? 0 : pool.created(), eng.validation().toJson());
    }

    public static void main(String[] args) throws IOException {
        Path file = null; Set<String> syms = null; boolean hist = false; Path out = null;
        String reader = "stream", map = "hash", book = "tree"; boolean pool = false, dedupe = false, validate = false; int dumpN = 0;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--file" -> file = Path.of(args[++i]);
            case "--symbols" -> syms = Set.of(args[++i].split(","));
            case "--hist" -> hist = true;
            case "--out" -> out = Path.of(args[++i]);
            case "--reader" -> reader = args[++i];
            case "--map" -> map = args[++i];
            case "--book" -> book = args[++i];
            case "--pool" -> pool = true;
            case "--dedupe" -> dedupe = true;
            case "--final" -> { reader = "mmap"; map = "long"; book = "array"; pool = true; dedupe = true; }
            case "--validate" -> validate = true;
            case "--dump-priority" -> dumpN = Integer.parseInt(args[++i]);
            default -> throw new IllegalArgumentException(args[i]);
        }
        if (file == null) throw new IllegalArgumentException("--file is required");
        Config cfg = new Config(reader, map, book, pool, dedupe);
        if (dumpN > 0 && out == null) throw new IllegalArgumentException("--dump-priority needs --out DIR (writes DIR/priority.ndjson)");
        Validate val = new Validate(validate, dumpN > 0 ? out.resolve("priority.ndjson") : null, dumpN);
        Listener l = out == null ? new NullListener() : new DerivedWriter(out);
        Result r = run(file, cfg, syms, l, hist, val);
        if (l instanceof DerivedWriter dw) dw.close();
        if (out != null) Files.writeString(out.resolve("validation.json"), r.validation());
        System.out.println(r);
    }
}
