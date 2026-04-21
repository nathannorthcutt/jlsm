package jlsm.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial tests for SqlParser — targets unbounded recursion and state safety.
 */
class SqlParserAdversarialTest {

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();

    // ── FINDING-10: Unbounded recursion depth in parser ───────────────

    // @spec query.sql-query-support.R32
    /**
     * FINDING-10: deeply nested parentheses should throw SqlParseException, not StackOverflowError
     */
    @Test
    void deeplyNestedParenthesesThrowsSqlParseException() {
        // 500 levels of nesting — well beyond any reasonable SQL query
        final int depth = 500;
        var sb = new StringBuilder("SELECT * FROM t WHERE ");
        sb.append("(".repeat(depth));
        sb.append("x = 1");
        sb.append(")".repeat(depth));

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Deeply nested expressions should fail with SqlParseException, not StackOverflowError");
    }

    // @spec query.sql-query-support.R32,R36
    /** FINDING-10: moderate nesting should still work — regression check */
    @Test
    void moderateNestingStillWorks() throws SqlParseException {
        // 10 levels of nesting — perfectly reasonable
        var sql = "SELECT * FROM t WHERE ((((((((((x = 1))))))))))";
        var stmt = parser.parse(lexer.tokenize(sql));
        assertTrue(stmt.where().isPresent());
    }

    // ── FINDING-11: SqlParser mutable state ───────────────────────────

    // @spec query.sql-query-support.R103 — parser must consume all tokens up to EOF; trailing tokens after a
    // complete SELECT must produce a SqlParseException. Without this check, a malformed
    // query like "SELECT * FROM t GARBAGE" silently succeeds and the extra tokens are lost.
    @Test
    void trailingTokensAfterSelectRejected() {
        assertThrows(SqlParseException.class,
                () -> parser.parse(lexer.tokenize("SELECT * FROM t EXTRA")),
                "Trailing tokens after a complete SELECT must throw SqlParseException");
    }

    // @spec query.sql-query-support.R103 — trailing junk after LIMIT/OFFSET must also be rejected
    @Test
    void trailingTokensAfterLimitRejected() {
        assertThrows(SqlParseException.class,
                () -> parser.parse(lexer.tokenize("SELECT * FROM t LIMIT 5 BOGUS")),
                "Trailing tokens after LIMIT must throw SqlParseException");
    }

    // @spec query.sql-query-support.R103 — well-formed SELECT with no trailing tokens must still parse (regression)
    @Test
    void noTrailingTokensParsesCleanly() throws SqlParseException {
        var stmt = parser.parse(lexer.tokenize("SELECT * FROM t WHERE x = 1 LIMIT 5 OFFSET 10"));
        assertTrue(stmt.where().isPresent());
        assertEquals(5, stmt.limit().orElseThrow());
        assertEquals(10, stmt.offset().orElseThrow());
    }

    // @spec query.sql-query-support.R97
    /** FINDING-11: sequential reuse of SqlParser should produce correct results */
    @Test
    void sequentialReuseProducesCorrectResults() throws SqlParseException {
        // First parse — has 2 bind parameters
        var tokens1 = lexer.tokenize("SELECT * FROM t WHERE a = ? AND b = ?");
        var stmt1 = parser.parse(tokens1);
        var and1 = assertInstanceOf(SqlAst.Expression.Logical.class, stmt1.where().get());
        var cmp1a = assertInstanceOf(SqlAst.Expression.Comparison.class, and1.left());
        var param1a = assertInstanceOf(SqlAst.Expression.Parameter.class, cmp1a.right());
        assertEquals(0, param1a.index(), "First parse, first param should be index 0");

        // Second parse — parameter index should reset to 0, not carry over from first parse
        var tokens2 = lexer.tokenize("SELECT * FROM t WHERE c = ?");
        var stmt2 = parser.parse(tokens2);
        var cmp2 = assertInstanceOf(SqlAst.Expression.Comparison.class, stmt2.where().get());
        var param2 = assertInstanceOf(SqlAst.Expression.Parameter.class, cmp2.right());
        assertEquals(0, param2.index(),
                "Second parse should reset parameter index to 0, not carry over from first parse");
    }
}
