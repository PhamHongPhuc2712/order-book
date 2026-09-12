package sg.phuc.lob.replay;

import sg.phuc.lob.itch.FrameReader;
import sg.phuc.lob.itch.Itch;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Writes a subset ITCH file: every locate-0 message plus every message for the chosen symbols, same framing, same order.
 * Optional [fromTs toTs] (ns since midnight) keeps the chosen locates' messages only inside the window, but always keeps
 * their R/H messages so the directory and trading state are intact.
 */
public final class Filter {
    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        Set<String> syms = Set.of(args[2].split(","));
        long fromTs = args.length > 4 ? Long.parseLong(args[3]) : 0, toTs = args.length > 4 ? Long.parseLong(args[4]) : Long.MAX_VALUE;
        boolean[] keep = new boolean[65536]; keep[0] = true;
        long kept = 0, total = 0;
        try (var fr = new FrameReader(new BufferedInputStream(Files.newInputStream(in), 1 << 20));
             var os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            int len;
            while ((len = fr.next()) >= 0) {
                total++;
                byte[] b = fr.buf();
                int loc = Itch.locate(b);
                char t = Itch.type(b);
                if (t == 'R' && syms.contains(Itch.alpha(b, 11, 8))) keep[loc] = true;
                if (!keep[loc]) continue;
                if (loc != 0 && t != 'R' && t != 'H') { long ts = Itch.timestamp(b); if (ts < fromTs || ts >= toTs) continue; }
                os.write(len >> 8); os.write(len & 0xFF); os.write(b, 0, len); kept++;
            }
        }
        System.out.printf("kept %d of %d frames -> %s (%d bytes)%n", kept, total, out, Files.size(out));
    }
}
