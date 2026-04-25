package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Tests for the R10b FAILED-state guard, R10c finish protocol (under {@link WriterCommitHook}'s
 * exclusive lock with fresh scope re-check), and R10e tmp-file cleanup invariants on
 * {@link TrieSSTableWriter}.
 *
 * <p>
 * The {@link WriterCommitHook} SPI is the jlsm-core boundary jlsm-engine plugs into so the writer
 * can perform the R10c lock + fresh-catalog-read + scope-compare under the engine's catalog lock
 * without jlsm-core depending on jlsm-engine.
 *
 * @spec sstable.footer-encryption-scope.R10b
 * @spec sstable.footer-encryption-scope.R10c
 * @spec sstable.footer-encryption-scope.R10e
 */
class TrieSSTableWriterR10cTest {

    @TempDir
    Path tempDir;

    private static TableScope scope() {
        return new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
    }

    private static Entry entry(String key) {
        final byte[] keyBytes = key.getBytes();
        return new Entry.Put(MemorySegment.ofArray(keyBytes),
                MemorySegment.ofArray("value".getBytes()), new SequenceNumber(1L));
    }

    // ---- R10c happy path: stable encryption state across construction → finish ----

    @Test
    void finish_withStableScope_commitsUnderHook() throws IOException {
        // covers: R10c happy path — when fresh re-read matches construction-time, finish
        // commits successfully under the hook's lock.
        final Path out = tempDir.resolve("sst-1.sst");
        final RecordingHook hook = new RecordingHook(Optional.of(scope()));
        try (final TrieSSTableWriter w = TrieSSTableWriter.builder().id(1).level(Level.L0).path(out)
                .scope(scope()).dekVersions(new int[]{ 1 }).commitHook(hook)
                .tableNameForLock("users").build()) {
            w.append(entry("k1"));
            final SSTableMetadata m = w.finish();
            assertNotNull(m);
        }
        assertTrue(Files.exists(out), "committed v6 SSTable must exist");
        assertEquals(1, hook.acquires.get(), "lock acquired exactly once for finish (R10c step 2)");
        assertEquals(1, hook.releases.get(), "lock released exactly once after finish");
        assertTrue(hook.freshReadCalled.get(),
                "fresh catalog read invoked under lock (R10c step 3)");
    }

    // ---- R10c race: encryption state transitioned mid-write → FAILED ----

