package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.core.indexing.SimilarityFunction;
import org.junit.jupiter.api.Test;

class IndexDefinitionTest {

    @Test
    // @spec query.index-types.R1,R7,R13 — IndexType arity + IndexDefinition three-component record + 2-arg ctor
    void testIndexDefinitionCreation() {
        var eqDef = new IndexDefinition("name", IndexType.EQUALITY);
        assertEquals("name", eqDef.fieldName());
        assertEquals(IndexType.EQUALITY, eqDef.indexType());
        assertNull(eqDef.similarityFunction());

        var rangeDef = new IndexDefinition("age", IndexType.RANGE);
        assertEquals("age", rangeDef.fieldName());
        assertEquals(IndexType.RANGE, rangeDef.indexType());

        var uniqueDef = new IndexDefinition("email", IndexType.UNIQUE);
        assertEquals("email", uniqueDef.fieldName());
        assertEquals(IndexType.UNIQUE, uniqueDef.indexType());

        var ftDef = new IndexDefinition("body", IndexType.FULL_TEXT);
        assertEquals("body", ftDef.fieldName());
        assertEquals(IndexType.FULL_TEXT, ftDef.indexType());

        var vecDef = new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE);
        assertEquals("embedding", vecDef.fieldName());
        assertEquals(IndexType.VECTOR, vecDef.indexType());
        assertEquals(SimilarityFunction.COSINE, vecDef.similarityFunction());
    }

    @Test
    // @spec query.index-types.R8 — reject null fieldName with NPE
    void testIndexDefinitionNullFieldNameThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition(null, IndexType.EQUALITY));
    }

    @Test
    // @spec query.index-types.R9 — reject blank fieldName with IAE
    void testIndexDefinitionBlankFieldNameThrowsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexDefinition("  ", IndexType.EQUALITY));
    }

    @Test
    // @spec query.index-types.R10 — reject null indexType with NPE
    void testIndexDefinitionNullIndexTypeThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new IndexDefinition("name", null));
    }

    @Test
    // @spec query.index-types.R11 — VECTOR with null similarityFunction rejected with NPE
    void testIndexDefinitionVectorMissingSimilarityThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition("embedding", IndexType.VECTOR, null));
    }
}
