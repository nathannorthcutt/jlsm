package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural and behavioral spec-gap tests for F07. Covers requirements that are otherwise only
 * implicitly exercised — sealed-type permits, utility-class shape, defensive list copies,
 * whitespace-only input, and BindMarker comparison semantics.
 */
class F07StructuralTest {

    // @spec query.sql-query-support.R1 — jlsm-sql exports only jlsm.sql; no internal subpackages
    // surface types
    @Test
    void jlsmSqlModuleExportsOnlyJlsmSqlPackage() throws Exception {
        // Tests run in the unnamed module, so Class.getModule() gives the unnamed module.
        // Locate jlsm-sql's module-info.class via the code source of a production class,
        // then parse the ModuleDescriptor directly.
        var codeSource = JlsmSql.class.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, "JlsmSql must have a CodeSource");
        var moduleInfoUrl = new java.net.URL(codeSource.getLocation(), "module-info.class");
        try (var in = moduleInfoUrl.openStream()) {
            var descriptor = java.lang.module.ModuleDescriptor.read(in);
            assertEquals("jlsm.sql", descriptor.name());
            var exports = descriptor.exports();
            assertEquals(1, exports.size(), "jlsm.sql must export exactly one package");
            assertEquals("jlsm.sql", exports.iterator().next().source(),
                    "the single exported package must be jlsm.sql");
        }
    }

    // @spec query.sql-query-support.R6 — JlsmSql is final with a private no-arg constructor
    @Test
    void jlsmSqlIsFinalAndHasPrivateConstructor() throws ReflectiveOperationException {
        assertTrue(Modifier.isFinal(JlsmSql.class.getModifiers()), "JlsmSql must be final");
        var ctors = JlsmSql.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "JlsmSql must declare exactly one constructor");
        Constructor<?> ctor = ctors[0];
        assertEquals(0, ctor.getParameterCount(), "constructor must be no-arg");
        assertTrue(Modifier.isPrivate(ctor.getModifiers()), "constructor must be private");
    }

    // @spec query.sql-query-support.R46 — SqlAst is a sealed interface
    @Test
    void sqlAstIsSealed() {
        assertTrue(SqlAst.class.isSealed(), "SqlAst must be sealed");
        assertTrue(SqlAst.class.isInterface(), "SqlAst must be an interface");
    }

    // @spec query.sql-query-support.R49 — Column sealed, permits exactly Wildcard and Named
    @Test
    void columnPermitsExactlyWildcardAndNamed() {
        assertTrue(SqlAst.Column.class.isSealed(), "Column must be sealed");
        var permitted = Set.of(SqlAst.Column.class.getPermittedSubclasses());
        assertEquals(2, permitted.size(), "Column must permit exactly two implementations");
        assertTrue(permitted.contains(SqlAst.Column.Wildcard.class));
        assertTrue(permitted.contains(SqlAst.Column.Named.class));
    }

    // @spec query.sql-query-support.R50 — Expression sealed, permits exactly 11 listed
    // implementations
    @Test
    void expressionPermitsExactlyElevenImplementations() {
        assertTrue(SqlAst.Expression.class.isSealed(), "Expression must be sealed");
        var permitted = Set.of(SqlAst.Expression.class.getPermittedSubclasses());
        assertEquals(11, permitted.size(), "Expression must permit exactly 11 implementations");
        assertTrue(permitted.contains(SqlAst.Expression.Comparison.class));
        assertTrue(permitted.contains(SqlAst.Expression.Logical.class));
        assertTrue(permitted.contains(SqlAst.Expression.Not.class));
        assertTrue(permitted.contains(SqlAst.Expression.Between.class));
        assertTrue(permitted.contains(SqlAst.Expression.IsNull.class));
        assertTrue(permitted.contains(SqlAst.Expression.ColumnRef.class));
        assertTrue(permitted.contains(SqlAst.Expression.StringLiteral.class));
        assertTrue(permitted.contains(SqlAst.Expression.NumberLiteral.class));
        assertTrue(permitted.contains(SqlAst.Expression.BooleanLiteral.class));
        assertTrue(permitted.contains(SqlAst.Expression.Parameter.class));
        assertTrue(permitted.contains(SqlAst.Expression.FunctionCall.class));
    }

    // @spec query.sql-query-support.R79 — SqlQuery is a record type
    @Test
    void sqlQueryIsARecord() {
        assertTrue(SqlQuery.class.isRecord(), "SqlQuery must be a record");
    }

    // @spec query.sql-query-support.R48 — SelectStatement defensively copies columns and orderBy
    @Test
    void selectStatementDefensivelyCopiesLists() {
        var columns = new java.util.ArrayList<SqlAst.Column>(
                List.of(new SqlAst.Column.Named("a", Optional.empty())));
        var orderBy = new java.util.ArrayList<SqlAst.OrderByClause>();
        var stmt = new SqlAst.SelectStatement(columns, "t", Optional.empty(), orderBy,
                Optional.empty(), Optional.empty());
        columns.add(new SqlAst.Column.Named("b", Optional.empty()));
        orderBy.add(new SqlAst.OrderByClause(new SqlAst.Expression.ColumnRef("a"), true));
        assertEquals(1, stmt.columns().size(), "SelectStatement.columns must be copied");
        assertEquals(0, stmt.orderBy().size(), "SelectStatement.orderBy must be copied");
    }

    // @spec query.sql-query-support.R52 — FunctionCall defensively copies its arguments list
    @Test
    void functionCallDefensivelyCopiesArguments() {
        var args = new java.util.ArrayList<SqlAst.Expression>(
                List.of(new SqlAst.Expression.ColumnRef("a")));
        var fn = new SqlAst.Expression.FunctionCall("MATCH", args);
        args.add(new SqlAst.Expression.StringLiteral("x"));
        assertEquals(1, fn.arguments().size(), "FunctionCall.arguments must be copied");
    }

    // @spec query.sql-query-support.R81 — SqlQuery defensively copies projections, aliases, orderBy
    @Test
    void sqlQueryDefensivelyCopiesLists() {
        var projections = new java.util.ArrayList<>(List.of("a"));
        var aliases = new java.util.ArrayList<>(List.of("aa"));
        var orderBy = new java.util.ArrayList<>(List.of(new SqlQuery.OrderBy("a", true)));
        var q = new SqlQuery(Optional.empty(), projections, aliases, orderBy, OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty());
        projections.add("b");
        aliases.add("bb");
        orderBy.add(new SqlQuery.OrderBy("b", false));
        assertEquals(1, q.projections().size());
        assertEquals(1, q.aliases().size());
        assertEquals(1, q.orderBy().size());
    }

    // @spec query.sql-query-support.R83 — BindMarker.compareTo orders by index
    @Test
    void bindMarkerCompareToOrdersByIndex() {
        var a = new SqlQuery.BindMarker(0);
        var b = new SqlQuery.BindMarker(5);
        var c = new SqlQuery.BindMarker(5);
        assertTrue(a.compareTo(b) < 0, "BindMarker(0) must sort before BindMarker(5)");
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, b.compareTo(c), "equal indices compareTo must return 0");
    }

    // @spec query.sql-query-support.R100 — whitespace-only input returns a single EOF token
    @Test
    void whitespaceOnlyInputYieldsEofOnly() throws SqlParseException {
        var lexer = new SqlLexer();
        var tokens = lexer.tokenize("   \t\n  ");
        assertEquals(1, tokens.size(), "whitespace-only input must produce exactly one token");
        assertEquals(TokenType.EOF, tokens.getFirst().type());
    }

    // @spec query.sql-query-support.R77 — numeric widening: Integer if fits, Long if fits, Double
    // for decimals
    @Test
    void numericLiteralWidensIntegerToLongToDouble() throws SqlParseException {
        var schema = JlsmSchema.builder("t", 1).field("x", FieldType.int64())
                .field("f", FieldType.float64()).build();
        var pipeline = new SqlTranslator();
        var parser = new SqlParser();
        var lexer = new SqlLexer();

        // Value that fits in int
        var intQuery = pipeline
                .translate(parser.parse(lexer.tokenize("SELECT * FROM t WHERE x = 42")), schema);
        var intEq = (jlsm.table.Predicate.Eq) intQuery.predicate().orElseThrow();
        assertInstanceOf(Integer.class, intEq.value());

        // Value that overflows int but fits in long
        var longQuery = pipeline.translate(
                parser.parse(lexer.tokenize("SELECT * FROM t WHERE x = 9999999999")), schema);
        var longEq = (jlsm.table.Predicate.Eq) longQuery.predicate().orElseThrow();
        assertInstanceOf(Long.class, longEq.value());

        // Decimal value becomes Double
        var doubleQuery = pipeline
                .translate(parser.parse(lexer.tokenize("SELECT * FROM t WHERE f = 3.14")), schema);
        var doubleEq = (jlsm.table.Predicate.Eq) doubleQuery.predicate().orElseThrow();
        assertInstanceOf(Double.class, doubleEq.value());
    }
}
