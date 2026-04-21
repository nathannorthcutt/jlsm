package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SqlTranslator — targets contract gaps and implementation risks discovered
 * during spec analysis round 1.
 */
class SqlTranslatorAdversarialTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("salary", FieldType.float64()).field("active", FieldType.boolean_()).build();

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();
    private final SqlTranslator translator = new SqlTranslator();

    private SqlQuery translate(String sql) throws SqlParseException {
        return translator.translate(parser.parse(lexer.tokenize(sql)), SCHEMA);
    }

    // ── FINDING-1: Bind parameters with range operators ──────────────

    // @spec query.sql-query-support.R61
    /** FINDING-1: bind parameter with > operator should not throw */
    @Test
    void bindParameterWithGreaterThan() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age > ?");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Gt.class, query.predicate().get());
    }

    // @spec query.sql-query-support.R61
    /** FINDING-1: bind parameter with >= operator should not throw */
    @Test
    void bindParameterWithGreaterThanOrEqual() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age >= ?");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Gte.class, query.predicate().get());
    }

    // @spec query.sql-query-support.R61
    /** FINDING-1: bind parameter with < operator should not throw */
    @Test
    void bindParameterWithLessThan() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age < ?");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Lt.class, query.predicate().get());
    }

    // @spec query.sql-query-support.R61
    /** FINDING-1: bind parameter with <= operator should not throw */
    @Test
    void bindParameterWithLessThanOrEqual() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age <= ?");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Lte.class, query.predicate().get());
    }

    // @spec query.sql-query-support.R61
    /** FINDING-1: bind parameters in BETWEEN should not throw */
    @Test
    void bindParametersInBetween() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age BETWEEN ? AND ?");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Between.class, query.predicate().get());
    }

    // ── FINDING-2: Reversed comparisons (literal on left) ────────────

    // @spec query.sql-query-support.R74
    /** FINDING-2: literal on left side of comparison should work */
    @Test
    void reversedComparisonLiteralOnLeft() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE 30 < age");
        assertTrue(query.predicate().isPresent());
        // 30 < age is equivalent to age > 30
    }

    // @spec query.sql-query-support.R74
    /** FINDING-2: string literal on left side of equality */
    @Test
    void reversedEqualityStringLiteralOnLeft() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE 'Alice' = name");
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Eq.class, query.predicate().get());
    }

    // ── FINDING-3: Numeric overflow in parseNumber ───────────────────

    // @spec query.sql-query-support.R78
    /** FINDING-3: number exceeding Long.MAX_VALUE should throw SqlParseException, not NFE */
    @Test
    void numericOverflowThrowsSqlParseException() {
        // 99999999999999999999 exceeds Long.MAX_VALUE
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE age = 99999999999999999999"));
    }

    // @spec query.sql-query-support.R60,R89
    /**
     * FINDING-3: number just beyond Integer range is rejected for INT32 fields. Updated by audit
     * F-R1.cb.2.6 — translator now validates integer range per field type
     */
    @Test
    void integerOverflowFallsToLong() {
        // 2147483648 = Integer.MAX_VALUE + 1 — exceeds INT32 range, must be rejected
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE age = 2147483648"));
    }

    // @spec query.sql-query-support.R78 — decimal numeric literals that overflow Double must throw,
    // not become Infinity
    @Test
    void decimalOverflowThrowsSqlParseException() {
        // Double.parseDouble("1e400") returns Double.POSITIVE_INFINITY without throwing
        // NumberFormatException, so a naive parseNumber would silently produce Infinity as
        // the predicate value. R78 requires SqlParseException on decimal overflow.
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE salary > 1e400"));
    }

    // @spec query.sql-query-support.R78 — decimal underflow to -Infinity must also throw
    @Test
    void decimalNegativeOverflowThrowsSqlParseException() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE salary > -1e400"));
    }
}
