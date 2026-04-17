package jlsm.table;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JlsmSchemaTest {

    // @spec F13.R2,R17,R18
    @Test
    void builder_createsSchemaWithNameAndVersion() {
        JlsmSchema schema = JlsmSchema.builder("users", 1).field("id", FieldType.Primitive.INT64)
                .build();
        assertEquals("users", schema.name());
        assertEquals(1, schema.version());
        assertEquals(1, schema.fields().size());
    }

    // @spec F13.R19,R32
    @Test
    void builder_defaultMaxDepth_is10() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).build();
        assertEquals(10, schema.maxDepth());
    }

    // @spec F13.R31
    @Test
    void builder_maxDepth_canBeSetUpTo25() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).maxDepth(25).build();
        assertEquals(25, schema.maxDepth());
    }

    // @spec F13.R30
    @Test
    void builder_maxDepth_throwsOver25() {
        assertThrows(IllegalArgumentException.class,
                () -> JlsmSchema.builder("test", 1).maxDepth(26).build());
    }

    // @spec F13.R20,R24
    @Test
    void builder_objectField_inlineNested() {
        JlsmSchema schema = JlsmSchema.builder("outer", 1)
                .objectField("address", inner -> inner.field("street", FieldType.Primitive.STRING)
                        .field("city", FieldType.Primitive.STRING))
                .build();
        assertEquals(1, schema.fields().size());
        assertEquals("address", schema.fields().get(0).name());
        assertInstanceOf(FieldType.ObjectType.class, schema.fields().get(0).type());
    }

    // @spec F13.R23,R31
    @Test
    void builder_nestedAtLimit_succeeds() {
        // Build a schema nested exactly at maxDepth=2
        JlsmSchema schema = JlsmSchema
                .builder("root", 1).maxDepth(
                        2)
                .objectField("level1", l1 -> l1.objectField("level2",
                        l2 -> l2.field("leaf", FieldType.Primitive.STRING)))
                .build();
        assertNotNull(schema);
    }

    // @spec F13.R23
    @Test
    void builder_nestedBeyondLimit_throws() {
        assertThrows(IllegalArgumentException.class, () -> JlsmSchema
                .builder("root", 1).maxDepth(
                        1)
                .objectField("level1", l1 -> l1.objectField("level2",
                        l2 -> l2.field("leaf", FieldType.Primitive.STRING)))
                .build());
    }

    // @spec F13.R13,R12
    @Test
    void fieldIndex_returnsCorrectIndex() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("alpha", FieldType.Primitive.STRING)
                .field("beta", FieldType.Primitive.INT32)
                .field("gamma", FieldType.Primitive.BOOLEAN).build();
        assertEquals(0, schema.fieldIndex("alpha"));
        assertEquals(1, schema.fieldIndex("beta"));
        assertEquals(2, schema.fieldIndex("gamma"));
    }

    // @spec F13.R14
    @Test
    void fieldIndex_unknownField_returnsNegative() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("known", FieldType.Primitive.STRING)
                .build();
        assertEquals(-1, schema.fieldIndex("unknown"));
    }
}
