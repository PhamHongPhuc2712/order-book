package sg.phuc.lob.book;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LongObjectMapPropertyTest {
    @Provide Arbitrary<List<long[]>> ops() {
        // [op 0=put 1=remove 2=get, key 1..64] — small key space forces collisions, re-puts and backward shifts
        return Combinators.combine(Arbitraries.integers().between(0, 2), Arbitraries.longs().between(1, 64))
            .as((op, k) -> new long[]{op, k}).list().ofMinSize(1).ofMaxSize(2000);
    }
    @Property(tries = 400) void matchesHashMap(@ForAll("ops") List<long[]> ops) {
        var ref = new HashMap<Long, String>(); var m = new LongObjectMap<String>(4);
        for (long[] o : ops) {
            long k = o[1]; String v = "v" + k;
            switch ((int) o[0]) {
                case 0 -> assertEquals(ref.put(k, v), m.put(k, v));
                case 1 -> assertEquals(ref.remove(k), m.remove(k));
                default -> assertEquals(ref.get(k), m.get(k));
            }
            assertEquals(ref.size(), m.size());
        }
        for (var e : ref.entrySet()) assertEquals(e.getValue(), m.get(e.getKey()));   // every surviving key still reachable after shifts
    }
    @Property(tries = 100) void growsBeyondInitialCapacity(@ForAll @IntRange(min = 1, max = 5000) int n) {
        var m = new LongObjectMap<Integer>(2);
        for (long k = 1; k <= n; k++) m.put(k * 7919, (int) k);
        assertEquals(n, m.size());
        for (long k = 1; k <= n; k++) assertEquals((int) k, m.get(k * 7919));
    }
    @Example void keyZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LongObjectMap<String>(4).put(0, "x"));
    }
}