    @Test
    void finish_withScopeTransitionDuringWrite_failsAndDeletesPartial() throws IOException {
        // covers: R10c step 5 — writer constructed pre-enable; encryption flips mid-write;
        // fresh read sees Optional.of(...); writer FAILED; partial file deleted; IOException
        // surfaced. R12 — IOException message must not reveal scope identifiers.
        final Path out = tempDir.resolve("sst-race.sst");
        final TransitioningHook hook = new TransitioningHook(Optional.empty(),
                Optional.of(scope()));
        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(2).level(Level.L0).path(out)
                .commitHook(hook).tableNameForLock("racy").build();

        w.append(entry("k1"));
        final IOException ex = assertThrows(IOException.class, w::finish);
        // Message must not reveal scope identifiers (R12).
        assertFalse(ex.getMessage().contains("tenant-A"),
                "R12 — IOException must not reveal scope identifiers; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("domain-X"));
        assertFalse(ex.getMessage().contains("table-Z"));

        // Partial file deleted (R10b/R10e — close after FAILED removes tmp).
        w.close();
        assertFalse(Files.exists(out), "no committed file emitted (R10c)");
        // No leftover *.partial.* tmp files in tempDir.
        try (var s = Files.list(tempDir)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().contains(".partial.")),
                    "R10e — partial tmp file deleted on close after FAILED");
        }
    }

    // ---- R10b mid-write IOException → FAILED → subsequent ops rejected ----

    @Test
    void midWrite_subsequentAppend_afterFailed_rejected() throws IOException {
        // covers: R10b — once FAILED, the writer rejects further mutating ops.
        final Path out = tempDir.resolve("sst-fail.sst");
        final TransitioningHook hook = new TransitioningHook(Optional.empty(),
                Optional.of(scope()));
        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(3).level(Level.L0).path(out)
                .commitHook(hook).tableNameForLock("racy").build();
        w.append(entry("k1"));
        assertThrows(IOException.class, w::finish);
        // Subsequent ops on a FAILED writer must throw IllegalStateException.
        assertThrows(IllegalStateException.class, () -> w.append(entry("k2")));
        assertThrows(IllegalStateException.class, w::finish);
        w.close();
    }

    // ---- R10c: hook NOT called when no scope configured (plaintext path unchanged) ----

    @Test
    void plaintextWriter_doesNotInvokeHook() throws IOException {
        // covers: R10c — for plaintext (v5) writers without scope and without a hook, the
        // existing finish path is preserved.
        final Path out = tempDir.resolve("sst-plain.sst");
        try (final TrieSSTableWriter w = TrieSSTableWriter.builder().id(4).level(Level.L0).path(out)
                .build()) {
            w.append(entry("k1"));
            final SSTableMetadata m = w.finish();
            assertNotNull(m);
        }
        assertTrue(Files.exists(out));
    }

    // ---- defensive (Lens B) — close before finish removes tmp file ----

    @Test
    void closeWithoutFinish_deletesPartialFile() throws IOException {
        // covers: R10e + existing close-without-finish behaviour.
        final Path out = tempDir.resolve("sst-aborted.sst");
        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(5).level(Level.L0).path(out)
                .build();
        w.append(entry("k1"));
        w.close();
        try (var s = Files.list(tempDir)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().contains(".partial.")),
                    "close before finish must delete *.partial.* tmp");
        }
        assertFalse(Files.exists(out));
    }

    // ---- defensive (Lens B) — hook MUST be supplied when scope was set at construction ----

    @Test
    void scopedWriter_withoutHook_finishStillSucceeds_butWarnsOrCommitsOptionally()
            throws IOException {
        // covers: R10c — when no hook is supplied for a scoped writer (legacy / test paths),
        // the writer falls back to construction-time scope (no fresh re-read possible). This
        // is acceptable for unit-test paths but engine paths MUST always supply a hook. We
        // assert the writer at minimum does not crash — engine integration tests cover the
        // mandatory-hook path.
        final Path out = tempDir.resolve("sst-noholder.sst");
        try (final TrieSSTableWriter w = TrieSSTableWriter.builder().id(6).level(Level.L0).path(out)
                .scope(scope()).dekVersions(new int[]{ 1 }).build()) {
            w.append(entry("k1"));
            final SSTableMetadata m = w.finish();
            assertNotNull(m);
        }
        assertTrue(Files.exists(out));
    }

    // ---- helpers — minimal in-memory WriterCommitHook impls ----

    /** Records hook invocations; returns a fixed scope on every fresh read. */
    private static final class RecordingHook implements WriterCommitHook {
        final AtomicInteger acquires = new AtomicInteger(0);
        final AtomicInteger releases = new AtomicInteger(0);
        final AtomicBoolean freshReadCalled = new AtomicBoolean(false);
        private final Optional<TableScope> scope;

        RecordingHook(Optional<TableScope> scope) {
            this.scope = scope;
        }

        @Override
        public Lease acquire(String tableName) throws IOException {
            acquires.incrementAndGet();
            return new Lease() {
                @Override
                public Optional<TableScope> freshScope() {
                    freshReadCalled.set(true);
                    return scope;
                }

                @Override
                public void close() {
                    releases.incrementAndGet();
                }
            };
        }
    }

    /**
     * Models the catalog state after the race: the writer was constructed observing {@code before},
     * and by the time finish() reads the fresh scope, the state has flipped to {@code after}. The
     * hook returns {@code after} on every freshScope() call, since the transition has already
     * committed by the time finish() acquires the lease.
     */
    private static final class TransitioningHook implements WriterCommitHook {
        private final Optional<TableScope> after;
        @SuppressWarnings("unused")
        private final Optional<TableScope> before;

        TransitioningHook(Optional<TableScope> before, Optional<TableScope> after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public Lease acquire(String tableName) {
            return new Lease() {
                @Override
                public Optional<TableScope> freshScope() {
                    return after;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
