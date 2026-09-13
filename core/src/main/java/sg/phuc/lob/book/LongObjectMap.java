package sg.phuc.lob.book;

/** Open-addressing hash map with primitive long keys, linear probing, backward-shift deletion. Key 0 is EMPTY. */
public final class LongObjectMap<V> {
    private long[] keys; private Object[] vals; private int mask, size, resizeAt;

    public LongObjectMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        keys = new long[cap]; vals = new Object[cap]; mask = cap - 1; resizeAt = cap / 2;
    }
    private static int mix(long k) { k *= 0x9E3779B97F4A7C15L; return (int) (k ^ (k >>> 32)); }

    @SuppressWarnings("unchecked")
    public V get(long k) {
        int i = mix(k) & mask;
        while (true) { long c = keys[i]; if (c == k) return (V) vals[i]; if (c == 0) return null; i = (i + 1) & mask; }
    }
    @SuppressWarnings("unchecked")
    public V put(long k, V v) {
        if (k == 0) throw new IllegalArgumentException("key 0 is reserved");
        if (size >= resizeAt) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) { V old = (V) vals[i]; vals[i] = v; return old; }
            if (c == 0) { keys[i] = k; vals[i] = v; size++; return null; }
            i = (i + 1) & mask;
        }
    }
    @SuppressWarnings("unchecked")
    public V remove(long k) {
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == 0) return null;
            if (c == k) {
                V old = (V) vals[i];
                // backward shift: pull later entries of the same probe chain up so no tombstones are needed
                int j = i;
                while (true) {
                    j = (j + 1) & mask;
                    long cj = keys[j];
                    if (cj == 0) break;
                    int home = mix(cj) & mask;
                    boolean inRange = (i <= j) ? (home > i && home <= j) : (home > i || home <= j);   // entry may not move past its home
                    if (!inRange) { keys[i] = cj; vals[i] = vals[j]; i = j; }
                }
                keys[i] = 0; vals[i] = null; size--;
                return old;
            }
            i = (i + 1) & mask;
        }
    }
    public int size() { return size; }
    public int capacity() { return keys.length; }
    @SuppressWarnings("unchecked")
    private void grow() {
        long[] ok = keys; Object[] ov = vals;
        int cap = ok.length << 1; keys = new long[cap]; vals = new Object[cap]; mask = cap - 1; resizeAt = cap / 2; size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != 0) put(ok[i], (V) ov[i]);
    }
}
