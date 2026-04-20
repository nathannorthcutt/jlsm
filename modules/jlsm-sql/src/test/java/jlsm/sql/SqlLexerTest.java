package jlsm.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlLexerTest {

    private final SqlLexer lexer = new SqlLexer();

    private List<Token> tokenize(String sql) throws SqlParseException {
        return lexer.tokenize(sql);
    }

    // ── Happy path ───────────────────────────────────────────────

    // @spec F07.R8,R10,R18
    @Test
    void tokenizesSelectStar() throws SqlParseException {
        var tokens = tokenize("SELECT * FROM users");

        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("users", tokens.get(3).text());
        assertEquals(TokenType.EOF, tokens.getLast().type());
    }

    // @spec F07.R16,R18
    @Test
    void tokenizesColumnProjection() throws SqlParseException {
        var tokens = tokenize("SELECT a, b FROM t");

        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals("a", tokens.get(1).text());
        assertEquals(TokenType.COMMA, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("b", tokens.get(3).text());
        assertEquals(TokenType.FROM, tokens.get(4).type());
    }

    // @spec F07.R10,R17
    @Test
    void tokenizesWhereWithComparison() throws SqlParseException {
        var tokens = tokenize("SELECT * FROM t WHERE age > 30");

        assertEquals(TokenType.WHERE, tokens.get(4).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(5).type());
        assertEquals("age", tokens.get(5).text());
        assertEquals(TokenType.GT, tokens.get(6).type());
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(7).type());
        assertEquals("30", tokens.get(7).text());
    }

    // @spec F07.R17
    @Test
    void tokenizesAllComparisonOperators() throws SqlParseException {
        var tokens = tokenize("= != <> < <= > >=");

        assertEquals(TokenType.EQ, tokens.get(0).type());
        assertEquals(TokenType.NE, tokens.get(1).type());
        assertEquals(TokenType.NE, tokens.get(2).type());
        assertEquals(TokenType.LT, tokens.get(3).type());
        assertEquals(TokenType.LTE, tokens.get(4).type());
        assertEquals(TokenType.GT, tokens.get(5).type());
        assertEquals(TokenType.GTE, tokens.get(6).type());
    }

    // @spec F07.R12
    @Test
    void tokenizesStringLiteral() throws SqlParseException {
        var tokens = tokenize("'hello'");

        assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
        assertEquals("hello", tokens.get(0).text());
    }

    // @spec F07.R13
    @Test
    void tokenizesStringLiteralWithEscapedQuote() throws SqlParseException {
        var tokens = tokenize("'it''s'");

        assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
        assertEquals("it's", tokens.get(0).text());
    }

    // @spec F07.R15,R53
    @Test
    void tokenizesNumericLiterals() throws SqlParseException {
        var intTokens = tokenize("42");
        assertEquals(TokenType.NUMBER_LITERAL, intTokens.get(0).type());
        assertEquals("42", intTokens.get(0).text());

        var decTokens = tokenize("3.14");
        assertEquals(TokenType.NUMBER_LITERAL, decTokens.get(0).type());
        assertEquals("3.14", decTokens.get(0).text());
    }

    // @spec F07.R19
    @Test
    void tokenizesBindParameter() throws SqlParseException {
        var tokens = tokenize("?");

        assertEquals(TokenType.PARAMETER, tokens.get(0).type());
        assertEquals("?", tokens.get(0).text());
    }

    // @spec F07.R10,R11
    @Test
    void tokenizesKeywordsCaseInsensitive() throws SqlParseException {
        var lower = tokenize("select * from t");
        assertEquals(TokenType.SELECT, lower.get(0).type());
        assertEquals(TokenType.FROM, lower.get(2).type());

        var mixed = tokenize("SeLeCt * FrOm t");
        assertEquals(TokenType.SELECT, mixed.get(0).type());
        assertEquals(TokenType.FROM, mixed.get(2).type());
    }

    // @spec F07.R10
    @Test
    void tokenizesBooleanAndNull() throws SqlParseException {
        var tokens = tokenize("TRUE FALSE NULL");

        assertEquals(TokenType.TRUE, tokens.get(0).type());
        assertEquals(TokenType.FALSE, tokens.get(1).type());
        assertEquals(TokenType.NULL, tokens.get(2).type());
    }

    // @spec F07.R10
    @Test
    void tokenizesOrderByLimitOffset() throws SqlParseException {
        var tokens = tokenize("ORDER BY x DESC LIMIT 10 OFFSET 5");

        assertEquals(TokenType.ORDER, tokens.get(0).type());
        assertEquals(TokenType.BY, tokens.get(1).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals(TokenType.DESC, tokens.get(3).type());
        assertEquals(TokenType.LIMIT, tokens.get(4).type());
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(5).type());
        assertEquals(TokenType.OFFSET, tokens.get(6).type());
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(7).type());
    }

    // @spec F07.R10
    @Test
    void tokenizesBetween() throws SqlParseException {
        var tokens = tokenize("age BETWEEN 1 AND 10");

        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals(TokenType.BETWEEN, tokens.get(1).type());
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(2).type());
        assertEquals(TokenType.AND, tokens.get(3).type());
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(4).type());
    }

    // @spec F07.R10,R42
    @Test
    void tokenizesMatchFunction() throws SqlParseException {
        var tokens = tokenize("MATCH(title, 'search text')");

        assertEquals(TokenType.MATCH, tokens.get(0).type());
        assertEquals(TokenType.LPAREN, tokens.get(1).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals(TokenType.COMMA, tokens.get(3).type());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(4).type());
        assertEquals("search text", tokens.get(4).text());
        assertEquals(TokenType.RPAREN, tokens.get(5).type());
    }

    // @spec F07.R10,R42
    @Test
    void tokenizesVectorDistanceFunction() throws SqlParseException {
        var tokens = tokenize("VECTOR_DISTANCE(embedding, ?, 'cosine')");

        assertEquals(TokenType.VECTOR_DISTANCE, tokens.get(0).type());
        assertEquals(TokenType.LPAREN, tokens.get(1).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals(TokenType.COMMA, tokens.get(3).type());
        assertEquals(TokenType.PARAMETER, tokens.get(4).type());
        assertEquals(TokenType.COMMA, tokens.get(5).type());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(6).type());
        assertEquals("cosine", tokens.get(6).text());
        assertEquals(TokenType.RPAREN, tokens.get(7).type());
    }

    // @spec F07.R10
    @Test
    void tokenizesColumnAlias() throws SqlParseException {
        var tokens = tokenize("SELECT name AS n FROM t");

        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals(TokenType.AS, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("n", tokens.get(3).text());
    }

    // @spec F07.R8,R27
    @Test
    void alwaysEndsWithEof() throws SqlParseException {
        var empty = tokenize("");
        assertEquals(1, empty.size());
        assertEquals(TokenType.EOF, empty.getFirst().type());

        var nonEmpty = tokenize("SELECT");
        assertEquals(TokenType.EOF, nonEmpty.getLast().type());
    }

    // @spec F07.R23
    @Test
    void tracksPositionCorrectly() throws SqlParseException {
        var tokens = tokenize("SELECT *");
        assertEquals(0, tokens.get(0).position()); // SELECT at 0
        assertEquals(7, tokens.get(1).position()); // * at 7
    }

    // ── Error cases ──────────────────────────────────────────────

    // @spec F07.R14
    @Test
    void rejectsUnterminatedStringLiteral() {
        assertThrows(SqlParseException.class, () -> tokenize("'unterminated"));
    }

    // @spec F07.R21
    @Test
    void rejectsUnrecognisedCharacter() {
        assertThrows(SqlParseException.class, () -> tokenize("SELECT @invalid"));
    }

    // @spec F07.R9
    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> tokenize(null));
    }
}
