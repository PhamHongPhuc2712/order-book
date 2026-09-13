package sg.phuc.lob.itch;

import java.io.IOException;
import java.io.InputStream;

/** Reads [len:2 big-endian][message] frames into a reusable buffer. Throws with byte offset on any truncation. */
public final class FrameReader implements Frames {
    private final InputStream in;
    private final byte[] buf = new byte[65536];
    private final byte[] hdr = new byte[2];
    private long frames, bytes;

    public FrameReader(InputStream in) { this.in = in; }

    /** @return message length now in buf(), or -1 at clean EOF. */
    public int next() throws IOException {
        int n = in.readNBytes(hdr, 0, 2);
        if (n == 0) return -1;
        if (n < 2) throw new IOException("truncated length prefix at byte " + bytes);
        int len = (hdr[0] & 0xFF) << 8 | (hdr[1] & 0xFF);
        if (len == 0 || len > buf.length) throw new IOException("bad frame length " + len + " at byte " + bytes);
        int m = in.readNBytes(buf, 0, len);
        if (m < len) throw new IOException("truncated message (" + m + "/" + len + ") at byte " + bytes);
        bytes += 2 + len; frames++;
        return len;
    }
    public byte[] buf() { return buf; }
    public long frames() { return frames; }
    public long bytes() { return bytes; }
    @Override public void close() throws IOException { in.close(); }
}
