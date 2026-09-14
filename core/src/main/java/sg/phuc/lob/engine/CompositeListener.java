package sg.phuc.lob.engine;

/** Fans every Listener call out to each wrapped listener, in order. */
public final class CompositeListener implements Listener {
    private final Listener[] ls;
    public CompositeListener(Listener... ls) { this.ls = ls; }
    @Override public void onSystemEvent(long ts, char code) { for (Listener l : ls) l.onSystemEvent(ts, code); }
    @Override public void onAdd(long ts, int locate, long ref, byte side, int shares, int price) { for (Listener l : ls) l.onAdd(ts, locate, ref, side, shares, price); }
    @Override public void onBbo(long ts, int locate, int bid, int bidSh, int ask, int askSh) { for (Listener l : ls) l.onBbo(ts, locate, bid, bidSh, ask, askSh); }
    @Override public void onExecution(long ts, int locate, long ref, byte side, int shares, int price, long match, boolean printable) { for (Listener l : ls) l.onExecution(ts, locate, ref, side, shares, price, match, printable); }
    @Override public void onCancel(long ts, int locate, long ref, byte side, int shares, int price) { for (Listener l : ls) l.onCancel(ts, locate, ref, side, shares, price); }
    @Override public void onTrade(long ts, int locate, char kind, int shares, int price, long match, char crossType) { for (Listener l : ls) l.onTrade(ts, locate, kind, shares, price, match, crossType); }
    public Listener[] listeners() { return ls; }
}
