package sg.phuc.lob.itch;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped [len:2][msg] reader with the same contract as FrameReader. A MappedByteBuffer is limited to 2 GB, so the
 * file is mapped in 1 GB windows; a window is re-mapped from the current frame start whenever the frame would cross its end
 * (a handful of times per 8 GB day). Uses no preview APIs (java.lang.foreign is still preview on JDK 21).
 */
public final class MappedFrameReader implements Frames {
    static final long WINDOW = 1L << 30;
    private final FileChannel ch;
    private final long size;
    private final byte[] buf = new byte[65536];
    private MappedByteBuffer map;
    private long mapStart = -1;
    private long pos, frames;

    public MappedFrameReader(Path p) throws IOException {
        ch = FileChannel.open(p, StandardOpenOption.READ);
        size = ch.size();
    }

    /** Make [pos, pos+n) addressable in map. */
    private void ensure(int n) throws IOException {
        if (map == null || pos < mapStart || pos + n > mapStart + map.capacity()) {
            map = ch.map(FileChannel.MapMode.READ_ONLY, pos, Math.min(WINDOW, size - pos));
            mapStart = pos;
        }
    }

    @Override public int next() throws IOException {
        if (pos == size) return -1;
        if (size - pos < 2) throw new IOException("truncated length prefix at byte " + pos);
        ensure(2);
        int o = (int) (pos - mapStart);
        int len = (map.get(o) & 0xFF) << 8 | (map.get(o + 1) & 0xFF);
        if (len == 0 || len > buf.length) throw new IOException("bad frame length " + len + " at byte " + pos);
        if (size - pos - 2 < len) throw new IOException("truncated message (" + (size - pos - 2) + "/" + len + ") at byte " + pos);
        ensure(2 + len);
        map.get((int) (pos - mapStart) + 2, buf, 0, len);
        pos += 2 + len; frames++;
        return len;
    }
    @Override public byte[] buf() { return buf; }
    @Override public long frames() { return frames; }
    @Override public long bytes() { return pos; }
    @Override public void close() throws IOException { map = null; ch.close(); }
}
