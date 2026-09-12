package sg.phuc.lob.book;
/** One price level: FIFO queue of orders. shares == Σ order.shares, count == list length (property-tested). */
public final class Level {
    public final int price; public int shares; public int count; public Order head, tail;
    public Level(int price) { this.price = price; }
    public void append(Order o) {
        o.level = this; o.prev = tail; o.next = null;
        if (tail == null) head = o; else tail.next = o;
        tail = o; shares += o.shares; count++;
    }
    public void remove(Order o) {
        if (o.prev == null) head = o.next; else o.prev.next = o.next;
        if (o.next == null) tail = o.prev; else o.next.prev = o.prev;
        o.prev = o.next = null; o.level = null; shares -= o.shares; count--;
    }
    public void reduce(Order o, int by) { o.shares -= by; shares -= by; }
}
