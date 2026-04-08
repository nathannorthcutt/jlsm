package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary concerns in the SQL pipeline.
 */
class ContractBoundariesAdversarialTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("salary", FieldType.float64()).field("active", FieldType.boolean_())
            .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 128)).build();

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();
    private final SqlTranslator translator = new SqlTranslator();

    private SqlQuery translate(String sql) throws SqlParseException {
        return translator.translate(parser.parse(lexer.tokenize(sql)), SCHEMA);
    }

    // Finding: F-R1.cb.1.2
    // Bug: BindMarker index validated by assert only — negative index accepted in production
    // Correct behavior: BindMarker constructor must reject negative index with
    // IllegalArgumentException
    // Fix location: SqlQuery.BindMarker compact constructor (SqlQuery.java:71-73)
    // Regression watch: valid zero and positive indices must still be accepted
    @Test
    void test_BindMarker_negativeIndex_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new SqlQuery.BindMarker(-1),
                "BindMarker must reject negative index with IllegalArgumentException");
    }

    // Finding: F-R1.cb.1.3
    // Bug: VectorDistanceOrder parameterIndex validated by assert only — negative index accepted in
    // production
    // Correct behavior: VectorDistanceOrder constructor must reject negative parameterIndex with
    // IllegalArgumentException
    // Fix location: SqlQuery.VectorDistanceOrder compact constructor (SqlQuery.java:93-97)
    // Regression watch: valid zero and positive parameterIndex must still be accepted
    @Test
    void test_VectorDistanceOrder_negativeParameterIndex_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlQuery.VectorDistanceOrder("field", -1, "cosine", true),
                "VectorDistanceOrder must reject negative parameterIndex with IllegalArgumentException");
    }

    // Finding: F-R1.cb.1.4
    // Bug: Lexer accepts trailing-dot numeric literals (e.g., "42.") producing Double instead of
    // Integer
    // Correct behavior: Trailing-dot numeric literal must be rejected as malformed at the lexer
    // level
    // Fix location: SqlLexer.readNumericLiteral (SqlLexer.java:186-205)
    // Regression watch: Valid decimals like "42.0" and "3.14" must still be accepted
    @Test
    void test_SqlLexer_trailingDotNumericLiteral_rejected() {
        var lexer = new SqlLexer();
        assertThrows(SqlParseException.class,
                () -> lexer.tokenize("SELECT * FROM test WHERE age = 42."),
                "Trailing-dot numeric literal '42.' must be rejected as malformed");
    }

    // Finding: F-R1.cb.1.5
    // Bug: Negative number literals not expressible — no minus token in lexer
    // Correct behavior: SQL with negative numeric literals (e.g., WHERE temp > -10) must parse
    // successfully
    // Fix location: SqlLexer.java (add MINUS token), TokenType.java (add MINUS), SqlParser.java
    // (unary minus)
    // Regression watch: positive numeric literals and comparison operators must still work
    @Test
    void test_SqlLexer_negativeNumericLiteral_parseable() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age > -10");

        assertTrue(query.predicate().isPresent(), "predicate should be present");
    }

    // Finding: F-R1.cb.1.5 (supplemental — negative decimal)
    @Test
    void test_SqlLexer_negativeDecimalLiteral_parseable() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE salary > -99.5");

        assertTrue(query.predicate().isPresent(), "predicate should be present");
    }

    // Finding: F-R1.cb.2.6
    // Bug: validateValueType accepts Number for integer types without range checking
    // Correct behavior: INT8 field must reject values outside [-128, 127] with SqlParseException
    // Fix location: SqlTranslator.validateValueType (SqlTranslator.java:369-376)
    // Regression watch: values within range must still be accepted
    @Test
    void test_validateValueType_int8FieldOverflowValue_rejected() {
        // Schema with INT8 field — value 999999 overflows byte range
        var int8Schema = JlsmSchema.builder("int8test", 1).field("tiny", FieldType.Primitive.INT8)
                .build();
        var lexer = new SqlLexer();
        var parser = new SqlParser();
        var xlator = new SqlTranslator();

        // 999999 is parsed as Integer, passes instanceof Number, but overflows INT8
        var ex = assertThrows(SqlParseException.class,
                () -> xlator.translate(
                        parser.parse(lexer.tokenize("SELECT * FROM int8test WHERE tiny = 999999")),
                        int8Schema),
                "INT8 field must reject out-of-range value 999999");
        assertTrue(ex.getMessage().contains("range") || ex.getMessage().contains("overflow")
                || ex.getMessage().contains("out of range") || ex.getMessage().contains("Range"),
                "Error message should mention range issue, got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.2.7
    // Bug: validateFieldIsVector accepts ArrayType without element type checking —
    // ArrayType(STRING) passes as a valid vector field for VECTOR_DISTANCE
    // Correct behavior: VECTOR_DISTANCE must reject non-numeric ArrayType fields with
    // SqlParseException
    // Fix location: SqlTranslator.validateFieldIsVector (SqlTranslator.java:412-420)
    // Regression watch: VectorType and ArrayType(FLOAT32) must still be accepted
    @Test
    void test_validateFieldIsVector_stringArrayType_rejected() {
        var stringArraySchema = JlsmSchema.builder("arrtest", 1)
                .field("tags", FieldType.arrayOf(FieldType.string())).build();
        var lexer = new SqlLexer();
        var parser = new SqlParser();
        var xlator = new SqlTranslator();

        assertThrows(SqlParseException.class,
                () -> xlator.translate(parser.parse(lexer.tokenize(
                        "SELECT * FROM arrtest ORDER BY VECTOR_DISTANCE(tags, ?, 'cosine')")),
                        stringArraySchema),
                "VECTOR_DISTANCE must reject ArrayType(STRING) — not a numeric vector field");
    }

    // Finding: F-R1.cb.2.8
    // Bug: VECTOR_DISTANCE metric value not validated against known metrics —
    // arbitrary strings like 'banana' pass through to VectorDistanceOrder
    // Correct behavior: Invalid metric must be rejected with SqlParseException at translation time
    // Fix location: SqlTranslator.translateVectorDistance (SqlTranslator.java:293-298)
    // Regression watch: valid metrics (cosine, euclidean, dot) must still be accepted
    @Test
    void test_translateVectorDistance_invalidMetric_rejected() {
        assertThrows(SqlParseException.class,
                () -> translate(
                        "SELECT * FROM test ORDER BY VECTOR_DISTANCE(embedding, ?, 'banana')"),
                "VECTOR_DISTANCE must reject unknown metric 'banana'");
    }

    // Finding: F-R1.cb.2.10
    // Bug: translateBetween does not validate low <= high — inverted range silently returns no
    // results
    // Correct behavior: BETWEEN with low > high must be rejected with SqlParseException at
    // translation time
    // Fix location: SqlTranslator.translateBetween (SqlTranslator.java:236-248)
    // Regression watch: valid BETWEEN with low <= high must still be accepted
    @Test
    void test_translateBetween_invertedRange_rejected() {
        // WHERE age BETWEEN 10 AND 5 is an inverted range (low=10 > high=5)
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE age BETWEEN 10 AND 5"),
                "BETWEEN must reject inverted range where low > high");
    }

    // Finding: F-R1.cb.2.11
    // Bug: SqlParseException position not validated — arbitrary negative values accepted
    // Correct behavior: position must be >= -1; values < -1 must throw IllegalArgumentException
    // Fix location: SqlParseException constructors (SqlParseException.java:23-26, 33-36)
    // Regression watch: position -1 (unknown) and position >= 0 must still be accepted
    @Test
    void test_SqlParseException_arbitraryNegativePosition_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new SqlParseException("test error", -42),
                "SqlParseException must reject position < -1");
    }

    @Test
    void test_SqlParseException_minValuePosition_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlParseException("test error", Integer.MIN_VALUE),
                "SqlParseException must reject Integer.MIN_VALUE position");
    }

    @Test
    void test_SqlParseException_validPositions_accepted() {
        // -1 means unknown position — must be accepted
        var unknown = new SqlParseException("unknown pos", -1);
        assertEquals(-1, unknown.position());

        // 0 and positive positions must be accepted
        var zero = new SqlParseException("at start", 0);
        assertEquals(0, zero.position());

        var positive = new SqlParseException("mid-string", 42);
        assertEquals(42, positive.position());
    }

    // Finding: F-R1.cb.1.1
    // Bug: VECTOR_DISTANCE ORDER BY direction (ASC/DESC) is silently discarded —
    // VectorDistanceOrder has no ascending field, translateVectorDistance ignores
    // clause.ascending()
    // Correct behavior: VectorDistanceOrder must preserve the ascending/descending direction
    // Fix location: SqlQuery.VectorDistanceOrder (add ascending field), SqlTranslator lines 73-76
    // Regression watch: existing VECTOR_DISTANCE ASC queries must continue to work
    @Test
    void test_translateVectorDistance_descDirection_preserved() throws SqlParseException {
        var query = translate(
                "SELECT * FROM test ORDER BY VECTOR_DISTANCE(embedding, ?, 'cosine') DESC");

        assertTrue(query.vectorDistance().isPresent(), "vectorDistance should be present");
        var vd = query.vectorDistance().get();
        assertEquals("embedding", vd.field());
        assertEquals("cosine", vd.metric());
        assertFalse(vd.ascending(), "DESC direction must be preserved as ascending=false");
    }
}
