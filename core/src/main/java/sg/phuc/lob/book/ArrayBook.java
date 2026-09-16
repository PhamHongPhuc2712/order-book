package sg.phuc.lob.book;

import java.util.Arrays;

/** Sorted-array book: per side, prices[] (best at index 0) and parallel levels[]. Binary search + arraycopy. */
public final class ArrayBook implements Book {
    private final int locate;
    private int[] bp = new int[64], ap = new int[64]; private Level[] bl = new Level[64], al = new Level[64]; private int nb, na;
    public ArrayBook(int locate) { this.locate = locate; }
    @Override public int locate() { return locate; }

    /** index of price on side, or -(insertion point)-1. Bids descending, asks ascending. */
    private int find(byte s, int price) {
        int[] p = s == 'B' ? bp : ap; int n = s == 'B' ? nb : na;
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, v = p[mid];
            int cmp = s == 'B' ? Integer.compare(price, v) : Integer.compare(v, price);   // bids: larger price sorts first
            if (cmp == 0) return mid;
            if (cmp > 0) hi = mid - 1; else lo = mid + 1;
        }
        return -(lo + 1);
    }
    @Override public Level level(byte s, int price, boolean create) {
        int i = find(s, price);
        if (i >= 0) return (s == 'B' ? bl : al)[i];
        if (!create) return null;
        int ins = -i - 1;
        if (s == 'B') {
            if (nb == bp.length) { bp = Arrays.copyOf(bp, nb * 2); bl = Arrays.copyOf(bl, nb * 2); }
            System.arraycopy(bp, ins, bp, ins + 1, nb - ins); System.arraycopy(bl, ins, bl, ins + 1, nb - ins);
            Level lv = new Level(price); bp[ins] = price; bl[ins] = lv; nb++; return lv;
        } else {
            if (na == ap.length) { ap = Arrays.copyOf(ap, na * 2); al = Arrays.copyOf(al, na * 2); }
            System.arraycopy(ap, ins, ap, ins + 1, na - ins); System.arraycopy(al, ins, al, ins + 1, na - ins);
            Level lv = new Level(price); ap[ins] = price; al[ins] = lv; na++; return lv;
        }
    }
    @Override public void removeLevel(byte s, int price) {
        int i = find(s, price); if (i < 0) return;
        if (s == 'B') { System.arraycopy(bp, i + 1, bp, i, nb - i - 1); System.arraycopy(bl, i + 1, bl, i, nb - i - 1); bl[--nb] = null; }
        else          { System.arraycopy(ap, i + 1, ap, i, na - i - 1); System.arraycopy(al, i + 1, al, i, na - i - 1); al[--na] = null; }
    }
    @Override public Level bestLevel(byte s) { return s == 'B' ? (nb == 0 ? null : bl[0]) : (na == 0 ? null : al[0]); }
    @Override public int bestBid() { return nb == 0 ? 0 : bp[0]; }
    @Override public int bestAsk() { return na == 0 ? 0 : ap[0]; }
    @Override public int bestShares(byte s) { Level lv = bestLevel(s); return lv == null ? 0 : lv.shares; }
    @Override public boolean isCrossed() { return nb > 0 && na > 0 && bp[0] >= ap[0]; }
    @Override public int levels(byte s) { return s == 'B' ? nb : na; }
    @Override public int orderCount() {
        int n = 0;
        for (int i = 0; i < nb; i++) n += bl[i].count;
        for (int i = 0; i < na; i++) n += al[i].count;
        return n;
    }
    @Override public Level levelAt(byte s, int index) {
        if (index < 0) return null;
        return s == 'B' ? (index < nb ? bl[index] : null) : (index < na ? al[index] : null);
    }
}
