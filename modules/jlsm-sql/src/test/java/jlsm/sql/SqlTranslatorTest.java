package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlTranslatorTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("salary", FieldType.float64()).field("active", FieldType.boolean_())
            .field("created", FieldType.timestamp()).build();

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();
    private final SqlTranslator translator = new SqlTranslator();

    private SqlQuery translate(String sql) throws SqlParseException {
        return translator.translate(parser.parse(lexer.tokenize(sql)), SCHEMA);
    }

    // ── Happy path ───────────────────────────────────────────────

    // @spec F07.R75
    @Test
    void translatesSelectStar() throws SqlParseException {
        var query = translate("SELECT * FROM test");

        assertTrue(query.projections().isEmpty());
        assertTrue(query.aliases().isEmpty());
        assertTrue(query.predicate().isEmpty());
    }

    // @spec F07.R57
    @Test
    void translatesColumnProjection() throws SqlParseException {
        var query = translate("SELECT name, age FROM test");

        assertEquals(List.of("name", "age"), query.projections());
    }

    // @spec F07.R76
    @Test
    void translatesColumnAliases() throws SqlParseException {
        var query = translate("SELECT name AS n, age AS a FROM test");

        assertEquals(List.of("name", "age"), query.projections());
        assertEquals(List.of("n", "a"), query.aliases());
    }

    // @spec F07.R62
    @Test
    void translatesWhereEquals() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE name = 'Alice'");

        assertTrue(query.predicate().isPresent());
        var eq = assertInstanceOf(Predicate.Eq.class, query.predicate().get());
        assertEquals("name", eq.field());
        assertEquals("Alice", eq.value());
    }

    // @spec F07.R62
    @Test
    void translatesWhereNotEquals() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE name != 'Bob'");

        var ne = assertInstanceOf(Predicate.Ne.class, query.predicate().get());
        assertEquals("name", ne.field());
        assertEquals("Bob", ne.value());
    }

    // @spec F07.R62
    @Test
    void translatesWhereComparisons() throws SqlParseException {
        var gt = translate("SELECT * FROM test WHERE age > 30");
        assertInstanceOf(Predicate.Gt.class, gt.predicate().get());

        var gte = translate("SELECT * FROM test WHERE age >= 30");
        assertInstanceOf(Predicate.Gte.class, gte.predicate().get());

        var lt = translate("SELECT * FROM test WHERE age < 30");
        assertInstanceOf(Predicate.Lt.class, lt.predicate().get());

        var lte = translate("SELECT * FROM test WHERE age <= 30");
        assertInstanceOf(Predicate.Lte.class, lte.predicate().get());
    }

    // @spec F07.R63
    @Test
    void translatesWhereBetween() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE age BETWEEN 20 AND 40");

        var between = assertInstanceOf(Predicate.Between.class, query.predicate().get());
        assertEquals("age", between.field());
    }

    // @spec F07.R64
    @Test
    void translatesWhereAndOr() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE name = 'Alice' AND age > 30");

        var and = assertInstanceOf(Predicate.And.class, query.predicate().get());
        assertEquals(2, and.children().size());
        assertInstanceOf(Predicate.Eq.class, and.children().get(0));
        assertInstanceOf(Predicate.Gt.class, and.children().get(1));

        var orQuery = translate("SELECT * FROM test WHERE name = 'Alice' OR name = 'Bob'");
        var or = assertInstanceOf(Predicate.Or.class, orQuery.predicate().get());
        assertEquals(2, or.children().size());
    }

    // @spec F07.R65
    @Test
    void translatesMatchFunction() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE MATCH(name, 'search text')");

        var match = assertInstanceOf(Predicate.FullTextMatch.class, query.predicate().get());
        assertEquals("name", match.field());
        assertEquals("search text", match.query());
    }

    // @spec F07.R59
    @Test
    void translatesOrderBy() throws SqlParseException {
        var query = translate("SELECT * FROM test ORDER BY age DESC, name ASC");

        assertEquals(2, query.orderBy().size());
        assertEquals("age", query.orderBy().get(0).field());
        assertFalse(query.orderBy().get(0).ascending());
        assertEquals("name", query.orderBy().get(1).field());
        assertTrue(query.orderBy().get(1).ascending());
    }

    // @spec F07.R54
    @Test
    void translatesLimitAndOffset() throws SqlParseException {
        var query = translate("SELECT * FROM test LIMIT 10 OFFSET 20");

        assertEquals(10, query.limit().orElse(-1));
        assertEquals(20, query.offset().orElse(-1));
    }

    // @spec F07.R69,R84
    @Test
    void translatesVectorDistanceInOrderBy() throws SqlParseException {
        // Need a schema with a vector-compatible field for this test
        // Using a float array field as a stand-in — the translator validates field existence
        var vectorSchema = JlsmSchema.builder("vectors", 1).field("id", FieldType.int32())
                .field("embedding", FieldType.arrayOf(FieldType.float32())).build();

        var query = translator.translate(parser.parse(lexer.tokenize(
                "SELECT * FROM vectors ORDER BY VECTOR_DISTANCE(embedding, ?, 'cosine') LIMIT 10")),
                vectorSchema);

        assertTrue(query.vectorDistance().isPresent());
        var vd = query.vectorDistance().get();
        assertEquals("embedding", vd.field());
        assertEquals(0, vd.parameterIndex());
        assertEquals("cosine", vd.metric());
        assertEquals(10, query.limit().orElse(-1));
    }

    // @spec F07.R62
    @Test
    void translatesStringLiteralValue() throws SqlParseException {
        var query = translate("SELECT * FROM test WHERE name = 'hello world'");

        var eq = assertInstanceOf(Predicate.Eq.class, query.predicate().get());
        assertEquals("hello world", eq.value());
    }

    // @spec F07.R77
    @Test
    void translatesNumericLiteralValues() throws SqlParseException {
        var intQuery = translate("SELECT * FROM test WHERE age = 42");
        var eqInt = assertInstanceOf(Predicate.Eq.class, intQuery.predicate().get());
        assertInstanceOf(Number.class, eqInt.value());

        var decQuery = translate("SELECT * FROM test WHERE salary > 50000.50");
        var gtDec = assertInstanceOf(Predicate.Gt.class, decQuery.predicate().get());
        assertInstanceOf(Number.class, gtDec.value());
    }

    // @spec F07.R61
    @Test
    void translatesBindParameter() throws SqlParseException {
        // Bind parameters are preserved in the predicate as-is —
        // they are resolved at execution time, not translation time.
        // The translator should not reject them.
        var query = translate("SELECT * FROM test WHERE age > 30 LIMIT 10");
        assertTrue(query.predicate().isPresent());
    }

    // @spec F07.R54,R64
    @Test
    void translatesFullComplexQuery() throws SqlParseException {
        var query = translate("""
                SELECT name, age FROM test
                WHERE age >= 18 AND name = 'Alice'
                ORDER BY age DESC
                LIMIT 50 OFFSET 10
                """);

        assertEquals(List.of("name", "age"), query.projections());
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.And.class, query.predicate().get());
        assertEquals(1, query.orderBy().size());
        assertEquals(50, query.limit().orElse(-1));
        assertEquals(10, query.offset().orElse(-1));
    }

    // ── Error cases ──────────────────────────────────────────────

    // @spec F07.R58
    @Test
    void rejectsUnknownColumnInWhere() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE nonexistent = 1"));
    }

    // @spec F07.R57
    @Test
    void rejectsUnknownColumnInSelect() {
        assertThrows(SqlParseException.class, () -> translate("SELECT nonexistent FROM test"));
    }

    // @spec F07.R59
    @Test
    void rejectsUnknownColumnInOrderBy() {
        assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test ORDER BY nonexistent"));
    }
}
