package jlsm.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Adversarial tests for shared-state concerns in jlsm-sql.
 */
class SharedStateAdversarialTest {

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();

    // Finding: F-R1.shared_state.1.2
    // Bug: parseOr()/parseAnd() while loops build unbounded left-associative binary AST
    // without incrementing expressionDepth, so chains of OR/AND clauses bypass depth limit
    // and produce deep ASTs that cause StackOverflowError in downstream recursive consumers.
    // Correct behavior: parser should throw SqlParseException when OR/AND chain depth exceeds
    // limit.
    // Fix location: SqlParser.parseOr (lines 252-260) and SqlParser.parseAnd (lines 262-274)
    // Regression watch: ensure moderate OR/AND chains (well under 128) still parse correctly.
    @Test
    void test_parseOrAnd_unboundedChain_throwsSqlParseException() {
        // Build "SELECT * FROM t WHERE x=1 OR x=1 OR x=1 ..." with 200 OR clauses
        // Each OR adds a nesting level to the binary AST. 200 > MAX_EXPRESSION_DEPTH (128).
        final int clauses = 200;
        var sb = new StringBuilder("SELECT * FROM t WHERE x = 1");
        for (int i = 1; i < clauses; i++) {
            sb.append(" OR x = 1");
        }

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Long OR chain should throw SqlParseException due to expression depth limit");
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: parameterIndex++ overflows on Integer.MAX_VALUE+ parameters (no bound check).
    // More practically, there is no upper bound on parameter count at all, allowing
    // unbounded resource consumption. A MAX_PARAMETERS limit should be enforced.
    // Correct behavior: parser should throw SqlParseException when parameter count exceeds limit.
    // Fix location: SqlParser.parsePrimary PARAMETER case (line ~394) — add overflow/limit guard.
    // Regression watch: ensure normal parameter counts (well under limit) still parse correctly.
    @Test
    void test_parsePrimary_parameterIndexOverflow_throwsSqlParseException() {
        // Build "SELECT * FROM t WHERE MATCH(?, ?, ..., ?) = 'x'" with 10,001 parameters
        // to exceed the expected MAX_PARAMETERS limit (10,000).
        final int paramCount = 10_001;
        var sb = new StringBuilder("SELECT * FROM t WHERE MATCH(");
        for (int i = 0; i < paramCount; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append('?');
        }
        sb.append(") = 'x'");

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Excessive parameter count should throw SqlParseException");
    }

    // Finding: F-R1.shared_state.1.4
    // Bug: peek() uses assert-only bounds check — IndexOutOfBoundsException in production
    // Correct behavior: peek() should throw SqlParseException when pos >= tokens.size()
    // Fix location: SqlParser.peek (lines 84-87) — replace assert with runtime guard
    // Regression watch: normal parsing must still work; ensure peek() is called before every
    // tokens.get()
    @Test
    void test_peek_assertOnlyBoundsCheck_throwsSqlParseException() {
        // Construct a token list without a trailing EOF token.
        // The parser will advance past the last real token, then peek() will
        // attempt tokens.get(pos) where pos == tokens.size(), causing IOOBE
        // instead of SqlParseException when assertions are disabled.
        var tokensWithoutEof = List.of(new Token(TokenType.SELECT, "SELECT", 0),
                new Token(TokenType.STAR, "*", 7), new Token(TokenType.FROM, "FROM", 9),
                new Token(TokenType.IDENTIFIER, "t", 14)
        // No EOF token — parser will run off the end
        );

        // Must throw SqlParseException, not IndexOutOfBoundsException
        assertThrows(SqlParseException.class, () -> parser.parse(tokensWithoutEof),
                "Token list without EOF should produce SqlParseException, not IndexOutOfBoundsException");
    }

    // Finding: F-R1.shared_state.1.6
    // Bug: parseFunctionCall skips expressionDepth tracking for its LPAREN/RPAREN pair,
    // so deeply nested MATCH(MATCH(MATCH(...))) bypasses MAX_EXPRESSION_DEPTH guard
    // and causes StackOverflowError instead of SqlParseException.
    // Correct behavior: parser should throw SqlParseException when function nesting exceeds depth
    // limit.
    // Fix location: SqlParser.parseFunctionCall (lines 430-444) — add expressionDepth guard.
    // Regression watch: ensure single and moderately nested function calls still parse correctly.
    @Test
    void test_parseFunctionCall_deepNesting_throwsSqlParseException() {
        // Build deeply nested MATCH(MATCH(MATCH(...))) that bypasses expressionDepth tracking.
        // parseFunctionCall never increments expressionDepth, so nesting is unlimited.
        // Use enough depth to overflow the call stack — each level adds ~2 frames
        // (parsePrimary → parseFunctionCall), so we need thousands.
        final int depth = 5000;
        var sb = new StringBuilder("SELECT * FROM t WHERE ");
        sb.append("MATCH(".repeat(depth));
        sb.append("?");
        sb.append(") = 'x'".repeat(depth));

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Deeply nested function calls should throw SqlParseException, not StackOverflowError");
    }

    // Finding: F-R1.shared_state.1.7
    // Bug: parseColumnList, parseOrderBy, and parseFunctionCall use unbounded ArrayLists
    // that grow without limit on crafted input, enabling resource exhaustion (OOM).
    // Correct behavior: parser should throw SqlParseException when list sizes exceed a limit.
    // Fix location: SqlParser.parseColumnList (~line 192), parseOrderBy (~line 220),
    // parseFunctionCall (~line 442) — add MAX_LIST_SIZE guard in while loops.
    // Regression watch: ensure normal-sized column lists / ORDER BY / function args still parse.
    @Test
    void test_parseColumnList_unboundedGrowth_throwsSqlParseException() {
        // Build "SELECT a, a, a, ..., a FROM t" with 2000 columns — well beyond any reasonable
        // limit.
        final int columnCount = 2000;
        var sb = new StringBuilder("SELECT a");
        for (int i = 1; i < columnCount; i++) {
            sb.append(", a");
        }
        sb.append(" FROM t");

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Excessively long column list should throw SqlParseException");
    }

    @Test
    void test_parseOrderBy_unboundedGrowth_throwsSqlParseException() {
        // Build "SELECT * FROM t ORDER BY a, a, ..., a" with 2000 ORDER BY clauses.
        final int clauseCount = 2000;
        var sb = new StringBuilder("SELECT * FROM t ORDER BY a");
        for (int i = 1; i < clauseCount; i++) {
            sb.append(", a");
        }

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Excessively long ORDER BY list should throw SqlParseException");
    }

    @Test
    void test_parseFunctionCall_unboundedArgList_throwsSqlParseException() {
        // Build "SELECT * FROM t WHERE MATCH(?, ?, ..., ?) = 'x'" with 2000 args.
        // This tests arg list size, not parameter count (MAX_PARAMETERS is 10000).
        final int argCount = 2000;
        var sb = new StringBuilder("SELECT * FROM t WHERE MATCH(");
        for (int i = 0; i < argCount; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append('?');
        }
        sb.append(") = 'x'");

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Excessively long function argument list should throw SqlParseException");
    }

    @Test
    void test_parseColumnList_moderateSize_succeeds() {
        // Ensure a moderate column list (50) still works fine.
        var sb = new StringBuilder("SELECT a");
        for (int i = 1; i < 50; i++) {
            sb.append(", a");
        }
        sb.append(" FROM t");

        assertDoesNotThrow(() -> parser.parse(lexer.tokenize(sb.toString())),
                "Moderate column list should parse without error");
    }

    // Finding: F-R1.shared_state.2.1
    // Bug: toUpperCase() without Locale.ROOT produces locale-dependent uppercase (e.g., Turkish 'i'
    // → 'İ')
    // Correct behavior: keywords containing 'i' (IN, IS, LIMIT, LIKE, BETWEEN) must be recognized
    // in any locale
    // Fix location: SqlLexer.readIdentifierOrKeyword line 220 — use toUpperCase(Locale.ROOT)
    // Regression watch: ensure all keywords still recognized in default locale after fix
    @Test
    void test_readIdentifierOrKeyword_turkishLocale_keywordMisrecognition()
            throws SqlParseException {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            List<Token> tokens = lexer.tokenize("select * from t where x in (1, 2)");
            // Find the token whose original text is "in" (lowercase — triggers Turkish İ bug)
            Token inToken = tokens.stream().filter(t -> t.text().equals("in")).findFirst()
                    .orElseThrow(() -> new AssertionError("No token with source text 'in' found"));
            assertEquals(TokenType.IN, inToken.type(),
                    "Keyword 'IN' must be recognized as IN token even in Turkish locale");
        } finally {
            Locale.setDefault(original);
        }
    }

    // Finding: F-R1.shared_state.1.1
    // Bug: parseNot() self-recurses without incrementing expressionDepth,
    // so a chain of NOT tokens causes StackOverflowError instead of SqlParseException.
    // Correct behavior: parser should throw SqlParseException when NOT nesting exceeds depth limit.
    // Fix location: SqlParser.parseNot (lines 276-281) — add expressionDepth guard before
    // recursion.
    // Regression watch: ensure single NOT and moderate NOT chains still parse correctly.
    @Test
    void test_parseNot_unboundedRecursion_stackOverflow() {
        // Build "SELECT * FROM t WHERE NOT NOT NOT ... NOT x = 1" with 500 NOTs
        final int depth = 500;
        var sb = new StringBuilder("SELECT * FROM t WHERE ");
        sb.append("NOT ".repeat(depth));
        sb.append("x = 1");

        assertThrows(SqlParseException.class, () -> parser.parse(lexer.tokenize(sb.toString())),
                "Deeply nested NOT chain should throw SqlParseException, not StackOverflowError");
    }
}
