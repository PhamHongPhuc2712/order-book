package sg.phuc.lob.book;
/** OrderMap over LongObjectMap: no boxed keys, no entry objects. */
public final class LongOrderMap implements OrderMap {
    private final LongObjectMap<Order> m;
    public LongOrderMap(int expected) { m = new LongObjectMap<>(expected); }
    @Override public Order get(long ref) { return m.get(ref); }
    @Override public Order put(long ref, Order o) { return m.put(ref, o); }
    @Override public Order remove(long ref) { return m.remove(ref); }
    @Override public int size() { return m.size(); }
}
