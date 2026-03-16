package jlsm.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenTest {

    @Test
    void tokenRecordStoresTypeTextAndPosition() {
        var token = new Token(TokenType.SELECT, "SELECT", 0);

        assertEquals(TokenType.SELECT, token.type());
        assertEquals("SELECT", token.text());
        assertEquals(0, token.position());
    }

    @Test
    void tokenRejectsNullType() {
        assertThrows(NullPointerException.class, () -> new Token(null, "text", 0));
    }

    @Test
    void tokenRejectsNullText() {
        assertThrows(NullPointerException.class, () -> new Token(TokenType.SELECT, null, 0));
    }

    @Test
    void tokenRejectsNegativePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Token(TokenType.SELECT, "SELECT", -1));
    }

    @Test
    void sqlParseExceptionCarriesPosition() {
        var ex = new SqlParseException("bad token", 42);

        assertEquals("bad token", ex.getMessage());
        assertEquals(42, ex.position());
    }

    @Test
    void sqlParseExceptionAcceptsNegativeOneForUnknownPosition() {
        var ex = new SqlParseException("unknown", -1);
        assertEquals(-1, ex.position());
    }

    @Test
    void sqlParseExceptionWithCause() {
        var cause = new RuntimeException("root");
        var ex = new SqlParseException("wrapped", 10, cause);

        assertEquals("wrapped", ex.getMessage());
        assertEquals(10, ex.position());
        assertSame(cause, ex.getCause());
    }
}
