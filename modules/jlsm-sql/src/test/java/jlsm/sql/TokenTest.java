package jlsm.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenTest {

    // @spec F07.R23
    @Test
    void tokenRecordStoresTypeTextAndPosition() {
        var token = new Token(TokenType.SELECT, "SELECT", 0);

        assertEquals(TokenType.SELECT, token.type());
        assertEquals("SELECT", token.text());
        assertEquals(0, token.position());
    }

    // @spec F07.R24
    @Test
    void tokenRejectsNullType() {
        assertThrows(NullPointerException.class, () -> new Token(null, "text", 0));
    }

    // @spec F07.R25
    @Test
    void tokenRejectsNullText() {
        assertThrows(NullPointerException.class, () -> new Token(TokenType.SELECT, null, 0));
    }

    // @spec F07.R26
    @Test
    void tokenRejectsNegativePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Token(TokenType.SELECT, "SELECT", -1));
    }

    // @spec F07.R85,R86
    @Test
    void sqlParseExceptionCarriesPosition() {
        var ex = new SqlParseException("bad token", 42);

        assertEquals("bad token", ex.getMessage());
        assertEquals(42, ex.position());
    }

    // @spec F07.R86
    @Test
    void sqlParseExceptionAcceptsNegativeOneForUnknownPosition() {
        var ex = new SqlParseException("unknown", -1);
        assertEquals(-1, ex.position());
    }

    // @spec F07.R87
    @Test
    void sqlParseExceptionWithCause() {
        var cause = new RuntimeException("root");
        var ex = new SqlParseException("wrapped", 10, cause);

        assertEquals("wrapped", ex.getMessage());
        assertEquals(10, ex.position());
        assertSame(cause, ex.getCause());
    }
}
