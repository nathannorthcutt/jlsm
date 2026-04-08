package jlsm.sstable;

import jlsm.core.bloom.BloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLifecycleAdversarialTest {

    @TempDir
    Path dir;

    Path sstPath;

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    @BeforeEach
    void writeSSTable() throws IOException {
        sstPath = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, sstPath)) {
            w.append(put("a", "va", 1));
            w.append(put("b", "vb", 2));
            w.append(put("c", "vc", 3));
            w.finish();
        }
    }

    private static long openFdCount() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: readBloomFilter closes Arena immediately after deserialize(); a custom
    // Deserializer that retains the MemorySegment reference will get
    // IllegalStateException on subsequent access (use-after-close).
    // Correct behavior: The MemorySegment passed to deserialize() must remain valid
    // after deserialization, or the API must not close the Arena.
    // Fix location: TrieSSTableReader.readBloomFilter (lines 638-648)
    // Regression watch: Ensure BlockedBloomFilter still works after the fix.
    @Test
    void test_readBloomFilter_retainingDeserializer_useAfterClose() throws IOException {
        // A custom deserializer that retains a reference to the MemorySegment
        // and uses it later in mightContain(). This is a legitimate implementation
        // strategy — the Deserializer contract does not forbid it.
        BloomFilter.Deserializer retainingDeserializer = bytes -> {
            // Read header values while segment is still alive
            int numBlocks = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0);
            int numHashFunctions = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN),
                    4);

            // Return a BloomFilter that retains the segment for later use
            return new BloomFilter() {
                private final MemorySegment retained = bytes;
                private final int blocks = numBlocks;

                @Override
                public void add(MemorySegment key) {
                    throw new UnsupportedOperationException("read-only");
                }

                @Override
                public boolean mightContain(MemorySegment key) {
                    // Access the retained segment — this will throw
                    // IllegalStateException if the backing Arena is closed
                    long firstBlock = retained
                            .get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16);
                    return firstBlock != 0 || blocks > 0;
                }

                @Override
                public double falsePositiveRate() {
                    return 0.01;
                }

                @Override
                public MemorySegment serialize() {
                    return retained;
                }
            };
        };

        // Open the SSTable with the retaining deserializer.
        // The readBloomFilter method will close the Arena after deserialization,
        // but the returned BloomFilter still holds a reference to the segment.
        try (TrieSSTableReader reader = TrieSSTableReader.open(sstPath, retainingDeserializer)) {
            // reader.get() internally calls bloomFilter.mightContain(), which
            // accesses the retained MemorySegment. Before the fix, this throws
            // IllegalStateException("Already closed") from the closed Arena.
            Optional<Entry> result = reader.get(seg("a"));
            assertTrue(result.isPresent(), "key 'a' should be found in the SSTable");
        }
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: open/openLazy catch IOException but not Error — channel leaks on OutOfMemoryError
    // Correct behavior: Channel must be closed when any Throwable (including Error) escapes
    // Fix location: TrieSSTableReader.open (v1/v2) and openLazy (v1/v2) catch blocks
    // Regression watch: Ensure IOException paths still work correctly after broadening catch
    @Test
    void test_open_errorDuringRead_channelLeaksFileDescriptor() throws Exception {
        long baselineFds = openFdCount();
        if (baselineFds < 0) {
            // Not on a Unix system — skip gracefully
            return;
        }

        // A deserializer that throws OutOfMemoryError, simulating memory pressure
        // during bloom filter deserialization inside the try block.
        BloomFilter.Deserializer oomDeserializer = bytes -> {
            throw new OutOfMemoryError("simulated OOM during bloom filter deserialization");
        };

        int iterations = 50;
        for (int i = 0; i < iterations; i++) {
            try {
                TrieSSTableReader.open(sstPath, oomDeserializer);
                fail("Expected OutOfMemoryError to propagate");
            } catch (OutOfMemoryError expected) {
                // Before fix: channel not closed (catch only handles IOException)
                // After fix: channel closed in catch (Error) or finally block
            }
        }

        long afterFds = openFdCount();
        // Allow a small margin for JVM internal FD churn, but 50 leaked FDs is unmistakable
        assertTrue(afterFds - baselineFds < 5,
                "File descriptors leaked: before=" + baselineFds + " after=" + afterFds + " (delta="
                        + (afterFds - baselineFds) + "). "
                        + "Channel not closed when Error escapes open().");
    }

}
