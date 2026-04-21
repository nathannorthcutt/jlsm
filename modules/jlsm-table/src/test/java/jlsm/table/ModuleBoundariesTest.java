package jlsm.table;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Verifies the JPMS module descriptor of {@code jlsm.table} exports the public API package and
 * withholds the {@code jlsm.table.internal} package — the boundary that keeps {@code SecondaryIndex},
 * {@code FieldIndex}, {@code FullTextFieldIndex}, {@code VectorFieldIndex}, {@code FieldValueCodec},
 * {@code IndexRegistry}, and {@code QueryExecutor} off the public surface while
 * {@code IndexType}, {@code IndexDefinition}, {@code Predicate}, {@code TableQuery},
 * {@code TableEntry}, and {@code DuplicateKeyException} remain available to consumers.
 *
 * <p>The test suite runs in the unnamed module (per jlsm-table's Gradle config with
 * {@code --add-exports jlsm.table/jlsm.table.internal=ALL-UNNAMED}), so the production module
 * descriptor is read directly from the compiled {@code module-info.class} on the classpath rather
 * than via the runtime {@link Module} API.
 */
// @spec query.query-executor.R19 — SecondaryIndex, FieldIndex, FullTextFieldIndex,
// VectorFieldIndex, FieldValueCodec, IndexRegistry, and QueryExecutor reside in
// jlsm.table.internal which is intentionally not exported
// @spec query.query-executor.R20 — IndexType, IndexDefinition, Predicate, TableQuery,
// TableEntry, and DuplicateKeyException reside in jlsm.table which is exported
class ModuleBoundariesTest {

    private static ModuleDescriptor tableDescriptor() {
        // The test classpath contains many module-info.class resources (JDK modules, other jlsm
        // modules). Walk all of them and pick the one that names itself 'jlsm.table'.
        try {
            Enumeration<URL> urls = IndexType.class.getClassLoader()
                    .getResources("module-info.class");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream in = url.openStream()) {
                    ModuleDescriptor candidate = ModuleDescriptor.read(in);
                    if ("jlsm.table".equals(candidate.name())) {
                        return candidate;
                    }
                }
            }
            fail("No jlsm.table module-info.class found on test classpath — "
                    + "cannot verify module boundaries");
        } catch (IOException ex) {
            fail("failed to read module-info.class: " + ex);
        }
        throw new AssertionError("unreachable");
    }

    private static Set<String> exportedPackages(ModuleDescriptor descriptor) {
        return descriptor.exports().stream()
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Test
    // @spec query.query-executor.R19 — jlsm.table.internal absent from exported packages
    void jlsmTableInternalIsNotExported() {
        var descriptor = tableDescriptor();
        assertTrue(descriptor.name().equals("jlsm.table"),
                "descriptor must describe jlsm.table module, got " + descriptor.name());
        var exports = exportedPackages(descriptor);
        assertFalse(exports.contains("jlsm.table.internal"),
                "jlsm.table.internal must not be exported — exports were " + exports);
    }

    @Test
    // @spec query.query-executor.R20 — jlsm.table is exported and public API types reside there
    void jlsmTableIsExportedAndHostsPublicApi() {
        var descriptor = tableDescriptor();
        var exports = exportedPackages(descriptor);
        assertTrue(exports.contains("jlsm.table"),
                "jlsm.table must be exported — exports were " + exports);

        // Verify each public API type lives in jlsm.table (R20 class-placement claim).
        assertTrue(IndexType.class.getPackageName().equals("jlsm.table"),
                "IndexType must reside in jlsm.table");
        assertTrue(IndexDefinition.class.getPackageName().equals("jlsm.table"),
                "IndexDefinition must reside in jlsm.table");
        assertTrue(Predicate.class.getPackageName().equals("jlsm.table"),
                "Predicate must reside in jlsm.table");
        assertTrue(TableQuery.class.getPackageName().equals("jlsm.table"),
                "TableQuery must reside in jlsm.table");
        assertTrue(TableEntry.class.getPackageName().equals("jlsm.table"),
                "TableEntry must reside in jlsm.table");
        assertTrue(DuplicateKeyException.class.getPackageName().equals("jlsm.table"),
                "DuplicateKeyException must reside in jlsm.table");
    }

    @Test
    // @spec query.query-executor.R19 — internal types reside in jlsm.table.internal
    void internalTypesResideInInternalPackage() {
        assertTrue(jlsm.table.internal.SecondaryIndex.class.getPackageName()
                .equals("jlsm.table.internal"),
                "SecondaryIndex must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.FieldIndex.class.getPackageName()
                .equals("jlsm.table.internal"),
                "FieldIndex must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.FullTextFieldIndex.class.getPackageName()
                .equals("jlsm.table.internal"),
                "FullTextFieldIndex must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.VectorFieldIndex.class.getPackageName()
                .equals("jlsm.table.internal"),
                "VectorFieldIndex must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.FieldValueCodec.class.getPackageName()
                .equals("jlsm.table.internal"),
                "FieldValueCodec must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.IndexRegistry.class.getPackageName()
                .equals("jlsm.table.internal"),
                "IndexRegistry must reside in jlsm.table.internal");
        assertTrue(jlsm.table.internal.QueryExecutor.class.getPackageName()
                .equals("jlsm.table.internal"),
                "QueryExecutor must reside in jlsm.table.internal");
    }
}
