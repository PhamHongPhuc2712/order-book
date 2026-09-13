package sg.phuc.lob.book;

/** Free list of Order objects threaded through Order.next. take() pops or allocates; give() resets and pushes. */
public final class OrderPool {
    private Order free;
    private int freeCount, created;

    public OrderPool(int initial) { for (int i = 0; i < initial; i++) { Order o = new Order(0, 0, (byte) 0, 0, 0, 0); created++; push(o); } }
    private void push(Order o) { o.prev = null; o.level = null; o.next = free; free = o; freeCount++; }

    public Order take(long ref, int locate, byte side, int shares, int price, long ts) {
        Order o = free;
        if (o == null) { created++; return new Order(ref, locate, side, shares, price, ts); }
        free = o.next; freeCount--;
        o.reset(ref, locate, side, shares, price, ts);
        return o;
    }
    /** The order must already be unlinked from its level; callers give back only after listeners have seen it. */
    public void give(Order o) { push(o); }
    public int free() { return freeCount; }
    public int created() { return created; }
}
