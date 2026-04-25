package jlsm.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for the sealed-interface conversion of {@link Table} — validates R8e (sealed with exactly
 * two permitted subtypes) and R8f (runtime defence-in-depth: both permits non-public,
 * not-Serializable, in non-exported internal packages) from
 * {@code sstable.footer-encryption-scope}.
 */
class SealedTableTest {

    /** Both permits must be reachable by FQCN — they live in non-exported internal packages. */
    private static final String CATALOG_TABLE_FQCN = "jlsm.engine.internal.CatalogTable";
    private static final String CATALOG_CLUSTERED_TABLE_FQCN = "jlsm.engine.cluster.internal.CatalogClusteredTable";

    // ---------- R8e: sealed with exactly two permits ----------

    @Test
    void table_isSealed() {
        // covers: R8e — Table interface declared sealed
        assertTrue(Table.class.isSealed(),
                "Table must be a sealed interface (R8e); was non-sealed");
    }

    @Test
    void table_hasExactlyTwoPermittedSubtypes() {
        // covers: R8e — exactly two permits: CatalogTable + CatalogClusteredTable
        final Class<?>[] permits = Table.class.getPermittedSubclasses();
        assertEquals(2, permits.length, "Table must permit exactly two subtypes (R8e); got "
                + java.util.Arrays.toString(permits));
    }

    @Test
    void table_permitsCatalogTable_andCatalogClusteredTable() {
        // covers: R8e — the two permits are exactly the named internal classes
        final Set<String> permits = java.util.Arrays.stream(Table.class.getPermittedSubclasses())
                .map(Class::getName).collect(java.util.stream.Collectors.toSet());

        assertAll(
                () -> assertTrue(permits.contains(CATALOG_TABLE_FQCN),
                        "Table must permit " + CATALOG_TABLE_FQCN + "; permits = " + permits),
                () -> assertTrue(permits.contains(CATALOG_CLUSTERED_TABLE_FQCN),
                        "Table must permit " + CATALOG_CLUSTERED_TABLE_FQCN + "; permits = "
                                + permits));
    }

    // ---------- R8f: runtime defence-in-depth on each permit ----------

    @Test
    void catalogTable_isInNonExportedInternalPackage() throws Exception {
        // covers: R8f — CatalogTable lives in jlsm.engine.internal (non-exported)
        final Class<?> cls = Class.forName(CATALOG_TABLE_FQCN);
        assertEquals("jlsm.engine.internal", cls.getPackageName(),
                "CatalogTable must reside in jlsm.engine.internal (R8f)");
    }

    @Test
    void catalogClusteredTable_isInNonExportedInternalPackage() throws Exception {
        // covers: R8f — CatalogClusteredTable lives in jlsm.engine.cluster.internal
        // (non-exported)
        final Class<?> cls = Class.forName(CATALOG_CLUSTERED_TABLE_FQCN);
        assertEquals("jlsm.engine.cluster.internal", cls.getPackageName(),
                "CatalogClusteredTable must reside in jlsm.engine.cluster.internal (R8f)");
    }

    @Test
    void catalogTable_isNotSerializable() throws Exception {
        // covers: R8f — CatalogTable must NOT implement Serializable
        final Class<?> cls = Class.forName(CATALOG_TABLE_FQCN);
        assertFalse(Serializable.class.isAssignableFrom(cls),
                "CatalogTable must NOT implement Serializable (R8f)");
    }

    @Test
    void catalogClusteredTable_isNotSerializable() throws Exception {
        // covers: R8f — CatalogClusteredTable must NOT implement Serializable
        final Class<?> cls = Class.forName(CATALOG_CLUSTERED_TABLE_FQCN);
        assertFalse(Serializable.class.isAssignableFrom(cls),
                "CatalogClusteredTable must NOT implement Serializable (R8f)");
    }

    @Test
    void catalogTable_constructorsArePackagePrivate() throws Exception {
        // covers: R8f — CatalogTable constructors must be non-public (package-private),
        // forcing instantiation through the engine factory in the same internal package.
        final Class<?> cls = Class.forName(CATALOG_TABLE_FQCN);
        for (var ctor : cls.getDeclaredConstructors()) {
            final int mods = ctor.getModifiers();
            assertFalse(java.lang.reflect.Modifier.isPublic(mods),
                    "CatalogTable constructor must NOT be public (R8f); ctor = " + ctor);
            assertFalse(java.lang.reflect.Modifier.isProtected(mods),
                    "CatalogTable constructor must NOT be protected (R8f); ctor = " + ctor);
        }
    }

    @Test
    void catalogClusteredTable_constructorsArePackagePrivate() throws Exception {
        // covers: R8f — CatalogClusteredTable constructors must be non-public.
        final Class<?> cls = Class.forName(CATALOG_CLUSTERED_TABLE_FQCN);
        for (var ctor : cls.getDeclaredConstructors()) {
            final int mods = ctor.getModifiers();
            assertFalse(java.lang.reflect.Modifier.isPublic(mods),
                    "CatalogClusteredTable constructor must NOT be public (R8f); ctor = " + ctor);
            assertFalse(java.lang.reflect.Modifier.isProtected(mods),
                    "CatalogClusteredTable constructor must NOT be protected (R8f); ctor = "
                            + ctor);
        }
    }
}
