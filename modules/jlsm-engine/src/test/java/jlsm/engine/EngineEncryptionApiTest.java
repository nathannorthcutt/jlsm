package jlsm.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import jlsm.encryption.TableScope;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.Test;

/**
 * Tests for the Engine API surface extension — validates R7 (createEncryptedTable) and R7b
 * (enableEncryption) signatures from {@code sstable.footer-encryption-scope}.
 *
 * <p>
 * WU-1 only validates the interface signatures exist; implementations are stubbed (throw
 * {@link UnsupportedOperationException}) and will be filled in by WU-3.
 */
class EngineEncryptionApiTest {

    @Test
    void engine_declaresCreateEncryptedTable_withCorrectSignature() throws Exception {
        // covers: R7 — Engine interface exposes
        // createEncryptedTable(String, JlsmSchema, TableScope) throws IOException
        final Method m = Engine.class.getMethod("createEncryptedTable", String.class,
                JlsmSchema.class, TableScope.class);

        assertAll(() -> assertNotNull(m, "createEncryptedTable must be declared on Engine (R7)"),
                () -> assertEquals(Table.class, m.getReturnType(),
                        "createEncryptedTable must return Table (R7)"),
                () -> assertEquals(1,
                        java.util.Arrays.stream(m.getExceptionTypes())
                                .filter(t -> t.equals(java.io.IOException.class)).count(),
                        "createEncryptedTable must declare throws IOException (R7)"));
    }

    @Test
    void engine_declaresEnableEncryption_withCorrectSignature() throws Exception {
        // covers: R7b — Engine interface exposes
        // enableEncryption(String, TableScope) throws IOException
        final Method m = Engine.class.getMethod("enableEncryption", String.class, TableScope.class);

        assertAll(() -> assertNotNull(m, "enableEncryption must be declared on Engine (R7b)"),
                () -> assertEquals(void.class, m.getReturnType(),
                        "enableEncryption must return void (R7b)"),
                () -> assertEquals(1,
                        java.util.Arrays.stream(m.getExceptionTypes())
                                .filter(t -> t.equals(java.io.IOException.class)).count(),
                        "enableEncryption must declare throws IOException (R7b)"));
    }

    @Test
    void engine_doesNotDeclareDisableEncryption() {
        // covers: R7b — encryption is one-way; the Engine interface must NOT expose
        // a disableEncryption method.
        for (Method m : Engine.class.getMethods()) {
            if (m.getName().equals("disableEncryption")) {
                throw new AssertionError(
                        "Engine must NOT expose disableEncryption (R7b one-way invariant); found "
                                + m);
            }
        }
    }
}
