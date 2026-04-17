package jlsm.core.json;

import jlsm.core.json.internal.PanamaStage1;
import jlsm.core.json.internal.ScalarStage1;
import jlsm.core.json.internal.VectorStage1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in JSON types.
 */
class ContractBoundariesAdversarialTest {

    // Finding: F-R1.cb.1.3
    // Bug: JsonObject.Builder is reusable after build() — no invalidation guard.
    // Javadoc says "The builder may not be reused after this call" but nothing enforces it.
    // Correct behavior: build() should invalidate the builder; subsequent put() or build() must
    // throw IllegalStateException.
    // Fix location: JsonObject.Builder.build() and put() — add a 'built' flag and check it.
    // Regression watch: Ensure build() still returns a correct JsonObject on first call.
    @Test
    void test_JsonObjectBuilder_contractBoundary_rejectPutAfterBuild() {
        JsonObject.Builder builder = JsonObject.builder();
        builder.put("a", JsonPrimitive.ofNumber("1"));

        JsonObject first = builder.build();
        assertEquals(1, first.size());
        assertEquals(JsonPrimitive.ofNumber("1"), first.get("a"));

        // Builder's Javadoc says it may not be reused after build().
        // A second put() must throw IllegalStateException.
        assertThrows(IllegalStateException.class,
                () -> builder.put("b", JsonPrimitive.ofString("hello")),
                "Builder must reject put() after build()");
    }

    // Finding: F-R1.cb.1.3
    // Bug: Same as above — second build() call also silently succeeds.
    // Correct behavior: build() called a second time must throw IllegalStateException.
    // Fix location: JsonObject.Builder.build() — check 'built' flag.
    // Regression watch: Ensure first build() still works.
    @Test
    void test_JsonObjectBuilder_contractBoundary_rejectSecondBuild() {
        JsonObject.Builder builder = JsonObject.builder();
        builder.put("x", JsonPrimitive.ofString("val"));

        JsonObject first = builder.build();
        assertNotNull(first);
        assertEquals(1, first.size());

        // A second build() must throw IllegalStateException.
        assertThrows(IllegalStateException.class, builder::build,
                "Builder must reject build() after already built");
    }

