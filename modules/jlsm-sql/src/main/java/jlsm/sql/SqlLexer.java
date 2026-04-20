package jlsm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tokenizes a SQL string into a list of {@link Token}s.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: a non-null SQL string</li>
 * <li>Returns: an immutable list of tokens, always ending with {@link TokenType#EOF}</li>
 * <li>Side effects: none (stateless — new instance per tokenization)</li>
 * <li>Error conditions: throws {@link SqlParseException} on unrecognised characters or unterminated
 * string literals, with position information</li>
 * </ul>
 *
 * <p>
 * Handles: SQL keywords (case-insensitive), single-quoted string literals, numeric literals
 * (integers and decimals), identifiers, comparison operators ({@code =}, {@code !=}, {@code <>},
 * {@code <}, {@code <=}, {@code >}, {@code >=}), punctuation, and positional bind parameters
 * ({@code ?}).
 */
// @spec F07.R96 — stateless, safe for concurrent use; no shared mutable state between tokenize
// calls
public final class SqlLexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("SELECT", TokenType.SELECT), Map.entry("FROM", TokenType.FROM),
            Map.entry("WHERE", TokenType.WHERE), Map.entry("AND", TokenType.AND),
            Map.entry("OR", TokenType.OR), Map.entry("NOT", TokenType.NOT),
            Map.entry("ORDER", TokenType.ORDER), Map.entry("BY", TokenType.BY),
            Map.entry("ASC", TokenType.ASC), Map.entry("DESC", TokenType.DESC),
            Map.entry("LIMIT", TokenType.LIMIT), Map.entry("OFFSET", TokenType.OFFSET),
            Map.entry("BETWEEN", TokenType.BETWEEN), Map.entry("IS", TokenType.IS),
            Map.entry("NULL", TokenType.NULL), Map.entry("TRUE", TokenType.TRUE),
            Map.entry("FALSE", TokenType.FALSE), Map.entry("AS", TokenType.AS),
            Map.entry("LIKE", TokenType.LIKE), Map.entry("IN", TokenType.IN),
            Map.entry("MATCH", TokenType.MATCH),
            Map.entry("VECTOR_DISTANCE", TokenType.VECTOR_DISTANCE));

    /**
     * Tokenizes the given SQL string.
     *
     * @param sql the SQL string to tokenize, must not be null
     * @return an immutable list of tokens ending with EOF
     * @throws SqlParseException if the input contains unrecognised characters or unterminated
     *             string literals
     */
    // @spec F07.R8,R9,R10,R11,R17,R18,R19,R20,R21,R22,R27,R100 — tokenization contract
    public List<Token> tokenize(String sql) throws SqlParseException {
        Objects.requireNonNull(sql, "sql");

        final var tokens = new ArrayList<Token>();
        final int len = sql.length();
        int pos = 0;

        while (pos < len) {
            final char ch = sql.charAt(pos);

            // Skip whitespace
            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
            }

            // Single-quoted string literal
            if (ch == '\'') {
                pos = readStringLiteral(sql, pos, tokens);
                continue;
            }

            // Numeric literal (digit or decimal point followed by digit)
            if (Character.isDigit(ch)
                    || (ch == '.' && pos + 1 < len && Character.isDigit(sql.charAt(pos + 1)))) {
                pos = readNumericLiteral(sql, pos, tokens);
                continue;
            }

            // Identifier or keyword (starts with letter or underscore)
            if (Character.isLetter(ch) || ch == '_') {
                pos = readIdentifierOrKeyword(sql, pos, tokens);
                continue;
            }

            // Operators and punctuation
            switch (ch) {
                case '(' -> {
                    tokens.add(new Token(TokenType.LPAREN, "(", pos));
                    pos++;
                }
                case ')' -> {
                    tokens.add(new Token(TokenType.RPAREN, ")", pos));
                    pos++;
                }
                case ',' -> {
                    tokens.add(new Token(TokenType.COMMA, ",", pos));
                    pos++;
                }
                case '.' -> {
                    tokens.add(new Token(TokenType.DOT, ".", pos));
                    pos++;
                }
                case '*' -> {
                    tokens.add(new Token(TokenType.STAR, "*", pos));
                    pos++;
                }
                case '?' -> {
                    tokens.add(new Token(TokenType.PARAMETER, "?", pos));
                    pos++;
                }
                case '=' -> {
                    tokens.add(new Token(TokenType.EQ, "=", pos));
                    pos++;
                }
                case '!' -> {
                    if (pos + 1 < len && sql.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.NE, "!=", pos));
                        pos += 2;
                    } else {
                        throw new SqlParseException("Unexpected character '!' at position " + pos
                                + " — did you mean '!='?", pos);
                    }
                }
                case '<' -> {
                    if (pos + 1 < len && sql.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.LTE, "<=", pos));
                        pos += 2;
                    } else if (pos + 1 < len && sql.charAt(pos + 1) == '>') {
                        tokens.add(new Token(TokenType.NE, "<>", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", pos));
                        pos++;
                    }
                }
                case '>' -> {
                    if (pos + 1 < len && sql.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.GTE, ">=", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", pos));
                        pos++;
                    }
                }
                case '-' -> {
                    tokens.add(new Token(TokenType.MINUS, "-", pos));
                    pos++;
                }
                default -> throw new SqlParseException(
                        "Unrecognised character '" + ch + "' at position " + pos, pos);
            }
        }

        tokens.add(new Token(TokenType.EOF, "", len));
        return List.copyOf(tokens);
    }

    // @spec F07.R12,R13,R14 — string literal content, escaped '', unterminated throws at opening
    // quote
    private int readStringLiteral(String sql, int start, List<Token> tokens)
            throws SqlParseException {
        assert sql.charAt(start) == '\'' : "expected opening quote";

        final var sb = new StringBuilder();
        int pos = start + 1;
        final int len = sql.length();

        while (pos < len) {
            final char ch = sql.charAt(pos);
            if (ch == '\'') {
                // Check for escaped quote ''
                if (pos + 1 < len && sql.charAt(pos + 1) == '\'') {
                    sb.append('\'');
                    pos += 2;
                } else {
                    // End of string literal
                    tokens.add(new Token(TokenType.STRING_LITERAL, sb.toString(), start));
                    return pos + 1;
                }
            } else {
                sb.append(ch);
                pos++;
            }
        }

        throw new SqlParseException("Unterminated string literal starting at position " + start,
                start);
    }

    // @spec F07.R15,R101 — digits with optional single decimal point; reject trailing dot without
    // digits
    private int readNumericLiteral(String sql, int start, List<Token> tokens)
            throws SqlParseException {
        int pos = start;
        final int len = sql.length();
        boolean seenDot = false;

        while (pos < len) {
            final char ch = sql.charAt(pos);
            if (Character.isDigit(ch)) {
                pos++;
            } else if (ch == '.' && !seenDot) {
                seenDot = true;
                pos++;
            } else {
                break;
            }
        }

        if (seenDot && sql.charAt(pos - 1) == '.') {
            throw new SqlParseException(
                    "Malformed numeric literal '" + sql.substring(start, pos)
                            + "' — trailing dot with no fractional digits at position " + start,
                    start);
        }

        tokens.add(new Token(TokenType.NUMBER_LITERAL, sql.substring(start, pos), start));
        return pos;
    }

    // @spec F07.R10,R11,R16,R102 — letter/underscore start; digits allowed after; exact uppercase
    // keyword match
    private int readIdentifierOrKeyword(String sql, int start, List<Token> tokens) {
        int pos = start;
        final int len = sql.length();

        while (pos < len) {
            final char ch = sql.charAt(pos);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                pos++;
            } else {
                break;
            }
        }

        final String text = sql.substring(start, pos);
        final String upper = text.toUpperCase(Locale.ROOT);
        final TokenType keyword = KEYWORDS.get(upper);

        if (keyword != null) {
            tokens.add(new Token(keyword, text, start));
        } else {
            tokens.add(new Token(TokenType.IDENTIFIER, text, start));
        }

        return pos;
    }
}
