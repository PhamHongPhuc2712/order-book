package sg.phuc.lob.book;
public interface Book {
    int locate();
    Level level(byte side, int price, boolean create);
    void removeLevel(byte side, int price);
    Level bestLevel(byte side);
    int bestBid(); int bestAsk(); int bestShares(byte side);
    boolean isCrossed();
    int levels(byte side);
    /** Total live orders on both sides. Not on the hot path; used for end-of-day reporting. */
    int orderCount();
    /** Level at depth index (0 = best) on a side, or null. Not on the hot path; used by ladder/demo writers. */
    Level levelAt(byte side, int index);
}
