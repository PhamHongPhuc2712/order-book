package sg.phuc.lob.engine;
public interface Listener {
    default void onSystemEvent(long ts, char code) {}
    default void onAdd(long ts, int locate, long ref, byte side, int shares, int price) {}
    default void onBbo(long ts, int locate, int bid, int bidSh, int ask, int askSh) {}
    default void onExecution(long ts, int locate, long ref, byte side, int shares, int price, long match, boolean printable) {}
    default void onCancel(long ts, int locate, long ref, byte side, int shares, int price) {}
    default void onTrade(long ts, int locate, char kind, int shares, int price, long match, char crossType) {}
}
