package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial tests round 2 — targets contract gaps in type validation and resource safety.
 */
class SqlTranslatorAdversarialTest2 {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("salary", FieldType.float64()).field("active", FieldType.boolean_())
            .field("created", FieldType.timestamp()).build();

    private static final JlsmSchema VECTOR_SCHEMA = JlsmSchema.builder("vectors", 1)
            .field("id", FieldType.int32())
            .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 128))
            .field("title", FieldType.string()).build();

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();
    private final SqlTranslator translator = new SqlTranslator();

    private SqlQuery translate(String sql, JlsmSchema schema) throws SqlParseException {
        return translator.translate(parser.parse(lexer.tokenize(sql)), schema);
    }

    private SqlQuery translate(String sql) throws SqlParseException {
        return translate(sql, SCHEMA);
    }

    // ── FINDING-7: No field-type validation in translator ─────────────

    /** FINDING-7: string literal against int32 field should throw SqlParseException */
    @Test
    void rejectsStringLiteralForIntField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE age = 'Alice'"),
                "String literal 'Alice' is incompatible with INT32 field 'age'");
    }

    /** FINDING-7: numeric literal against boolean field should throw SqlParseException */
    @Test
    void rejectsNumericLiteralForBooleanField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE active = 42"),
                "Numeric literal 42 is incompatible with BOOLEAN field 'active'");
    }

    /** FINDING-7: boolean literal against string field should throw SqlParseException */
    @Test
    void rejectsBooleanLiteralForStringField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE name = TRUE"),
                "Boolean literal TRUE is incompatible with STRING field 'name'");
    }

    /** FINDING-7: string literal against float64 field with range operator */
    @Test
    void rejectsStringLiteralForFloatFieldInRange() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE salary > 'high'"),
                "String literal 'high' is incompatible with FLOAT64 field 'salary'");
    }

    /** FINDING-7: boolean literal against numeric field with equality */
    @Test
    void rejectsBooleanLiteralForNumericField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE age = FALSE"),
                "Boolean literal FALSE is incompatible with INT32 field 'age'");
    }

    /** FINDING-7: numeric literal against boolean field in BETWEEN */
    @Test
    void rejectsNumericBetweenForBooleanField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE active BETWEEN 0 AND 1"),
                "Numeric BETWEEN is incompatible with BOOLEAN field 'active'");
    }

    /** FINDING-7: correct type combination should still work — regression check */
    @Test
    void acceptsMatchingTypesAfterValidation() throws SqlParseException {
        // String field with string literal
        var q1 = translate("SELECT * FROM test WHERE name = 'Alice'");
        assertInstanceOf(Predicate.Eq.class, q1.predicate().get());

        // Int field with numeric literal
        var q2 = translate("SELECT * FROM test WHERE age > 30");
        assertInstanceOf(Predicate.Gt.class, q2.predicate().get());

        // Boolean field with boolean literal
        var q3 = translate("SELECT * FROM test WHERE active = TRUE");
        assertInstanceOf(Predicate.Eq.class, q3.predicate().get());

        // Float field with numeric literal
        var q4 = translate("SELECT * FROM test WHERE salary >= 50000.0");
        assertInstanceOf(Predicate.Gte.class, q4.predicate().get());
    }

    // ── FINDING-8: MATCH on non-STRING field accepted ─────────────────

    /** FINDING-8: MATCH on an INT32 field should throw SqlParseException */
    @Test
    void rejectsMatchOnNonStringField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE MATCH(age, 'search text')"),
                "MATCH requires a STRING field but 'age' is INT32");
    }

    /** FINDING-8: MATCH on a BOOLEAN field should throw SqlParseException */
    @Test
    void rejectsMatchOnBooleanField() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE MATCH(active, 'yes')"),
                "MATCH requires a STRING field but 'active' is BOOLEAN");
    }

    /** FINDING-8: MATCH on a STRING field should still work — regression check */
    @Test
    void acceptsMatchOnStringField() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE MATCH(name, 'Alice')");
        assertInstanceOf(Predicate.FullTextMatch.class, query.predicate().get());
    }

    // ── FINDING-9: VECTOR_DISTANCE on non-vector field accepted ───────

    /** FINDING-9: VECTOR_DISTANCE on a STRING field should throw SqlParseException */
    @Test
    void rejectsVectorDistanceOnNonVectorField() {
        assertThrows(SqlParseException.class, () -> translate(
                "SELECT * FROM vectors ORDER BY VECTOR_DISTANCE(title, ?, 'cosine') LIMIT 10",
                VECTOR_SCHEMA), "VECTOR_DISTANCE requires a vector field but 'title' is STRING");
    }

    /** FINDING-9: VECTOR_DISTANCE on an INT32 field should throw SqlParseException */
    @Test
    void rejectsVectorDistanceOnIntField() {
        assertThrows(SqlParseException.class,
                () -> translate(
                        "SELECT * FROM vectors ORDER BY VECTOR_DISTANCE(id, ?, 'cosine') LIMIT 10",
                        VECTOR_SCHEMA),
                "VECTOR_DISTANCE requires a vector field but 'id' is INT32");
    }

    /** FINDING-9: VECTOR_DISTANCE on a vector field should still work — regression check */
    @Test
    void acceptsVectorDistanceOnVectorField() throws SqlParseException {
        var query = translate(
                "SELECT * FROM vectors ORDER BY VECTOR_DISTANCE(embedding, ?, 'cosine') LIMIT 10",
                VECTOR_SCHEMA);
        assertTrue(query.vectorDistance().isPresent());
        assertEquals("embedding", query.vectorDistance().get().field());
    }

    // ── FINDING-12: Projections/aliases size mismatch ─────────────────

    /** FINDING-12: projections and aliases lists must have the same size */
    @Test
    void sqlQueryRejectsMismatchedProjectionsAndAliases() {
        // Directly construct with mismatched sizes to verify the contract
        assertThrows(IllegalArgumentException.class,
                () -> new SqlQuery(java.util.Optional.empty(), java.util.List.of("a", "b"),
                        java.util.List.of("x"), java.util.List.of(), java.util.OptionalInt.empty(),
                        java.util.OptionalInt.empty(), java.util.Optional.empty()),
                "projections and aliases must have the same size");
    }
}
