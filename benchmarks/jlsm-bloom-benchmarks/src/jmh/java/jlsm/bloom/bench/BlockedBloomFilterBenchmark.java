package jlsm.bloom.bench;

import jlsm.bloom.blocked.BlockedBloomFilter;
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

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class BlockedBloomFilterBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int insertionCount;

    private BlockedBloomFilter filter;
    private MemorySegment[] hitKeys;
    private MemorySegment[] missKeys;
    private int keyMask;
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        filter = new BlockedBloomFilter(insertionCount, 0.01);
        int capacity = Integer.highestOneBit(insertionCount - 1) << 1;
        keyMask = capacity - 1;

        hitKeys = new MemorySegment[capacity];
        missKeys = new MemorySegment[capacity];

        for (int i = 0; i < capacity; i++) {
            hitKeys[i]  = segmentOf("hit-"  + i);
            missKeys[i] = segmentOf("miss-" + i);
        }
        for (int i = 0; i < insertionCount; i++) {
            filter.add(hitKeys[i]);
        }
    }

    @Benchmark
    public boolean mightContainHit() {
        return filter.mightContain(hitKeys[index++ & keyMask]);
    }

    @Benchmark
    public boolean mightContainMiss() {
        return filter.mightContain(missKeys[index++ & keyMask]);
    }

    @Benchmark
    public void add() {
        filter.add(hitKeys[index++ & keyMask]);
    }

    private static MemorySegment segmentOf(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }
}
