package sg.phuc.lob.engine;

/** Hand-encodes ITCH 5.0 messages at the offsets in the spec, so every engine path is tested from raw bytes. */
final class Msg {
    static void p16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
    static void p32(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (24 - 8 * i)); }
    static void p48(byte[] b, int o, long v) { for (int i = 0; i < 6; i++) b[o + i] = (byte) (v >> (40 - 8 * i)); }
    static void p64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >> (56 - 8 * i)); }
    static void alpha(byte[] b, int o, int n, String s) { for (int i = 0; i < n; i++) b[o + i] = (byte) (i < s.length() ? s.charAt(i) : ' '); }
    static byte[] hdr(char t, int len, int loc, long ts) { byte[] b = new byte[len]; b[0] = (byte) t; p16(b, 1, loc); p16(b, 3, 0); p48(b, 5, ts); return b; }

    static byte[] sys(long ts, char code) { byte[] b = hdr('S', 12, 0, ts); b[11] = (byte) code; return b; }
    static byte[] dir(int loc, long ts, String stock) { byte[] b = hdr('R', 39, loc, ts); alpha(b, 11, 8, stock); alpha(b, 19, 20, ""); return b; }
    static byte[] action(int loc, long ts, String stock, char state) { byte[] b = hdr('H', 25, loc, ts); alpha(b, 11, 8, stock); b[19] = (byte) state; alpha(b, 20, 5, ""); return b; }
    static byte[] add(int loc, long ts, long ref, char side, int sh, String stock, int px) { byte[] b = hdr('A', 36, loc, ts); p64(b, 11, ref); b[19] = (byte) side; p32(b, 20, sh); alpha(b, 24, 8, stock); p32(b, 32, px); return b; }
    static byte[] exec(int loc, long ts, long ref, int sh, long match) { byte[] b = hdr('E', 31, loc, ts); p64(b, 11, ref); p32(b, 19, sh); p64(b, 23, match); return b; }
    static byte[] execPx(int loc, long ts, long ref, int sh, long match, char printable, int px) { byte[] b = hdr('C', 36, loc, ts); p64(b, 11, ref); p32(b, 19, sh); p64(b, 23, match); b[31] = (byte) printable; p32(b, 32, px); return b; }
    static byte[] cancel(int loc, long ts, long ref, int sh) { byte[] b = hdr('X', 23, loc, ts); p64(b, 11, ref); p32(b, 19, sh); return b; }
    static byte[] delete(int loc, long ts, long ref) { byte[] b = hdr('D', 19, loc, ts); p64(b, 11, ref); return b; }
    static byte[] replace(int loc, long ts, long oldRef, long newRef, int sh, int px) { byte[] b = hdr('U', 35, loc, ts); p64(b, 11, oldRef); p64(b, 19, newRef); p32(b, 27, sh); p32(b, 31, px); return b; }
}
