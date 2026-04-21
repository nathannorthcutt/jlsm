package jlsm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Recursive descent parser for the supported SQL SELECT subset.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: a non-null, non-empty list of {@link Token}s (from {@link SqlLexer})</li>
 * <li>Returns: a {@link SqlAst.SelectStatement} representing the parsed query</li>
 * <li>Side effects: none</li>
 * <li>Error conditions: throws {@link SqlParseException} with position info on syntax errors,
 * unexpected tokens, or unsupported SQL constructs (INSERT, UPDATE, DELETE, JOIN, subqueries,
 * etc.)</li>
 * </ul>
 *
 * <p>
 * Grammar (simplified):
 *
 * <pre>
 * selectStatement := SELECT columnList FROM tableName
 *                    [WHERE expression]
 *                    [ORDER BY orderByList]
 *                    [LIMIT number]
 *                    [OFFSET number]
 *
 * columnList      := STAR | column (COMMA column)*
 * column          := identifier [AS identifier]
 * expression      := orExpr
 * orExpr          := andExpr (OR andExpr)*
 * andExpr         := notExpr (AND notExpr)*
 * notExpr         := NOT notExpr | comparison
 * comparison      := primary compareOp primary
 *                  | primary BETWEEN primary AND primary
 *                  | primary IS [NOT] NULL
 * primary         := literal | columnRef | parameter | functionCall | LPAREN expression RPAREN
 * functionCall    := MATCH LPAREN args RPAREN | VECTOR_DISTANCE LPAREN args RPAREN
 * </pre>
 *
 * <p>
 * Governed by: brief.md — Architecture section (recursive descent parser).
 */
public final class SqlParser {

    // @spec query.sql-query-support.R32 — maximum expression nesting depth is 128
    private static final int MAX_EXPRESSION_DEPTH = 128;

    /** Maximum allowed bind-parameter count to prevent integer overflow and resource exhaustion. */
    private static final int MAX_PARAMETERS = 10_000;

    /**
     * Maximum allowed list size (columns, ORDER BY clauses, function args) to prevent resource
     * exhaustion.
     */
    private static final int MAX_LIST_SIZE = 1_000;

    private List<Token> tokens;
    private int pos;
    private int parameterIndex;
    private int expressionDepth;

    /**
     * Parses a list of tokens into a SQL AST.
     *
     * @param tokens the token list from {@link SqlLexer#tokenize}, must not be null or empty
     * @return the parsed SELECT statement AST
     * @throws SqlParseException on syntax errors or unsupported SQL constructs
     */
    // @spec query.sql-query-support.R28,R29,R30,R97 — non-null non-empty token list; reset all
    // state on each call
    public SqlAst.SelectStatement parse(List<Token> tokens) throws SqlParseException {
        Objects.requireNonNull(tokens, "tokens");
        if (tokens.isEmpty()) {
            throw new SqlParseException("Empty token list", 0);
        }

        this.tokens = tokens;
        this.pos = 0;
        this.parameterIndex = 0;
        this.expressionDepth = 0;

        return parseSelectStatement();
    }

    // ── Token navigation ─────────────────────────────────────────

    private Token peek() throws SqlParseException {
        if (pos >= tokens.size()) {
            final int position = tokens.isEmpty() ? 0 : tokens.getLast().position();
            throw new SqlParseException("Unexpected end of token stream (missing EOF token)",
                    position);
        }
        return tokens.get(pos);
    }

    // @spec query.sql-query-support.R45 — parser must not advance past EOF; repeated reads at EOF
    // return EOF without
    // error
    private Token advance() throws SqlParseException {
        final Token token = peek();
        if (token.type() != TokenType.EOF) {
            pos++;
        }
        return token;
    }

    // @spec query.sql-query-support.R44 — unexpected token raises SqlParseException carrying the
    // token's position
    private Token expect(TokenType type) throws SqlParseException {
        final Token token = peek();
        if (token.type() != type) {
            throw new SqlParseException("Expected " + type + " but found " + token.type() + " '"
                    + token.text() + "' at position " + token.position(), token.position());
        }
        return advance();
    }

    private boolean check(TokenType type) throws SqlParseException {
        return peek().type() == type;
    }

