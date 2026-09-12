package sg.phuc.lob.book;
import java.util.HashMap;
/** Naive: boxes every key. This is the baseline Phase 2 replaces with LongObjectMap. */
public final class HashOrderMap implements OrderMap {
    private final HashMap<Long, Order> m;
    public HashOrderMap(int expected) { m = new HashMap<>(expected * 2); }
    @Override public Order get(long ref) { return m.get(ref); }
    @Override public Order put(long ref, Order o) { return m.put(ref, o); }
    @Override public Order remove(long ref) { return m.remove(ref); }
    @Override public int size() { return m.size(); }
}
