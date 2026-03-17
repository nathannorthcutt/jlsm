package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.core.indexing.SimilarityFunction;
import org.junit.jupiter.api.Test;

class IndexDefinitionTest {

    @Test
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
    void testIndexDefinitionNullFieldNameThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition(null, IndexType.EQUALITY));
    }

    @Test
    void testIndexDefinitionBlankFieldNameThrowsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexDefinition("  ", IndexType.EQUALITY));
    }

    @Test
    void testIndexDefinitionNullIndexTypeThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new IndexDefinition("name", null));
    }

    @Test
    void testIndexDefinitionVectorMissingSimilarityThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition("embedding", IndexType.VECTOR, null));
    }
}
