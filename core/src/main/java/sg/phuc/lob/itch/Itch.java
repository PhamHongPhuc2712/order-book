package sg.phuc.lob.itch;

import java.nio.charset.StandardCharsets;

/** Big-endian field accessors over a raw ITCH 5.0 message in b[0..len). Allocation-free except alpha(). */
public final class Itch {
    private Itch() {}
    public static int  u8 (byte[] b, int o) { return b[o] & 0xFF; }
    public static int  u16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }
    public static int  u32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
    public static long u48(byte[] b, int o) { long v = 0; for (int i = 0; i < 6; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }
    public static long u64(byte[] b, int o) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }
    public static char type(byte[] b)      { return (char) b[0]; }
    public static int  locate(byte[] b)    { return u16(b, 1); }
    public static long timestamp(byte[] b) { return u48(b, 5); }          // ns since midnight
    public static String alpha(byte[] b, int o, int n) {
        int e = o + n; while (e > o && b[e - 1] == ' ') e--;
        return new String(b, o, e - o, StandardCharsets.US_ASCII);
    }
    /** Message length from the spec, or -1 for an unknown type. */
    public static int expectedLength(char t) {
        return switch (t) {
            case 'S' -> 12; case 'R' -> 39; case 'H' -> 25; case 'Y' -> 20; case 'L' -> 26; case 'V' -> 35; case 'W' -> 12;
            case 'K' -> 28; case 'J' -> 35; case 'h' -> 21; case 'A' -> 36; case 'F' -> 40; case 'E' -> 31; case 'C' -> 36;
            case 'X' -> 23; case 'D' -> 19; case 'U' -> 35; case 'P' -> 44; case 'Q' -> 40; case 'B' -> 19; case 'I' -> 50;
            case 'N' -> 20; case 'O' -> 48; default -> -1;
        };
    }
}
