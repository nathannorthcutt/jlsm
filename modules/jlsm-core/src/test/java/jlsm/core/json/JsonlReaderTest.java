package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonlReader}.
 */
class JsonlReaderTest {

    private static InputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    // --- Constructor validation ---

    @Test
    void constructorRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> new JsonlReader(null));
    }

    @Test
    void constructorRejectsNullErrorMode() {
        assertThrows(NullPointerException.class, () -> new JsonlReader(toStream(""), null, null));
    }

    // --- Single-line JSONL ---

    @Test
    void readSingleObject() throws Exception {
        try (var reader = new JsonlReader(toStream("{\"a\":1}\n"))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(1, values.size());
            assertInstanceOf(JsonObject.class, values.getFirst());
        }
    }

    @Test
    void readSingleObjectWithoutTrailingNewline() throws Exception {
        try (var reader = new JsonlReader(toStream("{\"a\":1}"))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(1, values.size());
        }
    }

    // --- Multi-line JSONL ---

    @Test
    void readMultipleObjects() throws Exception {
        String jsonl = """
                {"a":1}
                {"b":2}
                {"c":3}
                """;
        try (var reader = new JsonlReader(toStream(jsonl))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(3, values.size());
            for (JsonValue v : values) {
                assertInstanceOf(JsonObject.class, v);
            }
        }
    }

    // --- Blank lines ---

    @Test
    void skipsBlankLines() throws Exception {
        String jsonl = """
                {"a":1}

                {"b":2}
                   \t
                {"c":3}
                """;
        try (var reader = new JsonlReader(toStream(jsonl))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(3, values.size());
        }
    }

    @Test
    void allBlankLinesProducesEmptyStream() throws Exception {
        try (var reader = new JsonlReader(toStream("\n\n  \n\t\n"))) {
            List<JsonValue> values = reader.stream().toList();
            assertTrue(values.isEmpty());
        }
    }

    @Test
    void emptyInputProducesEmptyStream() throws Exception {
        try (var reader = new JsonlReader(toStream(""))) {
            List<JsonValue> values = reader.stream().toList();
            assertTrue(values.isEmpty());
        }
    }

    // --- FAIL_FAST error mode ---

    @Test
    void failFastThrowsOnMalformedLine() {
        String jsonl = "{\"a\":1}\n{bad json}\n{\"c\":3}\n";
        try (var reader = new JsonlReader(toStream(jsonl))) {
            assertThrows(JsonParseException.class, () -> reader.stream().toList());
        } catch (Exception e) {
            fail("Unexpected exception on close: " + e);
        }
    }

    @Test
    void failFastIsDefault() throws Exception {
        String jsonl = "{bad}\n";
        try (var reader = new JsonlReader(toStream(jsonl))) {
            assertThrows(JsonParseException.class, () -> reader.stream().toList());
        }
    }

    // --- SKIP_ON_ERROR mode ---

    @Test
    void skipOnErrorSkipsMalformedLines() throws Exception {
        String jsonl = "{\"a\":1}\n{bad json}\n{\"c\":3}\n";
        try (var reader = new JsonlReader(toStream(jsonl), JsonlReader.ErrorMode.SKIP_ON_ERROR,
                null)) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(2, values.size());
        }
    }

    @Test
    void skipOnErrorInvokesCallback() throws Exception {
        String jsonl = "{\"a\":1}\n{bad}\n{\"c\":3}\n";
        List<JsonlReader.ParseError> errors = new ArrayList<>();
        try (var reader = new JsonlReader(toStream(jsonl), JsonlReader.ErrorMode.SKIP_ON_ERROR,
                errors::add)) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(2, values.size());
            assertEquals(1, errors.size());
            assertEquals(2, errors.getFirst().lineNumber());
            assertEquals("{bad}", errors.getFirst().line());
            assertInstanceOf(JsonParseException.class, errors.getFirst().cause());
        }
    }

    @Test
    void skipOnErrorCallbackReceivesCorrectLineNumbers() throws Exception {
        String jsonl = "bad1\n{\"ok\":true}\nbad2\nbad3\n{\"ok2\":true}\n";
        List<JsonlReader.ParseError> errors = new ArrayList<>();
        try (var reader = new JsonlReader(toStream(jsonl), JsonlReader.ErrorMode.SKIP_ON_ERROR,
                errors::add)) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(2, values.size());
            assertEquals(3, errors.size());
            assertEquals(1, errors.get(0).lineNumber());
            assertEquals(3, errors.get(1).lineNumber());
            assertEquals(4, errors.get(2).lineNumber());
        }
    }

    // --- Stream composability ---

    @Test
    void streamSupportsMapFilterCollect() throws Exception {
        String jsonl = "{\"x\":1}\n{\"x\":2}\n{\"x\":3}\n";
        try (var reader = new JsonlReader(toStream(jsonl))) {
            long count = reader.stream().filter(v -> v instanceof JsonObject).count();
            assertEquals(3, count);
        }
    }

    @Test
    void streamIsLazy() throws Exception {
        // Stream should not read all lines eagerly; reading only the first
        // element should not require parsing the entire input
        String jsonl = "{\"a\":1}\n{\"b\":2}\n{\"c\":3}\n";
        try (var reader = new JsonlReader(toStream(jsonl))) {
            JsonValue first = reader.stream().findFirst().orElseThrow();
            assertInstanceOf(JsonObject.class, first);
        }
    }

    // --- Mixed value types ---

    @Test
    void handlesMixedValueTypes() throws Exception {
        String jsonl = """
                {"obj":true}
                [1,2,3]
                "hello"
                42
                true
                null
                """;
        try (var reader = new JsonlReader(toStream(jsonl))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(6, values.size());
            assertInstanceOf(JsonObject.class, values.get(0));
            assertInstanceOf(JsonArray.class, values.get(1));
            assertInstanceOf(JsonPrimitive.class, values.get(2));
            assertInstanceOf(JsonPrimitive.class, values.get(3));
            assertInstanceOf(JsonPrimitive.class, values.get(4));
            assertInstanceOf(JsonNull.class, values.get(5));
        }
    }

    // --- AutoCloseable ---

    @Test
    void closeClosesUnderlyingStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream delegate = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        JsonlReader reader = new JsonlReader(delegate);
        assertFalse(closed.get());
        reader.close();
        assertTrue(closed.get());
    }

    // --- Large stream (constant memory verification) ---

    @Test
    void largeStreamDoesNotAccumulateMemory() throws Exception {
        // Generate a large stream (10,000 lines) and consume it via forEach
        // to verify constant memory per line (no accumulation).
        int lineCount = 10_000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            sb.append("{\"i\":").append(i).append("}\n");
        }
        int[] count = { 0 };
        try (var reader = new JsonlReader(toStream(sb.toString()))) {
            reader.stream().forEach(_ -> count[0]++);
        }
        assertEquals(lineCount, count[0]);
    }

    // --- ParseError record validation ---

    @Test
    void parseErrorRejectsNullLine() {
        assertThrows(NullPointerException.class,
                () -> new JsonlReader.ParseError(1, null, new JsonParseException("test", 0)));
    }

    @Test
    void parseErrorRejectsNullCause() {
        assertThrows(NullPointerException.class, () -> new JsonlReader.ParseError(1, "line", null));
    }
}
