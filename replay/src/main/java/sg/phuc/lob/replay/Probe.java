package sg.phuc.lob.replay;

import sg.phuc.lob.itch.Itch;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Day-1 framing check: read [len:2][msg] frames, verify len == spec length for the type, histogram types. */
public final class Probe {
    static InputStream open(Path p) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20);
        return p.toString().endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
    }
    public static void main(String[] args) throws IOException {
        Path p = Path.of(args[0]); long max = args.length > 1 ? Long.parseLong(args[1]) : Long.MAX_VALUE;
        InputStream in = open(p);
        byte[] hdr = new byte[2], buf = new byte[65536];
        long[] hist = new long[128]; long frames = 0, bytes = 0, mismatch = 0; char firstS = 0;
        long t0 = System.nanoTime();
        try (in) {
            while (frames < max) {
                if (in.readNBytes(hdr, 0, 2) < 2) break;
                int len = (hdr[0] & 0xFF) << 8 | (hdr[1] & 0xFF);
                if (len == 0 || len > buf.length) { System.out.println("bad length " + len + " at byte " + bytes); break; }
                if (in.readNBytes(buf, 0, len) < len) { System.out.println("truncated at byte " + bytes); break; }
                char t = Itch.type(buf);
                if (t < 128) hist[t]++;
                int exp = Itch.expectedLength(t);
                if (exp != len) mismatch++;
                if (t == 'S' && firstS == 0) firstS = (char) buf[11];
                frames++; bytes += 2 + len;
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("frames=%d bytes=%d mismatch=%d firstSystemEvent=%c secs=%.1f frames/s=%.0f%n", frames, bytes, mismatch, firstS, s, frames / s);
        for (int i = 0; i < 128; i++) if (hist[i] > 0) System.out.printf("  %c %d%n", (char) i, hist[i]);
    }
}
