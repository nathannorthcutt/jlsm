package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JlsmSqlTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("active", FieldType.boolean_()).build();

    // @spec F07.R2,R7,R75
    @Test
    void parsesSimpleSelectQuery() throws SqlParseException {
        var query = JlsmSql.parse("SELECT * FROM users", SCHEMA);

        assertTrue(query.projections().isEmpty());
        assertTrue(query.predicate().isEmpty());
    }

    // @spec F07.R2,R7,R57,R62
    @Test
    void parsesQueryWithWhereClause() throws SqlParseException {
        var query = JlsmSql.parse("SELECT name FROM users WHERE age > 30", SCHEMA);

        assertEquals(1, query.projections().size());
        assertEquals("name", query.projections().getFirst());
        assertTrue(query.predicate().isPresent());
        assertInstanceOf(Predicate.Gt.class, query.predicate().get());
    }

    // @spec F07.R5
    @Test
    void rejectsBlankSql() {
        assertThrows(SqlParseException.class, () -> JlsmSql.parse("   ", SCHEMA));
    }

    // @spec F07.R3
    @Test
    void rejectsNullSql() {
        assertThrows(NullPointerException.class, () -> JlsmSql.parse(null, SCHEMA));
    }

    // @spec F07.R4
    @Test
    void rejectsNullSchema() {
        assertThrows(NullPointerException.class, () -> JlsmSql.parse("SELECT * FROM t", null));
    }
}
