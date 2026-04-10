package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonlWriter}.
 */
class JsonlWriterTest {

    // --- Constructor validation ---

    @Test
    void constructorRejectsNullOutput() {
        assertThrows(NullPointerException.class, () -> new JsonlWriter(null));
    }

    // --- Write single object ---

    @Test
    void writeSingleObject() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonObject.of(Map.of("key", JsonPrimitive.ofString("value"))));
        }
        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.endsWith("\n"), "Output must end with newline");
        // Parse back to verify it's valid JSON
        JsonValue parsed = JsonParser.parse(result.trim());
        assertInstanceOf(JsonObject.class, parsed);
    }

    // --- Write multiple values ---

    @Test
    void writeMultipleValues() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonObject.of(Map.of("a", JsonPrimitive.ofNumber("1"))));
            writer.write(JsonArray
                    .of(List.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofNumber("2"))));
            writer.write(JsonPrimitive.ofString("hello"));
            writer.write(JsonPrimitive.ofNumber("42"));
            writer.write(JsonPrimitive.ofBoolean(true));
            writer.write(JsonNull.INSTANCE);
        }
        String result = out.toString(StandardCharsets.UTF_8);
        String[] lines = result.split("\n", -1);
        // Last element after split on trailing \n is empty string
        assertEquals(7, lines.length, "Expected 6 lines + trailing empty");
        assertEquals("", lines[6], "File should end with newline");
    }

    // --- Each write ends with \n ---

    @Test
    void eachWriteEndsWithNewline() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonPrimitive.ofNumber("1"));
            String afterFirst = out.toString(StandardCharsets.UTF_8);
            assertTrue(afterFirst.endsWith("\n"));

            writer.write(JsonPrimitive.ofNumber("2"));
            String afterSecond = out.toString(StandardCharsets.UTF_8);
            assertTrue(afterSecond.endsWith("\n"));
        }
    }

    // --- Null value throws NullPointerException ---

    @Test
    void writeRejectsNullValue() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            assertThrows(NullPointerException.class, () -> writer.write(null));
        }
    }

    // --- AutoCloseable flushes and closes ---

    @Test
    void closeFlushesAndClosesStream() throws Exception {
        AtomicBoolean flushed = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        OutputStream delegate = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                flushed.set(true);
                super.flush();
            }

            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        JsonlWriter writer = new JsonlWriter(delegate);
        writer.close();
        assertTrue(flushed.get(), "close() must flush the stream");
        assertTrue(closed.get(), "close() must close the stream");
    }

    // --- Compact JSON output (no extra whitespace) ---

    @Test
    void outputIsCompactJson() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonObject.of(Map.of("a", JsonPrimitive.ofNumber("1"))));
        }
        String result = out.toString(StandardCharsets.UTF_8).trim();
        // Compact: no spaces around colon or braces
        assertEquals("{\"a\":1}", result);
    }

    // --- Accepts any JsonValue subtype ---

    @Test
    void acceptsJsonNull() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonNull.INSTANCE);
        }
        assertEquals("null\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsJsonArray() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonArray.of(List.of(JsonPrimitive.ofNumber("1"))));
        }
        assertEquals("[1]\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsJsonPrimitiveString() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonPrimitive.ofString("hello"));
        }
        assertEquals("\"hello\"\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsJsonPrimitiveBoolean() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonPrimitive.ofBoolean(false));
        }
        assertEquals("false\n", out.toString(StandardCharsets.UTF_8));
    }

    // --- Round-trip: write then read back ---

    @Test
    void roundTripWriteThenRead() throws Exception {
        var out = new ByteArrayOutputStream();
        JsonObject obj1 = JsonObject.of(Map.of("name", JsonPrimitive.ofString("Alice")));
        JsonObject obj2 = JsonObject.of(Map.of("name", JsonPrimitive.ofString("Bob")));

        try (var writer = new JsonlWriter(out)) {
            writer.write(obj1);
            writer.write(obj2);
        }

        byte[] bytes = out.toByteArray();
        try (var reader = new JsonlReader(new ByteArrayInputStream(bytes))) {
            List<JsonValue> values = reader.stream().toList();
            assertEquals(2, values.size());
            assertEquals(obj1, values.get(0));
            assertEquals(obj2, values.get(1));
        }
    }

    // --- Write produces output immediately ---

    @Test
    void writeProducesOutputImmediately() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = new JsonlWriter(out)) {
            writer.write(JsonPrimitive.ofNumber("1"));
            // Output should be available immediately, not buffered
            assertTrue(out.size() > 0, "Output should be produced immediately after write()");
        }
    }
}
