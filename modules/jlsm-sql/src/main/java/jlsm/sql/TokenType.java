package jlsm.sql;

/**
 * Token types produced by {@link SqlLexer}.
 *
 * <p>
 * Covers the supported SQL subset: SELECT, FROM, WHERE, ORDER BY, LIMIT, OFFSET, boolean
 * connectives, comparison operators, literals, identifiers, bind parameters, and built-in functions
 * (MATCH, VECTOR_DISTANCE).
 */
public enum TokenType {

    // Keywords
    SELECT, FROM, WHERE, AND, OR, NOT, ORDER, BY, ASC, DESC, LIMIT, OFFSET, BETWEEN, IS, NULL, TRUE, FALSE, AS, LIKE, IN,

    // Built-in functions
    MATCH, VECTOR_DISTANCE,

    // Identifiers and literals
    IDENTIFIER, STRING_LITERAL, NUMBER_LITERAL, PARAMETER, // positional bind parameter '?'

    // Punctuation
    COMMA, DOT, STAR, LPAREN, RPAREN,

    // Comparison operators
    EQ, // =
    NE, // != or <>
    LT, // <
    LTE, // <=
    GT, // >
    GTE, // >=

    // Arithmetic (unary minus for negative literals)
    MINUS, // -

    // End of input
    EOF
}
