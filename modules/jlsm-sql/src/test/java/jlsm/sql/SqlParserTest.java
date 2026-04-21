package jlsm.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqlParserTest {

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();

    private SqlAst.SelectStatement parse(String sql) throws SqlParseException {
        return parser.parse(lexer.tokenize(sql));
    }

    // ── Happy path ───────────────────────────────────────────────

    // @spec query.sql-query-support.R38
    @Test
    void parsesSelectStarFromTable() throws SqlParseException {
        var stmt = parse("SELECT * FROM users");

        assertEquals(1, stmt.columns().size());
        assertInstanceOf(SqlAst.Column.Wildcard.class, stmt.columns().getFirst());
        assertEquals("users", stmt.table());
        assertTrue(stmt.where().isEmpty());
        assertTrue(stmt.orderBy().isEmpty());
        assertTrue(stmt.limit().isEmpty());
        assertTrue(stmt.offset().isEmpty());
    }

    // @spec query.sql-query-support.R39
    @Test
    void parsesColumnProjectionList() throws SqlParseException {
        var stmt = parse("SELECT a, b, c FROM t");

        assertEquals(3, stmt.columns().size());
        var col0 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(0));
        assertEquals("a", col0.name());
        var col1 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(1));
        assertEquals("b", col1.name());
        var col2 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(2));
        assertEquals("c", col2.name());
    }

    // @spec query.sql-query-support.R39
    @Test
    void parsesColumnWithAlias() throws SqlParseException {
        var stmt = parse("SELECT name AS n, age AS a FROM t");

        var col0 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(0));
        assertEquals("name", col0.name());
        assertEquals(Optional.of("n"), col0.alias());

        var col1 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(1));
        assertEquals("age", col1.name());
        assertEquals(Optional.of("a"), col1.alias());
    }

    // @spec query.sql-query-support.R33
    @Test
    void parsesWhereEquals() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE x = 1");

        assertTrue(stmt.where().isPresent());
        var cmp = assertInstanceOf(SqlAst.Expression.Comparison.class, stmt.where().get());
        var left = assertInstanceOf(SqlAst.Expression.ColumnRef.class, cmp.left());
        assertEquals("x", left.name());
        assertEquals(SqlAst.ComparisonOp.EQ, cmp.op());
        var right = assertInstanceOf(SqlAst.Expression.NumberLiteral.class, cmp.right());
        assertEquals("1", right.text());
    }

    // @spec query.sql-query-support.R17,R33
    @Test
    void parsesWhereNotEquals() throws SqlParseException {
        var stmt1 = parse("SELECT * FROM t WHERE x != 1");
        var cmp1 = assertInstanceOf(SqlAst.Expression.Comparison.class, stmt1.where().get());
        assertEquals(SqlAst.ComparisonOp.NE, cmp1.op());

        var stmt2 = parse("SELECT * FROM t WHERE x <> 1");
        var cmp2 = assertInstanceOf(SqlAst.Expression.Comparison.class, stmt2.where().get());
        assertEquals(SqlAst.ComparisonOp.NE, cmp2.op());
    }

    // @spec query.sql-query-support.R33
    @Test
    void parsesWhereComparisons() throws SqlParseException {
        var gt = parse("SELECT * FROM t WHERE x > 1");
        assertEquals(SqlAst.ComparisonOp.GT,
                ((SqlAst.Expression.Comparison) gt.where().get()).op());

        var gte = parse("SELECT * FROM t WHERE x >= 1");
        assertEquals(SqlAst.ComparisonOp.GTE,
                ((SqlAst.Expression.Comparison) gte.where().get()).op());

        var lt = parse("SELECT * FROM t WHERE x < 1");
        assertEquals(SqlAst.ComparisonOp.LT,
                ((SqlAst.Expression.Comparison) lt.where().get()).op());

        var lte = parse("SELECT * FROM t WHERE x <= 1");
        assertEquals(SqlAst.ComparisonOp.LTE,
                ((SqlAst.Expression.Comparison) lte.where().get()).op());
    }

    // @spec query.sql-query-support.R33
    @Test
    void parsesWhereAndOr() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE a = 1 AND b = 2 OR c = 3");

        // OR has lower precedence: (a=1 AND b=2) OR (c=3)
        var or = assertInstanceOf(SqlAst.Expression.Logical.class, stmt.where().get());
        assertEquals(SqlAst.LogicalOp.OR, or.op());

        var and = assertInstanceOf(SqlAst.Expression.Logical.class, or.left());
        assertEquals(SqlAst.LogicalOp.AND, and.op());

        assertInstanceOf(SqlAst.Expression.Comparison.class, or.right());
    }

    // @spec query.sql-query-support.R34
    @Test
    void parsesWhereBetween() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE x BETWEEN 1 AND 10");

        var between = assertInstanceOf(SqlAst.Expression.Between.class, stmt.where().get());
        var field = assertInstanceOf(SqlAst.Expression.ColumnRef.class, between.field());
        assertEquals("x", field.name());
        assertInstanceOf(SqlAst.Expression.NumberLiteral.class, between.low());
        assertInstanceOf(SqlAst.Expression.NumberLiteral.class, between.high());
    }

    // @spec query.sql-query-support.R35
    @Test
    void parsesWhereIsNull() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE x IS NULL");

        var isNull = assertInstanceOf(SqlAst.Expression.IsNull.class, stmt.where().get());
        assertFalse(isNull.negated());
        var col = assertInstanceOf(SqlAst.Expression.ColumnRef.class, isNull.operand());
        assertEquals("x", col.name());
    }

    // @spec query.sql-query-support.R35
    @Test
    void parsesWhereIsNotNull() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE x IS NOT NULL");

        var isNull = assertInstanceOf(SqlAst.Expression.IsNull.class, stmt.where().get());
        assertTrue(isNull.negated());
    }

    // @spec query.sql-query-support.R36
    @Test
    void parsesParenthesizedExpressions() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3");

        // AND at top level, OR inside parens
        var and = assertInstanceOf(SqlAst.Expression.Logical.class, stmt.where().get());
        assertEquals(SqlAst.LogicalOp.AND, and.op());

        var or = assertInstanceOf(SqlAst.Expression.Logical.class, and.left());
        assertEquals(SqlAst.LogicalOp.OR, or.op());
    }

    // @spec query.sql-query-support.R42
    @Test
    void parsesMatchFunction() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE MATCH(title, 'search text')");

        var fn = assertInstanceOf(SqlAst.Expression.FunctionCall.class, stmt.where().get());
        assertEquals("MATCH", fn.name());
        assertEquals(2, fn.arguments().size());
        var field = assertInstanceOf(SqlAst.Expression.ColumnRef.class, fn.arguments().get(0));
        assertEquals("title", field.name());
        var query = assertInstanceOf(SqlAst.Expression.StringLiteral.class, fn.arguments().get(1));
        assertEquals("search text", query.value());
    }

    // @spec query.sql-query-support.R42,R43
    @Test
    void parsesVectorDistanceInOrderBy() throws SqlParseException {
        var stmt = parse("SELECT * FROM t ORDER BY VECTOR_DISTANCE(emb, ?, 'cosine')");

        assertEquals(1, stmt.orderBy().size());
        var orderBy = stmt.orderBy().getFirst();
        assertTrue(orderBy.ascending());
        var fn = assertInstanceOf(SqlAst.Expression.FunctionCall.class, orderBy.expression());
        assertEquals("VECTOR_DISTANCE", fn.name());
        assertEquals(3, fn.arguments().size());
    }

    // @spec query.sql-query-support.R40
    @Test
    void parsesOrderByAscDesc() throws SqlParseException {
        var stmt = parse("SELECT * FROM t ORDER BY a ASC, b DESC");

        assertEquals(2, stmt.orderBy().size());
        var ob0 = stmt.orderBy().get(0);
        assertTrue(ob0.ascending());
        var col0 = assertInstanceOf(SqlAst.Expression.ColumnRef.class, ob0.expression());
        assertEquals("a", col0.name());

        var ob1 = stmt.orderBy().get(1);
        assertFalse(ob1.ascending());
        var col1 = assertInstanceOf(SqlAst.Expression.ColumnRef.class, ob1.expression());
        assertEquals("b", col1.name());
    }

    // @spec query.sql-query-support.R41
    @Test
    void parsesLimitAndOffset() throws SqlParseException {
        var stmt = parse("SELECT * FROM t LIMIT 10 OFFSET 20");

        assertEquals(Optional.of(10), stmt.limit());
        assertEquals(Optional.of(20), stmt.offset());
    }

    // @spec query.sql-query-support.R37
    @Test
    void parsesBindParametersIndexed() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE a = ? AND b = ?");

        var and = assertInstanceOf(SqlAst.Expression.Logical.class, stmt.where().get());
        var left = assertInstanceOf(SqlAst.Expression.Comparison.class, and.left());
        var param0 = assertInstanceOf(SqlAst.Expression.Parameter.class, left.right());
        assertEquals(0, param0.index());

        var right = assertInstanceOf(SqlAst.Expression.Comparison.class, and.right());
        var param1 = assertInstanceOf(SqlAst.Expression.Parameter.class, right.right());
        assertEquals(1, param1.index());
    }

    // @spec query.sql-query-support.R50
    @Test
    void parsesBooleanLiterals() throws SqlParseException {
        var stmt = parse("SELECT * FROM t WHERE active = TRUE");

        var cmp = assertInstanceOf(SqlAst.Expression.Comparison.class, stmt.where().get());
        var bool = assertInstanceOf(SqlAst.Expression.BooleanLiteral.class, cmp.right());
        assertTrue(bool.value());
    }

    // @spec query.sql-query-support.R28
    @Test
    void parsesFullComplexQuery() throws SqlParseException {
        var stmt = parse("""
                SELECT name AS n, age FROM users
                WHERE age >= 18 AND (status = 'active' OR role = ?)
                ORDER BY age DESC, name ASC
                LIMIT 50 OFFSET 10
                """);

        // columns
        assertEquals(2, stmt.columns().size());
        var col0 = assertInstanceOf(SqlAst.Column.Named.class, stmt.columns().get(0));
        assertEquals("name", col0.name());
        assertEquals(Optional.of("n"), col0.alias());

        // table
        assertEquals("users", stmt.table());

        // where
        assertTrue(stmt.where().isPresent());
        var topAnd = assertInstanceOf(SqlAst.Expression.Logical.class, stmt.where().get());
        assertEquals(SqlAst.LogicalOp.AND, topAnd.op());

        // order by
        assertEquals(2, stmt.orderBy().size());
        assertFalse(stmt.orderBy().get(0).ascending()); // DESC
        assertTrue(stmt.orderBy().get(1).ascending()); // ASC

        // limit / offset
        assertEquals(Optional.of(50), stmt.limit());
        assertEquals(Optional.of(10), stmt.offset());
    }

    // ── Error cases ──────────────────────────────────────────────

    // @spec query.sql-query-support.R31
    @Test
    void rejectsInsertStatement() {
        var ex = assertThrows(SqlParseException.class, () -> parse("INSERT INTO t VALUES (1)"));
        assertTrue(ex.getMessage().toLowerCase().contains("select"),
                "Error should mention SELECT requirement");
    }

    // @spec query.sql-query-support.R31
    @Test
    void rejectsUpdateStatement() {
        var ex = assertThrows(SqlParseException.class, () -> parse("UPDATE t SET x = 1"));
        assertTrue(ex.getMessage().toLowerCase().contains("select"),
                "Error should mention SELECT requirement");
    }

    // @spec query.sql-query-support.R31
    @Test
    void rejectsDeleteStatement() {
        var ex = assertThrows(SqlParseException.class, () -> parse("DELETE FROM t"));
        assertTrue(ex.getMessage().toLowerCase().contains("select"),
                "Error should mention SELECT requirement");
    }

    // @spec query.sql-query-support.R44
    @Test
    void rejectsMissingFromClause() {
        assertThrows(SqlParseException.class, () -> parse("SELECT *"));
    }

    // @spec query.sql-query-support.R30
    @Test
    void rejectsEmptyTokenList() {
        assertThrows(SqlParseException.class, () -> parser.parse(List.of()));
    }
}
