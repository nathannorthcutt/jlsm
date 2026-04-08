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

    /** FINDING-10: moderate nesting should still work — regression check */
    @Test
    void moderateNestingStillWorks() throws SqlParseException {
        // 10 levels of nesting — perfectly reasonable
        var sql = "SELECT * FROM t WHERE ((((((((((x = 1))))))))))";
        var stmt = parser.parse(lexer.tokenize(sql));
        assertTrue(stmt.where().isPresent());
    }

    // ── FINDING-11: SqlParser mutable state ───────────────────────────

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
