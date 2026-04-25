package jlsm.engine.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.engine.Engine;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

/**
 * Integration tests for {@link LocalEngine} createEncryptedTable + enableEncryption — the 5-step
 * R7b protocol, R7 happy path, R7a unchanged plaintext path, R7c publication, R11a atomic catalog
 * write, R11b serialised concurrent enableEncryption, and R12 message-discipline.
 *
 * @spec sstable.footer-encryption-scope.R7
 * @spec sstable.footer-encryption-scope.R7a
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R7c
 * @spec sstable.footer-encryption-scope.R11a
 * @spec sstable.footer-encryption-scope.R11b
 * @spec sstable.footer-encryption-scope.R12
 * @spec sstable.footer-encryption-scope.R13c
 */
class LocalEngineEncryptionTest {

    @TempDir
    Path tempDir;

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("schema", 1).field("id", FieldType.Primitive.STRING).build();
    }

    private static TableScope scope() {
        return new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
    }

    private static TableScope altScope() {
        return new TableScope(new TenantId("tenant-B"), new DomainId("domain-Y"),
                new TableId("table-W"));
    }

    private Engine engine() throws IOException {
        return Engine.builder().rootDirectory(tempDir).build();
    }

    // ---- R7 happy path ----

    @Test
    void createEncryptedTable_persistsEncryptionMetadata() throws IOException {
        // covers: R7 — createEncryptedTable persists encryption + the catalog high-water bumps.
        try (final Engine eng = engine()) {
            final Table t = eng.createEncryptedTable("secret", testSchema(), scope());
            try {
                assertNotNull(t);
                assertTrue(t.metadata().encryption().isPresent(),
                        "createEncryptedTable must yield Optional.of(...) (R7)");
                assertEquals(scope(), t.metadata().encryption().orElseThrow().scope());
            } finally {
                t.close();
            }
        }
    }

    @Test
    void createEncryptedTable_nullScope_throwsNPE() throws IOException {
        // covers: R7 — eager input validation.
        try (final Engine eng = engine()) {
            assertThrows(NullPointerException.class,
                    () -> eng.createEncryptedTable("secret", testSchema(), null));
        }
    }

    @Test
    void createEncryptedTable_persistsAcrossReopen() throws IOException {
        // covers: R7 — encryption metadata round-trips across engine restarts.
        try (final Engine eng = engine()) {
            final Table t = eng.createEncryptedTable("secret", testSchema(), scope());
            t.close();
        }
        try (final Engine eng = engine()) {
            final TableMetadata m = eng.tableMetadata("secret");
            assertNotNull(m, "table must be present after restart");
            assertTrue(m.encryption().isPresent(),
                    "encryption metadata must round-trip across restart (R7 + R9b)");
            assertEquals(scope(), m.encryption().orElseThrow().scope());
        }
    }

    // ---- R7a — createTable unchanged ----

    @Test
    void createTable_yieldsEncryptionEmpty() throws IOException {
        // covers: R7a — plaintext createTable behaviour unchanged; encryption=empty.
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("plain", testSchema())) {
                assertEquals(Optional.empty(), t.metadata().encryption(), "R7a — plaintext");
            }
        }
    }

    // ---- R7b — enableEncryption transitions empty → present ----

    @Test
    void enableEncryption_transitionsEmptyToPresent() throws IOException {
        // covers: R7b happy path — enableEncryption flips Optional.empty() → Optional.of(...).
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("plain", testSchema())) {
                assertEquals(Optional.empty(), t.metadata().encryption());
            }
            eng.enableEncryption("plain", scope());
            try (final Table after = eng.getTable("plain")) {
                assertTrue(after.metadata().encryption().isPresent(),
                        "enableEncryption must publish Optional.of(...) (R7b)");
                assertEquals(scope(), after.metadata().encryption().orElseThrow().scope());
            }
        }
    }

    // ---- R7b idempotent rejection — encryption is one-way ----

    @Test
    void enableEncryption_calledTwice_secondCallThrowsISE() throws IOException {
        // covers: R7b step 3 — second enable on already-encrypted table fails ISE; encryption
        // is one-way.
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("plain", testSchema())) {
                // ignore handle
            }
            eng.enableEncryption("plain", scope());

            final IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> eng.enableEncryption("plain", altScope()));
            // R12 — message must NOT reveal existing scope identifiers (or any DEK material).
            assertFalse(ex.getMessage().contains("tenant-A"),
                    "ISE message must not reveal existing scope identifiers (R12)");
            assertFalse(ex.getMessage().contains("domain-X"),
                    "ISE message must not reveal existing scope identifiers (R12)");
            assertFalse(ex.getMessage().contains("table-Z"),
                    "ISE message must not reveal existing scope identifiers (R12)");
        }
    }

    // ---- R7c — post-enable, getTable / tableMetadata observe Optional.of(...) immediately ----

    @Test
    void postEnable_subsequentGetTable_observesEncryption() throws IOException {
        // covers: R7c — handles obtained AFTER enable observe the new state.
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("plain", testSchema())) {
                // close handle
            }
            eng.enableEncryption("plain", scope());

            try (final Table after = eng.getTable("plain")) {
                assertTrue(after.metadata().encryption().isPresent(), "R7c");
            }

            final TableMetadata m = eng.tableMetadata("plain");
            assertNotNull(m);
            assertTrue(m.encryption().isPresent(), "R7c — tableMetadata observes update");
        }
    }

    // ---- R7b — encryption is one-way: no disable method ----

    @Test
    void engine_doesNotDeclareDisableEncryption() {
        // covers: R7b one-way invariant — restated here for engine-level coverage.
        for (final var m : Engine.class.getMethods()) {
            assertFalse(m.getName().equals("disableEncryption"),
                    "Engine must not expose disableEncryption (R7b one-way)");
        }
    }

    // ---- R11b — concurrent enableEncryption serialised, exactly one wins ----

    @Test
    void concurrentEnableEncryption_serialisedByLock_oneWinsOthersFail() throws Exception {
        // covers: R7b + R11b — concurrent enableEncryption on the same table is serialised by
        // the catalog lock; exactly one succeeds, the rest observe encryption.isPresent() at
        // step 3 and throw IllegalStateException.
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("hot", testSchema())) {
                // ignore handle
            }

            final int N = 8;
            final ExecutorService exec = Executors.newFixedThreadPool(N);
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger successes = new AtomicInteger(0);
            final AtomicInteger ises = new AtomicInteger(0);
            final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < N; i++) {
                    futures.add(exec.submit(() -> {
                        try {
                            start.await();
                            eng.enableEncryption("hot", scope());
                            successes.incrementAndGet();
                        } catch (IllegalStateException ise) {
                            ises.incrementAndGet();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (final var f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertEquals(1, successes.get(),
                        "exactly one concurrent enableEncryption must succeed (R11b)");
                assertEquals(N - 1, ises.get(),
                        "remaining concurrent enableEncryption calls must throw ISE (R7b step 3)");
            } finally {
                exec.shutdownNow();
            }
        }
    }

    // ---- R11a — atomic publication: no partial transition ever observable ----

    @Test
    void duringEnable_concurrentReads_observeEitherEmptyOrPresent_neverPartial() throws Exception {
        // covers: R11a — TableCatalog metadata update is atomic w.r.t. concurrent
        // tableMetadata() readers; readers see Optional.empty() OR Optional.of(...), never a
        // partial transition (e.g., new format-version but stale encryption block).
        try (final Engine eng = engine()) {
            try (final Table t = eng.createTable("under-flux", testSchema())) {
                // ignore
            }

            final ExecutorService exec = Executors.newFixedThreadPool(2);
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                final var writer = exec.submit(() -> {
                    try {
                        start.await();
                        eng.enableEncryption("under-flux", scope());
                    } catch (Exception e) {
                        failure.set(e);
                    }
                    return null;
                });
                final var reader = exec.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 1_000; i++) {
                            final TableMetadata m = eng.tableMetadata("under-flux");
                            if (m == null) {
                                continue;
                            }
                            assertNotNull(m.encryption(),
                                    "encryption Optional must never be null (R11a)");
                            // Optional must be either empty or contain a fully-formed scope.
                            m.encryption().ifPresent(em -> {
                                assertNotNull(em.scope(), "scope must not be null on present");
                                assertEquals(scope(), em.scope(),
                                        "scope must be the persisted value when present (R11a)");
                            });
                        }
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                    return null;
                });
                start.countDown();
                writer.get(30, TimeUnit.SECONDS);
                reader.get(30, TimeUnit.SECONDS);
                if (failure.get() != null) {
                    throw new AssertionError(failure.get());
                }
            } finally {
                exec.shutdownNow();
            }
        }
    }

    // ---- input validation ----

    @Test
    void enableEncryption_unknownTable_throwsIOException() throws IOException {
        // covers: R7b — unknown table must surface IOException.
        try (final Engine eng = engine()) {
            assertThrows(IOException.class, () -> eng.enableEncryption("ghost", scope()));
        }
    }

    @Test
    void enableEncryption_nullName_throwsNPE() throws IOException {
        try (final Engine eng = engine()) {
            assertThrows(NullPointerException.class, () -> eng.enableEncryption(null, scope()));
        }
    }

    @Test
    void enableEncryption_nullScope_throwsNPE() throws IOException {
        try (final Engine eng = engine()) {
            assertThrows(NullPointerException.class, () -> eng.enableEncryption("any", null));
        }
    }

    @Test
    void enableEncryption_emptyName_throwsIAE() throws IOException {
        try (final Engine eng = engine()) {
            assertThrows(IllegalArgumentException.class, () -> eng.enableEncryption("", scope()));
        }
    }
}
