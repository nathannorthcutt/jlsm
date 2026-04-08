package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SqlLexer — targets contract gaps discovered during spec analysis round 1.
 */
class SqlLexerAdversarialTest {

    private final SqlLexer lexer = new SqlLexer();

    // ── FINDING-4: Trailing-dot numeric literal ──────────────────────

    /**
     * FINDING-4: "42." (trailing dot, no fractional part) must be rejected as malformed. Updated by
     * audit F-R1.cb.1.4 — lexer now throws SqlParseException for trailing-dot literals
     */
    @Test
    void trailingDotNumericLiteral() {
        // The lexer rejects trailing-dot numeric literals as malformed
        assertThrows(SqlParseException.class, () -> lexer.tokenize("42."));
    }

    // ── FINDING-6: SQL keywords as field names ───────────────────────

    /** FINDING-6: field named "order" (SQL keyword) is tokenized as keyword, not identifier */
    @Test
    void keywordAsFieldNameTokenizedAsKeyword() throws SqlParseException {
        var tokens = lexer.tokenize("order");
        // "order" should be tokenized as ORDER keyword — this means it can't be used as a field
        // name
        assertEquals(TokenType.ORDER, tokens.get(0).type());
    }

    /** FINDING-6: common field names that collide with SQL keywords */
    @Test
    void commonFieldNameKeywordCollisions() throws SqlParseException {
        // All of these are valid field names but will be tokenized as keywords
        var keywords = List.of("select", "from", "where", "order", "by", "as", "and", "or", "not",
                "in", "is", "like", "between", "limit", "offset", "null", "true", "false");

        for (var name : keywords) {
            var tokens = lexer.tokenize(name);
            assertNotEquals(TokenType.IDENTIFIER, tokens.get(0).type(),
                    "'" + name + "' is tokenized as keyword, not identifier — "
                            + "schemas with this field name are broken");
        }
    }

    /** FINDING-6: query with keyword field name in WHERE should fail to parse */
    @Test
    void selectWithKeywordFieldNameFails() {
        // Schema has a field named "order" but the parser can't handle it
        var schema = JlsmSchema.builder("test", 1).field("order", FieldType.int32()).build();

        // This should work but currently fails because "order" is tokenized as ORDER keyword
        assertThrows(SqlParseException.class,
                () -> JlsmSql.parse("SELECT * FROM test WHERE order = 1", schema));
    }
}
