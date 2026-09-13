package sg.phuc.lob.itch;

import java.io.IOException;

/** A source of [len:2][msg] frames. next() fills buf() and returns the message length, or -1 at clean EOF. */
public interface Frames extends AutoCloseable {
    int next() throws IOException;
    byte[] buf();
    long frames();
    long bytes();
    @Override void close() throws IOException;
}
