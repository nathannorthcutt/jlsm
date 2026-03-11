package jlsm.tree.bench;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class StandardLsmTreeBenchmark {

    private static final int KEY_COUNT = 1024;
    private static final int KEY_MASK = KEY_COUNT - 1;

    @Param({ "1024", "5120", "10240" })
    public int valueSizeBytes;

    private StandardLsmTree tree;
    private MemorySegment[] keys;
    private MemorySegment value;
    private int opIndex;
    private Path tempDir;
    private AtomicLong idCounter;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("jlsm-tree-bench-");
        idCounter = new AtomicLong(0);

        Path walDir = tempDir.resolve("wal");
        Files.createDirectories(walDir);

        tree = StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(walDir).segmentSize(4L * 1024 * 1024)
                        .build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(256L * 1024).recoverFromWal(false).build();

        // Pre-generate keys
        keys = new MemorySegment[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            String k = String.format("key-%04d", i);
            keys[i] = MemorySegment.ofArray(k.getBytes(StandardCharsets.UTF_8));
        }

        // Pre-generate fixed-size value
        byte[] valueBytes = new byte[valueSizeBytes];
        Arrays.fill(valueBytes, (byte) 0xAB);
        value = MemorySegment.ofArray(valueBytes);

        opIndex = 0;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (tree != null)
            tree.close();
        deleteRecursively(tempDir);
    }

    @Benchmark
    public void mixedWorkload() throws IOException {
        int op = opIndex % 10;
        int idx = opIndex & KEY_MASK;
        opIndex++;

        if (op < 6) {
            // 60% — put with rotating key (new inserts + re-inserts)
            tree.put(keys[idx], value);
        } else if (op < 9) {
            // 30% — update: put with the previous key
            tree.put(keys[(idx == 0 ? KEY_COUNT - 1 : idx - 1)], value);
        } else {
            // 10% — delete with rotating key
            tree.delete(keys[idx]);
        }
    }

    /** Recursively deletes a directory tree. */
    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root))
            return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