    private boolean match(TokenType type) throws SqlParseException {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    // ── Statement parsing ────────────────────────────────────────

    // @spec query.sql-query-support.R31 — reject non-SELECT statements with explicit keyword in the
    // error message
    private SqlAst.SelectStatement parseSelectStatement() throws SqlParseException {
        final Token first = peek();

        // Reject non-SELECT statements
        if (first.type() == TokenType.IDENTIFIER) {
            final String upper = first.text().toUpperCase();
            if (upper.equals("INSERT") || upper.equals("UPDATE") || upper.equals("DELETE")
                    || upper.equals("CREATE") || upper.equals("DROP") || upper.equals("ALTER")) {
                throw new SqlParseException("Only SELECT statements are supported, found '"
                        + first.text() + "' at position " + first.position(), first.position());
            }
        }

        expect(TokenType.SELECT);

        // Parse columns
        final List<SqlAst.Column> columns = parseColumnList();

        // FROM
        expect(TokenType.FROM);
        final Token tableName = expect(TokenType.IDENTIFIER);

        // Optional WHERE
        Optional<SqlAst.Expression> where = Optional.empty();
        if (match(TokenType.WHERE)) {
            where = Optional.of(parseExpression());
        }

        // Optional ORDER BY
        final List<SqlAst.OrderByClause> orderBy;
        if (check(TokenType.ORDER)) {
            orderBy = parseOrderBy();
        } else {
            orderBy = List.of();
        }

        // Optional LIMIT
        Optional<Integer> limit = Optional.empty();
        if (match(TokenType.LIMIT)) {
            limit = Optional.of(parseIntegerLiteral());
        }

        // Optional OFFSET
        Optional<Integer> offset = Optional.empty();
        if (match(TokenType.OFFSET)) {
            offset = Optional.of(parseIntegerLiteral());
        }

        // @spec query.sql-query-support.R103 — consume all tokens up to EOF; reject trailing tokens
        final Token trailing = peek();
        if (trailing.type() != TokenType.EOF) {
            throw new SqlParseException("Unexpected trailing token " + trailing.type() + " '"
                    + trailing.text() + "' at position " + trailing.position()
                    + " — expected end of statement", trailing.position());
        }

        return new SqlAst.SelectStatement(columns, tableName.text(), where, orderBy, limit, offset);
    }

    // @spec query.sql-query-support.R41 — LIMIT/OFFSET require integer literals; decimals or other
    // tokens throw
    private int parseIntegerLiteral() throws SqlParseException {
        final Token num = expect(TokenType.NUMBER_LITERAL);
        try {
            return Integer.parseInt(num.text());
        } catch (NumberFormatException e) {
            throw new SqlParseException(
                    "Expected integer but found '" + num.text() + "' at position " + num.position(),
                    num.position(), e);
        }
    }

    // ── Column list ──────────────────────────────────────────────

    // @spec query.sql-query-support.R38,R39 — SELECT * yields a single Wildcard; otherwise named
    // columns with optional
    // AS alias
    private List<SqlAst.Column> parseColumnList() throws SqlParseException {
        if (match(TokenType.STAR)) {
            return List.of(new SqlAst.Column.Wildcard());
        }

        final var columns = new ArrayList<SqlAst.Column>();
        columns.add(parseColumn());

        while (match(TokenType.COMMA)) {
            if (columns.size() >= MAX_LIST_SIZE) {
                final Token token = peek();
                throw new SqlParseException("Column list exceeds maximum of " + MAX_LIST_SIZE
                        + " at position " + token.position(), token.position());
            }
            columns.add(parseColumn());
        }

        return columns;
    }

    private SqlAst.Column parseColumn() throws SqlParseException {
        final Token name = expect(TokenType.IDENTIFIER);
        Optional<String> alias = Optional.empty();

        if (match(TokenType.AS)) {
            final Token aliasToken = expect(TokenType.IDENTIFIER);
            alias = Optional.of(aliasToken.text());
        }

        return new SqlAst.Column.Named(name.text(), alias);
    }

    // ── ORDER BY ─────────────────────────────────────────────────

    private List<SqlAst.OrderByClause> parseOrderBy() throws SqlParseException {
        expect(TokenType.ORDER);
        expect(TokenType.BY);

        final var clauses = new ArrayList<SqlAst.OrderByClause>();
        clauses.add(parseOrderByClause());

        while (match(TokenType.COMMA)) {
            if (clauses.size() >= MAX_LIST_SIZE) {
                final Token token = peek();
                throw new SqlParseException("ORDER BY clause list exceeds maximum of "
                        + MAX_LIST_SIZE + " at position " + token.position(), token.position());
            }
            clauses.add(parseOrderByClause());
        }

        return clauses;
    }

    // @spec query.sql-query-support.R40 — ORDER BY clauses default to ASC; DESC recorded as
    // ascending=false
    private SqlAst.OrderByClause parseOrderByClause() throws SqlParseException {
        final SqlAst.Expression expr = parseOrderByExpression();
        boolean ascending = true;

        if (match(TokenType.ASC)) {
            ascending = true;
        } else if (match(TokenType.DESC)) {
            ascending = false;
        }

        return new SqlAst.OrderByClause(expr, ascending);
    }

    // @spec query.sql-query-support.R43 — parser allows MATCH and VECTOR_DISTANCE function calls in
    // ORDER BY position
    private SqlAst.Expression parseOrderByExpression() throws SqlParseException {
        // Could be a function call (VECTOR_DISTANCE) or a column ref
        if (check(TokenType.VECTOR_DISTANCE) || check(TokenType.MATCH)) {
            return parseFunctionCall();
        }
        final Token name = expect(TokenType.IDENTIFIER);
        return new SqlAst.Expression.ColumnRef(name.text());
    }

    // ── Expression parsing (precedence climbing) ─────────────────

    // @spec query.sql-query-support.R33 — operator precedence: OR < AND < NOT <
    // comparison/BETWEEN/IS NULL
    private SqlAst.Expression parseExpression() throws SqlParseException {
        return parseOr();
    }

    private SqlAst.Expression parseOr() throws SqlParseException {
        SqlAst.Expression left = parseAnd();
        int depthAdded = 0;

        while (match(TokenType.OR)) {
            expressionDepth++;
            depthAdded++;
            if (expressionDepth > MAX_EXPRESSION_DEPTH) {
                final Token token = peek();
                throw new SqlParseException("Expression nesting depth exceeds maximum of "
                        + MAX_EXPRESSION_DEPTH + " at position " + token.position(),
                        token.position());
            }
            final SqlAst.Expression right = parseAnd();
            left = new SqlAst.Expression.Logical(left, SqlAst.LogicalOp.OR, right);
        }

        expressionDepth -= depthAdded;
        return left;
    }

    private SqlAst.Expression parseAnd() throws SqlParseException {
        SqlAst.Expression left = parseNot();
        int depthAdded = 0;

        // BETWEEN...AND is consumed by parseComparison before reaching here,
        // so any AND token at this level is always a logical connective.
        while (match(TokenType.AND)) {
            expressionDepth++;
            depthAdded++;
            if (expressionDepth > MAX_EXPRESSION_DEPTH) {
                final Token token = peek();
                throw new SqlParseException("Expression nesting depth exceeds maximum of "
                        + MAX_EXPRESSION_DEPTH + " at position " + token.position(),
                        token.position());
            }
            final SqlAst.Expression right = parseNot();
            left = new SqlAst.Expression.Logical(left, SqlAst.LogicalOp.AND, right);
        }

        expressionDepth -= depthAdded;
        return left;
    }

    private SqlAst.Expression parseNot() throws SqlParseException {
        if (match(TokenType.NOT)) {
            expressionDepth++;
            if (expressionDepth > MAX_EXPRESSION_DEPTH) {
                final Token token = peek();
                throw new SqlParseException("Expression nesting depth exceeds maximum of "
                        + MAX_EXPRESSION_DEPTH + " at position " + token.position(),
                        token.position());
            }
            final SqlAst.Expression operand = parseNot();
            expressionDepth--;
            return new SqlAst.Expression.Not(operand);
        }
        return parseComparison();
    }

    // @spec query.sql-query-support.R34,R35 — BETWEEN expr AND expr consumes AND as range syntax;
    // IS [NOT] NULL parsed
    private SqlAst.Expression parseComparison() throws SqlParseException {
        final SqlAst.Expression left = parsePrimary();

        // BETWEEN
        if (match(TokenType.BETWEEN)) {
            final SqlAst.Expression low = parsePrimary();
            expect(TokenType.AND);
            final SqlAst.Expression high = parsePrimary();
            return new SqlAst.Expression.Between(left, low, high);
        }

        // IS [NOT] NULL
        if (match(TokenType.IS)) {
            final boolean negated = match(TokenType.NOT);
            expect(TokenType.NULL);
            return new SqlAst.Expression.IsNull(left, negated);
        }

        // Comparison operators
        final SqlAst.ComparisonOp op = matchComparisonOp();
        if (op != null) {
            final SqlAst.Expression right = parsePrimary();
            return new SqlAst.Expression.Comparison(left, op, right);
        }

        return left;
    }

    private SqlAst.ComparisonOp matchComparisonOp() throws SqlParseException {
        return switch (peek().type()) {
            case EQ -> {
                advance();
                yield SqlAst.ComparisonOp.EQ;
            }
            case NE -> {
                advance();
                yield SqlAst.ComparisonOp.NE;
            }
            case LT -> {
                advance();
                yield SqlAst.ComparisonOp.LT;
            }
            case LTE -> {
                advance();
                yield SqlAst.ComparisonOp.LTE;
            }
            case GT -> {
                advance();
                yield SqlAst.ComparisonOp.GT;
            }
            case GTE -> {
                advance();
                yield SqlAst.ComparisonOp.GTE;
            }
            default -> null;
        };
    }

    // ── Primary expressions ──────────────────────────────────────

    // @spec query.sql-query-support.R36,R37 — parenthesised expressions; bind parameters assigned
    // sequential zero-based
    // indices
    private SqlAst.Expression parsePrimary() throws SqlParseException {
        final Token token = peek();

        return switch (token.type()) {
            case STRING_LITERAL -> {
                advance();
                yield new SqlAst.Expression.StringLiteral(token.text());
            }
            case NUMBER_LITERAL -> {
                advance();
                yield new SqlAst.Expression.NumberLiteral(token.text());
            }
            case MINUS -> {
                advance(); // consume the minus token
                final Token num = expect(TokenType.NUMBER_LITERAL);
                yield new SqlAst.Expression.NumberLiteral("-" + num.text());
            }
            case TRUE -> {
                advance();
                yield new SqlAst.Expression.BooleanLiteral(true);
            }
            case FALSE -> {
                advance();
                yield new SqlAst.Expression.BooleanLiteral(false);
            }
            case PARAMETER -> {
                advance();
                if (parameterIndex >= MAX_PARAMETERS) {
                    throw new SqlParseException("Bind parameter count exceeds maximum of "
                            + MAX_PARAMETERS + " at position " + token.position(),
                            token.position());
                }
                yield new SqlAst.Expression.Parameter(parameterIndex++);
            }
            case MATCH, VECTOR_DISTANCE -> parseFunctionCall();
            case IDENTIFIER -> {
                advance();
                yield new SqlAst.Expression.ColumnRef(token.text());
            }
            case LPAREN -> {
                expressionDepth++;
                if (expressionDepth > MAX_EXPRESSION_DEPTH) {
                    throw new SqlParseException("Expression nesting depth exceeds maximum of "
                            + MAX_EXPRESSION_DEPTH + " at position " + token.position(),
                            token.position());
                }
                advance(); // consume (
                final SqlAst.Expression expr = parseExpression();
                expect(TokenType.RPAREN);
                expressionDepth--;
                yield expr;
            }
            default -> throw new SqlParseException("Unexpected token " + token.type() + " '"
                    + token.text() + "' at position " + token.position(), token.position());
        };
    }

    // @spec query.sql-query-support.R42,R43 — MATCH/VECTOR_DISTANCE parsed as FunctionCall with
    // uppercased name in both
    // WHERE and ORDER BY
    private SqlAst.Expression.FunctionCall parseFunctionCall() throws SqlParseException {
        final Token name = advance(); // MATCH or VECTOR_DISTANCE

        expressionDepth++;
        if (expressionDepth > MAX_EXPRESSION_DEPTH) {
            throw new SqlParseException("Expression nesting depth exceeds maximum of "
                    + MAX_EXPRESSION_DEPTH + " at position " + name.position(), name.position());
        }

        expect(TokenType.LPAREN);

        final var args = new ArrayList<SqlAst.Expression>();
        if (!check(TokenType.RPAREN)) {
            args.add(parsePrimary());
            while (match(TokenType.COMMA)) {
                if (args.size() >= MAX_LIST_SIZE) {
                    final Token token = peek();
                    throw new SqlParseException("Function argument list exceeds maximum of "
                            + MAX_LIST_SIZE + " at position " + token.position(), token.position());
                }
                args.add(parsePrimary());
            }
        }

        expect(TokenType.RPAREN);
        expressionDepth--;
        return new SqlAst.Expression.FunctionCall(name.text().toUpperCase(), args);
    }
}
