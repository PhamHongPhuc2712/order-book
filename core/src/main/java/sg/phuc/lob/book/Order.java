package sg.phuc.lob.book;
/** Intrusive list node. Public mutable fields are deliberate: this is the hot path, and Phase 2 pools these. */
public final class Order {
    public long ref; public int locate; public byte side; public int shares; public int price; public long ts;
    public Order prev, next; public Level level;
    public Order(long ref, int locate, byte side, int shares, int price, long ts) { reset(ref, locate, side, shares, price, ts); }
    public void reset(long ref, int locate, byte side, int shares, int price, long ts) {
        this.ref = ref; this.locate = locate; this.side = side; this.shares = shares; this.price = price; this.ts = ts;
        prev = next = null; level = null;
    }
}
