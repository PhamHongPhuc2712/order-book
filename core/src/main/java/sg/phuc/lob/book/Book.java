package sg.phuc.lob.book;
public interface Book {
    int locate();
    Level level(byte side, int price, boolean create);
    void removeLevel(byte side, int price);
    Level bestLevel(byte side);
    int bestBid(); int bestAsk(); int bestShares(byte side);
    boolean isCrossed();
    int levels(byte side);
}
