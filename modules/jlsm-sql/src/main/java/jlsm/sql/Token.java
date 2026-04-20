package jlsm.sql;

import java.util.Objects;

/**
 * A single token produced by {@link SqlLexer}.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>{@code type} — the classification of this token (keyword, operator, literal, etc.)</li>
 * <li>{@code text} — the original source text that produced this token</li>
 * <li>{@code position} — zero-based character offset in the input string</li>
 * </ul>
 *
 * @param type the token type, never null
 * @param text the source text of this token, never null
 * @param position zero-based character offset in the input SQL string
 */
// @spec F07.R23,R24,R25,R26 — zero-based position; reject null type/text and negative position
public record Token(TokenType type, String text, int position) {

    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0, was: " + position);
        }
    }
}
