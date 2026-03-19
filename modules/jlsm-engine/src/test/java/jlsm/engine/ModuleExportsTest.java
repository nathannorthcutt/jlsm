package jlsm.engine;

import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the module exports are correct by importing and referencing all exported types from
 * jlsm.engine and transitively from jlsm.table.
 */
class ModuleExportsTest {

    @Test
    void testModuleCompiles() {
        // If this test compiles and runs, the module-info.java exports are correct.
        // Verify all exported types are accessible.
        assertNotNull(Engine.class);
        assertNotNull(Table.class);
        assertNotNull(TableMetadata.class);
        assertNotNull(TableMetadata.TableState.class);
        assertNotNull(EngineMetrics.class);
        assertNotNull(AllocationTracking.class);
        assertNotNull(HandleEvictedException.class);
        assertNotNull(HandleEvictedException.Reason.class);

        // Transitive exports from jlsm.table should also be accessible
        assertNotNull(JlsmSchema.class);
        assertNotNull(JlsmDocument.class);
        assertNotNull(TableQuery.class);
        assertNotNull(TableEntry.class);
        assertNotNull(UpdateMode.class);
    }
}
