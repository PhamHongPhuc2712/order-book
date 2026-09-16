package sg.phuc.lob.book;

import java.util.Comparator;
import java.util.TreeMap;

/** Naive book: TreeMap per side. Bids descending so firstKey() is best on both sides. */
public final class TreeBook implements Book {
    private final int locate;
    private final TreeMap<Integer, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Integer, Level> asks = new TreeMap<>();
    public TreeBook(int locate) { this.locate = locate; }
    private TreeMap<Integer, Level> side(byte s) { return s == 'B' ? bids : asks; }
    @Override public int locate() { return locate; }
    @Override public Level level(byte s, int price, boolean create) {
        TreeMap<Integer, Level> m = side(s);
        Level lv = m.get(price);
        if (lv == null && create) { lv = new Level(price); m.put(price, lv); }
        return lv;
    }
    @Override public void removeLevel(byte s, int price) { side(s).remove(price); }
    @Override public Level bestLevel(byte s) { var e = side(s).firstEntry(); return e == null ? null : e.getValue(); }
    @Override public int bestBid() { return bids.isEmpty() ? 0 : bids.firstKey(); }
    @Override public int bestAsk() { return asks.isEmpty() ? 0 : asks.firstKey(); }
    @Override public int bestShares(byte s) { Level lv = bestLevel(s); return lv == null ? 0 : lv.shares; }
    @Override public boolean isCrossed() { return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey(); }
    @Override public int levels(byte s) { return side(s).size(); }
    @Override public Level levelAt(byte s, int index) {
        if (index < 0) return null;
        for (Level lv : side(s).values()) if (index-- == 0) return lv;
        return null;
    }
    @Override public int orderCount() {
        int n = 0;
        for (Level lv : bids.values()) n += lv.count;
        for (Level lv : asks.values()) n += lv.count;
        return n;
    }
}
