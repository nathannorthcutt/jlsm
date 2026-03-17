package jlsm.tree.bench;

import jlsm.cache.LruBlockCache;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regression benchmark: LruBlockCache throughput and lock contention.
 *
 * <p>Guards against:
 * <ul>
 *   <li>Single-threaded throughput regression (putWithEviction, mixedGetPut)</li>
 *   <li>Multi-threaded lock contention degradation (mixedWorkload at 1, 2, 4, 8 threads)</li>
 * </ul>
 *
 * <p>The contention benchmarks establish a baseline for the current single-ReentrantLock
 * design. When the cache is reworked (e.g., striped/sharded), these benchmarks will
 * quantify the improvement.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class LruBlockCacheBenchmark {

    // ── Shared state ─────────────────────────────────────────────────────

    @State(Scope.Benchmark)
    public static class CacheState {

        @Param({"1000", "10000"})
        int cacheCapacity;

        LruBlockCache cache;
        MemorySegment block;
        AtomicLong globalCounter;

        static final int HOT_KEY_COUNT = 256;

        @Setup(Level.Trial)
        public void setup() {
            cache = LruBlockCache.builder().capacity(cacheCapacity).build();

            byte[] blockBytes = new byte[4096];
            ThreadLocalRandom.current().nextBytes(blockBytes);
            block = MemorySegment.ofArray(blockBytes);

            globalCounter = new AtomicLong(0);

            // Pre-populate to capacity with sstableId=0
            for (int i = 0; i < cacheCapacity; i++) {
                cache.put(0, i, block);
            }
            globalCounter.set(cacheCapacity);
        }
    }

    @State(Scope.Thread)
    public static class ThreadIndex {
        int idx;

        @Setup(Level.Iteration)
        public void setup() {
            idx = 0;
        }
    }

    // ── Single-threaded baselines ────────────────────────────────────────

    @Benchmark
    @Threads(1)
    public void putWithEviction(CacheState cs, ThreadIndex ti) {
        long offset = cs.globalCounter.getAndIncrement();
        cs.cache.put(1, offset, cs.block);
    }

    @Benchmark
    @Threads(1)
    public void mixedGetPut(CacheState cs, ThreadIndex ti, Blackhole bh) {
        doMixedOp(cs, ti, bh);
    }

    // ── Multi-threaded contention ────────────────────────────────────────

    @Benchmark
    @Threads(1)
    public void contention_t1(CacheState cs, ThreadIndex ti, Blackhole bh) {
        doMixedOp(cs, ti, bh);
    }

    @Benchmark
    @Threads(2)
    public void contention_t2(CacheState cs, ThreadIndex ti, Blackhole bh) {
        doMixedOp(cs, ti, bh);
    }

    @Benchmark
    @Threads(4)
    public void contention_t4(CacheState cs, ThreadIndex ti, Blackhole bh) {
        doMixedOp(cs, ti, bh);
    }

    @Benchmark
    @Threads(8)
    public void contention_t8(CacheState cs, ThreadIndex ti, Blackhole bh) {
        doMixedOp(cs, ti, bh);
    }

    // ── Shared workload logic ────────────────────────────────────────────

    /**
     * Mixed workload: 75% get-hit, 12.5% get-miss, 12.5% put-with-eviction.
     */
    private void doMixedOp(CacheState cs, ThreadIndex ti, Blackhole bh) {
        int op = ti.idx++ & 0x07;
        if (op < 6) {
            // 75% — get hit
            int keyIdx = ti.idx & (CacheState.HOT_KEY_COUNT - 1);
            bh.consume(cs.cache.get(0, keyIdx));
        } else if (op == 6) {
            // 12.5% — get miss
            bh.consume(cs.cache.get(99, ti.idx));
        } else {
            // 12.5% — put with eviction
            long offset = cs.globalCounter.getAndIncrement();
            cs.cache.put(1, offset, cs.block);
        }
    }
}