    // Finding: F-R1.cb.1.4
    // Bug: JsonPrimitive.ofNumber accepts arbitrary non-numeric text (e.g., "not-a-number",
    // "hello", empty string). Error is deferred to asInt()/asLong()/asDouble()/asBigDecimal().
    // Correct behavior: ofNumber should reject text that is not a valid JSON number at construction
    // time by throwing NumberFormatException.
    // Fix location: JsonPrimitive.ofNumber(String) — add BigDecimal validation before construction.
    // Regression watch: Ensure valid number text ("123", "1.5e10", "-0.0", "1E+3") still accepted.
    @ParameterizedTest
    @ValueSource(strings = { "not-a-number", "hello", "abc", "", "  ", "12.3.4", "NaN", "Infinity",
            "-Infinity", "0x1A" })
    void test_JsonPrimitive_contractBoundary_ofNumberRejectsNonNumericText(String invalidInput) {
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber(invalidInput),
                "ofNumber must reject non-numeric text at construction time: " + invalidInput);
    }

    // Finding: F-R1.cb.1.4 (companion — valid inputs still accepted)
    // Bug: Same as above — this test guards against over-zealous validation breaking valid numbers.
    // Correct behavior: Valid JSON number text must still be accepted by ofNumber.
    // Fix location: JsonPrimitive.ofNumber(String)
    // Regression watch: Must not reject valid number representations.
    @ParameterizedTest
    @ValueSource(strings = { "0", "123", "-456", "1.5", "1.5e10", "1.5E10", "1E+3", "1e-3", "-0.0",
            "9999999999999999999999999999" })
    void test_JsonPrimitive_contractBoundary_ofNumberAcceptsValidNumbers(String validInput) {
        JsonPrimitive p = assertDoesNotThrow(() -> JsonPrimitive.ofNumber(validInput),
                "ofNumber must accept valid number text: " + validInput);
        assertTrue(p.isNumber());
        assertEquals(validInput, p.asNumberText());
    }

    // Finding: F-R1.cb.2.1 (updated for F15 v3 iterative parser rewrite)
    //
    // Original intent: verify that advancePastStructural's assertion catches a Stage 1 index
    // that points to the wrong kind of structural character (e.g., '[' where '{' was expected).
    // Previously this was probed by calling parseObject() directly via reflection. After the
    // R27 rewrite (single iterative parseValue / stepObject / stepArray), parseObject no
    // longer exists, so we invoke advancePastStructural directly via reflection instead —
    // the assertion contract remains and must still fire on mismatched input.
    @Test
    void test_Materializer_contractBoundary_advancePastStructuralValidatesExpectedChar()
            throws Exception {
        byte[] input = "[null]".getBytes(StandardCharsets.UTF_8);
        int[] positions = { 0, 5 };
        int maxDepth = 256;

        Class<?> materializerClass = Class.forName("jlsm.core.json.JsonParser$Materializer");
        Constructor<?> ctor = materializerClass.getDeclaredConstructor(byte[].class, int[].class,
                int.class);
        ctor.setAccessible(true);
        Object materializer = ctor.newInstance(input, positions, maxDepth);

        // advancePastStructural is package-private and takes a char; invoking with '{' while
        // input[pos] == '[' must trip the internal assertion.
        Method advance = materializerClass.getDeclaredMethod("advancePastStructural", char.class);
        advance.setAccessible(true);

        var thrown = assertThrows(AssertionError.class, () -> {
            try {
                advance.invoke(materializer, '{');
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }, "advancePastStructural('{') must detect that input[0]='[' does not match expected '{'");
        assertTrue(thrown.getMessage().contains("expected"),
                "AssertionError message should mention the expected structural character");
    }

    // Finding: F-R1.cb.2.7
    // Bug: JsonParseException constructor does not null-check message parameter.
    // Javadoc says "must not be null" but passing null produces "null (at byte offset 0)"
    // instead of throwing NullPointerException.
    // Correct behavior: Constructor must throw NullPointerException when message is null.
    // Fix location: JsonParseException constructors — add Objects.requireNonNull(message).
    // Regression watch: Non-null messages must still work; both constructors must be checked.
    @Test
    void test_JsonParseException_contractBoundary_constructorRejectsNullMessage() {
        // Two-arg constructor: (String, long)
        assertThrows(NullPointerException.class, () -> new JsonParseException(null, 0),
                "JsonParseException(null, offset) must throw NullPointerException — "
                        + "Javadoc says message 'must not be null'");

        // Three-arg constructor: (String, long, Throwable)
        assertThrows(NullPointerException.class,
                () -> new JsonParseException(null, 0, new RuntimeException("cause")),
                "JsonParseException(null, offset, cause) must throw NullPointerException — "
                        + "Javadoc says message 'must not be null'");

        // Sanity: non-null message still works
        var ex = new JsonParseException("test error", 42);
        assertTrue(ex.getMessage().contains("test error"));
        assertEquals(42, ex.offset());
    }

    // Finding: F-R1.cb.2.4
    // Bug: JsonlReader.stream() creates a new BufferedReader per call — no guard against
    // multiple invocations. Two BufferedReaders wrapping the same InputStream buffer
    // independently, causing lines to be split between them (silent data corruption).
    // Correct behavior: stream() must throw IllegalStateException on the second call.
    // Fix location: JsonlReader.stream() — add a boolean flag that is set on first call.
    // Regression watch: First stream() call must still work normally.
    @Test
    void test_JsonlReader_contractBoundary_streamRejectsSecondInvocation() {
        String jsonl = """
                {"a":1}
                {"b":2}
                {"c":3}
                """;
        var input = new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8));
        var reader = new JsonlReader(input);

        // First call must succeed and produce a valid stream.
        var first = reader.stream();
        assertNotNull(first, "First stream() call must succeed");

        // Second call must throw IllegalStateException — the stream is single-use
        // because the underlying InputStream/BufferedReader is stateful.
        assertThrows(IllegalStateException.class, reader::stream,
                "stream() must reject second invocation — creating a second BufferedReader "
                        + "on the same InputStream causes silent data corruption");

        // Verify the first stream still works correctly.
        var values = first.toList();
        assertEquals(3, values.size(), "First stream must produce all three JSON values");
    }

    // Finding: F-R1.cb.2.5
    // Bug: JsonlReader.close() only closes the raw InputStream but not the BufferedReader
    // created inside stream(). The BufferedReader holds a char[] buffer that is not
    // released until GC, and the AutoCloseable contract implies all resources are closed.
    // Correct behavior: close() must also close the BufferedReader created by stream().
    // Fix location: JsonlReader — store BufferedReader as field in stream(), close it in close().
    // Regression watch: close() before stream() must still work (no BufferedReader to close).
    @Test
    void test_JsonlReader_contractBoundary_closeClosesBufferedReader() throws Exception {
        String jsonl = """
                {"a":1}
                {"b":2}
                """;
        var input = new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8));
        var reader = new JsonlReader(input);

        // Call stream() to create the internal BufferedReader.
        var stream = reader.stream();
        assertNotNull(stream);

        // Close the reader — this should close both the InputStream AND the BufferedReader.
        reader.close();

        // After the fix, the BufferedReader is stored as a field. Verify it exists and is closed.
        // A closed BufferedReader throws IOException on read() — we verify via reflection that
        // the field is non-null (was set by stream()) and that reading from it throws.
        Field bufferedField = JsonlReader.class.getDeclaredField("buffered");
        bufferedField.setAccessible(true);
        BufferedReader buffered = (BufferedReader) bufferedField.get(reader);

        assertNotNull(buffered, "BufferedReader field must be set after stream() was called");

        // A properly closed BufferedReader throws IOException on readLine().
        assertThrows(java.io.IOException.class, buffered::readLine,
                "BufferedReader must be closed after JsonlReader.close() — readLine() should throw IOException");
    }

    // Finding: F-R1.cb.2.9
    // Bug: Materializer uses bounded mutual recursion with maxDepth as the only guard, but
    // maxDepth accepts Integer.MAX_VALUE (only > 0 is checked). Deeply nested input with
    // a very large maxDepth causes StackOverflowError instead of a clean exception.
    // Correct behavior: maxDepth must be capped at a safe upper bound so that bounded mutual
    // recursion cannot exhaust the thread stack. An unreasonable maxDepth
    // must be rejected with IllegalArgumentException at parse() time.
    // Fix location: JsonParser.parse(String, int) — add an upper bound check on maxDepth.
    // Regression watch: Normal maxDepth values (1–4096) must still work.
    @Test
    void test_Materializer_contractBoundary_rejectsExcessiveMaxDepth() {
        // maxDepth = Integer.MAX_VALUE should be rejected — it would allow unbounded
        // mutual recursion depth, risking StackOverflowError.
        assertThrows(IllegalArgumentException.class,
                () -> JsonParser.parse("{}", Integer.MAX_VALUE),
                "maxDepth = Integer.MAX_VALUE must be rejected — bounded mutual recursion "
                        + "cannot safely support unlimited depth");
    }

    @Test
    void test_Materializer_contractBoundary_acceptsReasonableMaxDepth() {
        // Normal maxDepth values must still work.
        JsonValue result = JsonParser.parse("{\"a\":{\"b\":1}}", 256);
        assertNotNull(result);
    }

    // Finding: F-R1.cb.2.5 (companion — close before stream)
    // Bug: Same as above — ensures close() works when stream() was never called.
    // Correct behavior: close() must succeed without error when no BufferedReader was created.
    // Fix location: JsonlReader.close() — null-check the BufferedReader field before closing.
    // Regression watch: Must not throw NullPointerException.
    @Test
    void test_JsonlReader_contractBoundary_closeBeforeStreamDoesNotThrow() throws Exception {
        String jsonl = "{\"a\":1}\n";
        var input = new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8));
        var reader = new JsonlReader(input);

        // Close without ever calling stream() — must not throw.
        assertDoesNotThrow(() -> reader.close(),
                "close() before stream() must succeed — no BufferedReader to close");
    }

    // F-R1.cb.4.4 (superseded by F15 v3 verification, 2026-04-16)
    //
    // The prior audit had relaxed blank-key rejection to match RFC 8259, but F15.R15
    // explicitly requires non-blank keys and F15 v3 verification re-affirmed that choice —
    // blank-key rejection is now the authoritative behavior (a third stricter-than-RFC
    // parser property documented in R25). Tests enforcing acceptance have been moved to
    // JsonObjectTest as `ofRejectsEmptyKey`, `ofRejectsWhitespaceOnlyKey`,
    // `builderRejectsEmptyKey`, `builderRejectsWhitespaceOnlyKey`.

    // Finding: F-R1.cb.3.1
    // Bug: PanamaStage1 cross-block backslash runs not carried — escaped quotes misidentified.
    // computeEscapedQuotes() only inspects backslashBits within the current 64-byte block.
    // A backslash at position 63 (end of block 0) followed by a quote at position 64
    // (start of block 1 or scalar tail) is not recognized as an escaped quote.
    // Correct behavior: PanamaStage1.scan() must produce the same result as ScalarStage1.scan()
    // for all inputs. Specifically, \" straddling a 64-byte block boundary
    // must be treated as an escaped quote, not a string delimiter.
    // Fix location: PanamaStage1 — carry backslash state across block boundaries and into scalar
    // tail.
    // Regression watch: Ensure intra-block backslash escaping still works correctly.
    @Test
    void test_PanamaStage1_contractBoundary_crossBlockBackslashEscapedQuote() {
        // Build input where a backslash at end of block 0 (position 63) escapes
        // a quote at start of block 1 / scalar tail (position 64), INSIDE a string.
        //
        // Layout (70 bytes):
        // pos 0: {
        // pos 1: " <-- opens string
        // pos 2-62: 'a' * 61 (filler inside string)
        // pos 63: \ <-- last byte of block 0, escape char inside string
        // pos 64: " <-- first byte of block 1/tail, escaped quote (NOT a delimiter)
        // pos 65: a <-- still inside the string
        // pos 66: " <-- closes the string
        // pos 67: :
        // pos 68: 1
        // pos 69: }
        //
        // ScalarStage1 correctly sees \ at 63 escaping " at 64; string continues.
        // PanamaStage1 bug: block 0 has the backslash at bit 63 but block 1/tail
        // doesn't know — treats " at 64 as unescaped, closing the string prematurely.
        byte[] input = new byte[70];
        input[0] = '{';
        input[1] = '"';
        Arrays.fill(input, 2, 63, (byte) 'a');
        input[63] = '\\';
        input[64] = '"';
        input[65] = 'a';
        input[66] = '"';
        input[67] = ':';
        input[68] = '1';
        input[69] = '}';

        int[] expected = ScalarStage1.scan(input);
        int[] actual = PanamaStage1.scan(input);

        assertArrayEquals(expected, actual,
                "PanamaStage1 must match ScalarStage1 for backslash-quote straddling "
                        + "a 64-byte block boundary. Expected: " + Arrays.toString(expected)
                        + " but got: " + Arrays.toString(actual));
    }

    // Finding: F-R1.cb.3.3
    // Bug: VectorStage1 backslash at last lane position leaks escaped byte to next chunk.
    // When j == LANE_COUNT-1 and input[i+j] == '\\', the j++ skip sets j = LANE_COUNT,
    // exiting the inner loop. The escaped byte at position i+LANE_COUNT is processed
    // as a normal byte in the next chunk instead of being skipped.
    // Correct behavior: VectorStage1.scan() must produce the same result as ScalarStage1.scan().
    // A backslash at the last lane of a SIMD chunk must cause the first byte
    // of the next chunk to be treated as escaped.
    // Fix location: VectorStage1 inner chunk loop (lines 77-98) — carry "skip next" state across
    // chunks.
    // Regression watch: Intra-chunk backslash escaping must still work correctly.
    @Test
    void test_VectorStage1_contractBoundary_backslashAtLastLaneLeaksEscapedByte() {
        // Determine LANE_COUNT dynamically via reflection so this test works
        // regardless of the platform's preferred SIMD species width.
        int laneCount;
        try {
            Field lcField = VectorStage1.class.getDeclaredField("LANE_COUNT");
            lcField.setAccessible(true);
            laneCount = (int) lcField.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot read LANE_COUNT from VectorStage1", e);
        }

        // Build input where inside a string, a backslash sits at position (laneCount - 1)
        // — the last lane of the first SIMD chunk — and the escaped byte (a quote) is at
        // position laneCount (first lane of the second chunk or scalar tail).
        //
        // Layout:
        // pos 0: " <-- opens string
        // pos 1 .. lc-2: 'a' filler (inside string)
        // pos lc-1: \ <-- last lane of chunk 0
        // pos lc: " <-- escaped quote (NOT a delimiter)
        // pos lc+1: a <-- still inside string
        // pos lc+2: " <-- closes the string
        // pos lc+3: ,
        // pos lc+4: 1
        //
        // ScalarStage1: sees \ at lc-1, skips " at lc, string continues.
        // VectorStage1 bug: inner loop exits at j=lc, next chunk processes " at lc as unescaped.
        int totalLen = laneCount + 5;
        byte[] input = new byte[totalLen];
        input[0] = '"';
        Arrays.fill(input, 1, laneCount - 1, (byte) 'a');
        input[laneCount - 1] = '\\';
        input[laneCount] = '"'; // escaped quote — must NOT be treated as delimiter
        input[laneCount + 1] = 'a'; // still inside string
        input[laneCount + 2] = '"'; // real closing quote
        input[laneCount + 3] = ',';
        input[laneCount + 4] = '1';

        int[] expected = ScalarStage1.scan(input);
        int[] actual = VectorStage1.scan(input);

        assertArrayEquals(expected, actual,
                "VectorStage1 must match ScalarStage1 when backslash is at the last lane "
                        + "(position " + (laneCount - 1) + ") of a SIMD chunk. " + "Expected: "
                        + Arrays.toString(expected) + " but got: " + Arrays.toString(actual));
    }

    // Finding: F-R1.cb.4.3
    // Bug: JsonWriter.writeJsonString does not escape lone surrogate characters (U+D800-U+DFFF).
    // Chars in this range are >= 0x20 so they fall through to sb.append(c), emitting raw
    // surrogates into the JSON string. Lone surrogates are not valid Unicode scalar values
    // and produce invalid UTF-8 — strict JSON parsers will reject the output.
    // Correct behavior: Lone surrogates must be escaped as \\uXXXX sequences.
    // Fix location: JsonWriter.writeJsonString (lines 199-205) — add surrogate range check.
    // Regression watch: Properly paired surrogates (valid supplementary characters) must still
    // be emitted as raw chars, not escaped.
    @Test
    void test_JsonWriter_contractBoundary_loneSurrogatesEscaped() {
        // High surrogate without a following low surrogate
        String highSurrogate = "before\uD800after";
        String result = JsonWriter.write(JsonPrimitive.ofString(highSurrogate));
        // The output must contain \ud800 (escaped), not the raw surrogate char
        assertTrue(result.contains("\\ud800"),
                "Lone high surrogate U+D800 must be escaped as \\ud800 in JSON output, "
                        + "but got: " + result);
        assertFalse(result.contains("\uD800"), "Raw lone surrogate must not appear in output");

        // Low surrogate without a preceding high surrogate
        String lowSurrogate = "before\uDC00after";
        String result2 = JsonWriter.write(JsonPrimitive.ofString(lowSurrogate));
        assertTrue(result2.contains("\\udc00"),
                "Lone low surrogate U+DC00 must be escaped as \\udc00 in JSON output, "
                        + "but got: " + result2);
    }

    // Finding: F-R1.cb.4.6
    // Bug: JsonWriter.appendIndent integer overflow on level * spacesPerLevel multiplication.
    // With level=2 and spacesPerLevel=1_073_741_824 (2^30), the product is 2^31 which
    // overflows int to Integer.MIN_VALUE. The for-loop condition (i < total) with negative
    // total never executes — zero spaces appended instead of the correct count.
    // Correct behavior: appendIndent must detect overflow and throw ArithmeticException (or
    // use Math.multiplyExact) rather than silently producing wrong output.
    // Fix location: JsonWriter.appendIndent (line 213) — use Math.multiplyExact for level *
    // spacesPerLevel.
    // Regression watch: Normal indent values (small depth, small indent) must still work.
    @Test
    void test_JsonWriter_contractBoundary_appendIndentOverflowDetected() throws Exception {
        // Access the private appendIndent method via reflection to test the arithmetic directly.
        // This overflow is not reachable through the public API (OOM occurs first at shallower
        // depths), but the method itself contains a silent integer overflow that should be guarded.
        var method = JsonWriter.class.getDeclaredMethod("appendIndent", StringBuilder.class,
                int.class, int.class);
        method.setAccessible(true);

        StringBuilder sb = new StringBuilder();
        int level = 2;
        int spacesPerLevel = 1_073_741_824; // 2^30 — product with level=2 is 2^31, overflows int

        // Without fix: level * spacesPerLevel = 2^31 = Integer.MIN_VALUE (negative), loop
        // never executes, sb remains empty. This is silently wrong — no exception thrown.
        // With fix: Math.multiplyExact throws ArithmeticException on overflow.
        try {
            method.invoke(null, sb, level, spacesPerLevel);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof ArithmeticException) {
                // Fix is in place — overflow detected and reported.
                return;
            }
            throw e;
        }

        // If we reach here, no exception was thrown. The bug manifests as zero spaces appended
        // when overflow produces a negative total.
        fail("appendIndent(sb, " + level + ", " + spacesPerLevel
                + ") should throw ArithmeticException "
                + "due to integer overflow (2 * 2^30 = 2^31 overflows int), "
                + "but silently produced " + sb.length() + " spaces instead of 2^31");
    }

    // Finding: F-R1.cb.4.5
    // Bug: JsonlWriter.close() is not idempotent — double close propagates IOException
    // because output.flush() at line 59 throws when the stream is already closed.
    // Correct behavior: close() must be idempotent per the AutoCloseable contract —
    // a second close() call must be a silent no-op.
    // Fix location: JsonlWriter.close() — add a volatile boolean 'closed' guard.
    // Regression watch: First close must still flush and close the underlying stream.
    @Test
    void test_JsonlWriter_contractBoundary_doubleCloseIsIdempotent() throws IOException {
        // Use a real ByteArrayOutputStream wrapped in an OutputStream that throws on
        // flush/close after the first close — simulating typical stream behavior.
        var baos = new ByteArrayOutputStream();
        var throwingStream = new OutputStream() {
            private boolean closed = false;

            @Override
            public void write(int b) throws IOException {
                if (closed)
                    throw new IOException("stream closed");
                baos.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (closed)
                    throw new IOException("stream closed");
                baos.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                if (closed)
                    throw new IOException("stream closed");
                baos.flush();
            }

            @Override
            public void close() throws IOException {
                closed = true;
            }
        };

        var writer = new JsonlWriter(throwingStream);
        writer.write(JsonPrimitive.ofNumber("42"));

        // First close must succeed — flushes and closes the stream.
        assertDoesNotThrow(() -> writer.close(), "First close() must succeed");

        // Second close must be a no-op — must NOT throw IOException.
        assertDoesNotThrow(() -> writer.close(),
                "Second close() must be idempotent — must not throw IOException");

        // Verify the data was actually written before close.
        String output = baos.toString(StandardCharsets.UTF_8);
        assertEquals("42\n", output, "Data must have been written before close");
    }

}
