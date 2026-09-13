package sg.phuc.lob.book;

import net.jqwik.api.*;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LongSetPropertyTest {
    @Property(tries = 300) void matchesHashSet(@ForAll("keys") List<Long> keys) {
        var ref = new HashSet<Long>(); var s = new LongSet(2);
        for (long k : keys) { assertEquals(ref.add(k), s.add(k)); assertEquals(ref.size(), s.size()); }
        for (long k = 1; k <= 200; k++) assertEquals(ref.contains(k), s.contains(k));
    }
    @Provide Arbitrary<List<Long>> keys() { return Arbitraries.longs().between(1, 150).list().ofMaxSize(2000); }
}
