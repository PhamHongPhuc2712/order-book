package sg.phuc.lob.book;

/** Open-addressing set of primitive longs, same probing as LongObjectMap, no deletion. Key 0 is EMPTY. */
public final class LongSet {
    private long[] keys; private int mask, size, resizeAt;

    public LongSet(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        keys = new long[cap]; mask = cap - 1; resizeAt = cap / 2;
    }
    private static int mix(long k) { k *= 0x9E3779B97F4A7C15L; return (int) (k ^ (k >>> 32)); }

    public boolean contains(long k) {
        int i = mix(k) & mask;
        while (true) { long c = keys[i]; if (c == k) return true; if (c == 0) return false; i = (i + 1) & mask; }
    }
    /** @return true if k was newly added, false if already present. */
    public boolean add(long k) {
        if (k == 0) throw new IllegalArgumentException("key 0 is reserved");
        if (size >= resizeAt) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) return false;
            if (c == 0) { keys[i] = k; size++; return true; }
            i = (i + 1) & mask;
        }
    }
    public int size() { return size; }
    private void grow() {
        long[] ok = keys;
        int cap = ok.length << 1; keys = new long[cap]; mask = cap - 1; resizeAt = cap / 2; size = 0;
        for (long k : ok) if (k != 0) add(k);
    }
}
