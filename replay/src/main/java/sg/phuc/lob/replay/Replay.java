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
        for (Listener l : listener instanceof CompositeListener c ? c.listeners() : new Listener[]{listener}) {
            if (l instanceof DerivedWriter dw) dw.setEngine(eng);
            if (l instanceof MeatPyExport mx) mx.setEngine(eng);
            if (l instanceof QueueProbe qp) qp.setEngine(eng);
            if (l instanceof LadderWriter lw) lw.setEngine(eng);
        }
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

    static Set<String> probeSymbols(String arg) throws IOException {
        String text = arg.startsWith("@") ? Files.readString(Path.of(arg.substring(1))) : arg;
        Set<String> s = new java.util.HashSet<>();
        for (String t : text.split("[,\s]+")) if (!t.isEmpty()) s.add(t);
        return s;
    }

    public static void main(String[] args) throws IOException {
        Path file = null; Set<String> syms = null; boolean hist = false; Path out = null;
        String reader = "stream", map = "hash", book = "tree", meatpy = null, probes = null, ladder = null;
        long ladderFrom = 33_880_000_000_000L, ladderTo = 34_320_000_000_000L;   // 9:28:00 .. 9:32:00, through the opening cross
        boolean pool = false, dedupe = false, validate = false, derived = true; int dumpN = 0;
        double probeEvery = 1, probeCensor = 60;
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
            case "--meatpy" -> meatpy = args[++i];                       // top-of-book at 1-minute marks for one symbol -> DIR/meatpy_SYM.csv
            case "--no-derived" -> derived = false;                        // with --out: skip the NDJSON writer (validation.json / meatpy only)
            case "--probe-symbols" -> probes = args[++i];                  // A,B,C or @file (one symbol per line or comma-separated) -> DIR/episodes.ndjson
            case "--probe-every" -> probeEvery = Double.parseDouble(args[++i]);     // seconds between joiner samples (default 1)
            case "--probe-censor" -> probeCensor = Double.parseDouble(args[++i]);   // seconds before an open episode is censored T (default 60)
            case "--ladder" -> ladder = args[++i];                        // top-10 ladder of one symbol on a 100 ms grid -> DIR/ladder_SYM.ndjson (demo)
            case "--ladder-from" -> ladderFrom = Long.parseLong(args[++i]);
            case "--ladder-to" -> ladderTo = Long.parseLong(args[++i]);
            default -> throw new IllegalArgumentException(args[i]);
        }
        if (file == null) throw new IllegalArgumentException("--file is required");
        Config cfg = new Config(reader, map, book, pool, dedupe);
        if (dumpN > 0 && out == null) throw new IllegalArgumentException("--dump-priority needs --out DIR (writes DIR/priority.ndjson)");
        Validate val = new Validate(validate, dumpN > 0 ? out.resolve("priority.ndjson") : null, dumpN);
        if (meatpy != null && out == null) throw new IllegalArgumentException("--meatpy needs --out DIR");
        if (probes != null && out == null) throw new IllegalArgumentException("--probe-symbols needs --out DIR");
        if (ladder != null && out == null) throw new IllegalArgumentException("--ladder needs --out DIR");
        java.util.List<Listener> ls = new java.util.ArrayList<>();
        if (out != null && derived) ls.add(new DerivedWriter(out));
        if (meatpy != null) ls.add(new MeatPyExport(out.resolve("meatpy_" + meatpy + ".csv"), meatpy, 34_200_000_000_000L, 57_600_000_000_000L));
        if (ladder != null) {
            Files.createDirectories(out);
            ls.add(new LadderWriter(ladder, ladderFrom, ladderTo, 100_000_000L, 10,
                new java.io.BufferedWriter(Files.newBufferedWriter(out.resolve("ladder_" + ladder + ".ndjson"), java.nio.charset.StandardCharsets.US_ASCII), 1 << 20)));
        }
        if (probes != null) {
            Files.createDirectories(out);
            ls.add(new QueueProbe(probeSymbols(probes), (long) (probeEvery * 1e9), (long) (probeCensor * 1e9),
                new java.io.BufferedWriter(Files.newBufferedWriter(out.resolve("episodes.ndjson"), java.nio.charset.StandardCharsets.US_ASCII), 1 << 20)));
        }
        Listener l = ls.isEmpty() ? new NullListener() : ls.size() == 1 ? ls.get(0) : new CompositeListener(ls.toArray(new Listener[0]));
        Result r = run(file, cfg, syms, l, hist, val);
        for (Listener x : ls) if (x instanceof AutoCloseable c) { try { c.close(); } catch (Exception e) { throw new IOException(e); } }
        if (out != null) Files.writeString(out.resolve("validation.json"), r.validation());
        System.out.println(r);
    }
}
