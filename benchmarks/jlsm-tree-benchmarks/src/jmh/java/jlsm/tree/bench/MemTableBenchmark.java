package jlsm.tree.bench;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.memtable.ConcurrentSkipListMemTable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regression benchmark: ConcurrentSkipListMemTable put/get/scan throughput.
 *
 * <p>
 * Guards against degradation in:
 * <ul>
 * <li>{@code put} — skip list insertion + KeyComparator cost</li>
 * <li>{@code getHit} — point lookup via ceilingEntry probe (O(log n))</li>
 * <li>{@code getMiss} — negative lookup early-exit path</li>
 * <li>{@code scan} — full iterator traversal</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class MemTableBenchmark {

    @Param({ "1000", "10000", "100000" })
    int preloadCount;

    private ConcurrentSkipListMemTable memTable;
    private MemorySegment[] keys;
    private MemorySegment value;
    private AtomicLong seqCounter;
    private int keyMask;
    private int opIndex;

    @Setup(Level.Trial)
    public void setup() {
        memTable = new ConcurrentSkipListMemTable();
        seqCounter = new AtomicLong(1);

        int poolSize = Integer.highestOneBit(preloadCount);
        if (poolSize < preloadCount) {
            poolSize <<= 1;
        }
        keyMask = poolSize - 1;
        keys = new MemorySegment[poolSize];

        for (int i = 0; i < poolSize; i++) {
            keys[i] = MemorySegment
                    .ofArray(String.format("key-%08d", i).getBytes(StandardCharsets.UTF_8));
        }

        byte[] valBytes = new byte[128];
        java.util.Arrays.fill(valBytes, (byte) 0xAB);
        value = MemorySegment.ofArray(valBytes);

        for (int i = 0; i < preloadCount; i++) {
            long seq = seqCounter.getAndIncrement();
            memTable.apply(new Entry.Put(keys[i & keyMask], value, new SequenceNumber(seq)));
        }

        opIndex = 0;
    }

    @Benchmark
    public void put() {
        int idx = opIndex++ & keyMask;
        long seq = seqCounter.getAndIncrement();
        memTable.apply(new Entry.Put(keys[idx], value, new SequenceNumber(seq)));
    }

    @Benchmark
    public void getHit(Blackhole bh) {
        int idx = opIndex++ & keyMask;
        bh.consume(memTable.get(keys[idx]));
    }

    @Benchmark
    public void getMiss(Blackhole bh) {
        bh.consume(memTable.get(MemorySegment.ofArray(
                String.format("zzz-%08d", opIndex++ & keyMask).getBytes(StandardCharsets.UTF_8))));
    }

    @Benchmark
    public void scan(Blackhole bh) {
        Iterator<Entry> it = memTable.scan();
        int consumed = 0;
        while (it.hasNext() && consumed < 100) {
            bh.consume(it.next());
            consumed++;
        }
    }
}
