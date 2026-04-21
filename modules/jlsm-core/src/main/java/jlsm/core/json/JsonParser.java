package jlsm.core.json;

import jlsm.core.json.internal.StructuralIndexer;
import jlsm.core.json.internal.StructuralIndexer.StructuralIndex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Two-stage on-demand JSON parser.
 *
 * <p>
 * Stage 1: structural indexing via {@link StructuralIndexer}. Stage 2: iterative materialization
 * using an explicit stack (no recursion).
 *
 * <p>
 * Stricter than RFC 8259: rejects duplicate keys, trailing content, and bounds nesting depth.
 *
 * @spec serialization.simd-jsonl.R16 — two-stage architecture: structural index then
 *       materialization
 * @spec serialization.simd-jsonl.R17 — parse returns fully materialized tree, no input references
 * @spec serialization.simd-jsonl.R25 — handles all RFC 8259 types with stricter duplicate/trailing
 *       rules
 * @spec serialization.simd-jsonl.R27 — configurable max depth (default 256), iterative not
 *       recursive
 */
public final class JsonParser {

    private static final int DEFAULT_MAX_DEPTH = 256;
    /**
     * Upper bound on configurable maxDepth. Bounded mutual recursion uses ~3 stack frames per
     * nesting level (parseValue → parseObject/parseArray → parseValue); 4096 levels ≈ 12k frames,
     * well within default JVM stack size (~512–1024 frames at ~1 KiB each on a 512 KiB stack).
     * Allowing arbitrary values (e.g., Integer.MAX_VALUE) risks StackOverflowError.
     */
    private static final int MAX_DEPTH_LIMIT = 4096;

    /**
     * Parses a JSON string into a {@link JsonValue} tree with default max depth (256).
     *
     * @param json the JSON string; must not be null
     * @return the parsed JsonValue
     * @throws NullPointerException if json is null
     * @throws JsonParseException if the input is malformed
     */
    public static JsonValue parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        return parse(json, DEFAULT_MAX_DEPTH);
    }

    /**
     * Parses a JSON string into a {@link JsonValue} tree with configurable max depth.
     *
     * @param json the JSON string; must not be null
     * @param maxDepth the maximum nesting depth; must be positive
     * @return the parsed JsonValue
     * @throws NullPointerException if json is null
     * @throws IllegalArgumentException if maxDepth is not positive
     * @throws JsonParseException if the input is malformed
     * @spec serialization.simd-jsonl.R28 — rejects trailing content after complete value
     * @spec serialization.simd-jsonl.R29 — detects truncated input
     * @spec serialization.simd-jsonl.R52 — depth bounds [1, 4096]
     * @spec serialization.simd-jsonl.R24 — throws JsonParseException with byte offset
     * @spec serialization.simd-jsonl.R26 — handles supplementary Unicode via surrogate pairs
     */
    public static JsonValue parse(String json, int maxDepth) {
        Objects.requireNonNull(json, "json must not be null");
        if (maxDepth <= 0 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException(
                    "maxDepth must be between 1 and " + MAX_DEPTH_LIMIT + ", got " + maxDepth);
        }

        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        if (input.length == 0) {
            throw new JsonParseException("Empty input", 0);
        }

        // Stage 1: structural indexing
        StructuralIndex index = StructuralIndexer.index(input);

        // Stage 2: iterative materialization
        var parser = new Materializer(input, index.positions(), maxDepth);
        JsonValue result = parser.parseValue();

        // Check for trailing content
        parser.skipWhitespace();
        if (parser.pos < input.length) {
            throw new JsonParseException("Trailing content after root value", parser.pos);
        }

        return result;
    }

    private JsonParser() {
        // Static utility class
    }

    /**
     * Iterative stage 2 materializer. Walks the input bytes guided by the structural index,
     * building the JsonValue tree with an explicit stack.
     */
    private static final class Materializer {

        private final byte[] input;
        private final int[] structPositions;
        private final int maxDepth;
        /** Current byte position in input. */
        private int pos;
        /** Current index into structPositions. */
        private int structIdx;

        Materializer(byte[] input, int[] structPositions, int maxDepth) {
            this.input = input;
            this.structPositions = structPositions;
            this.maxDepth = maxDepth;
            this.pos = 0;
            this.structIdx = 0;
        }

        /**
         * Parse a single JSON value starting from the current position.
         *
         * <p>
         * Uses an explicit {@link Deque} as a frame stack rather than recursion: each container
         * open (<code>{</code> or <code>[</code>) pushes a frame; each container close pops the
         * frame and emits the constructed {@link JsonObject} or {@link JsonArray} as the "ready"
         * value, which is then delivered up the stack. Primitives bypass the stack entirely. The
         * depth check is against {@code stack.size()} — one frame per nesting level — so the JVM
         * call stack depth is O(1) regardless of input nesting depth.
         *
         * @spec serialization.simd-jsonl.R27 — no recursion for depth traversal; iterative state
         *       machine
         */
        JsonValue parseValue() {
            Deque<Frame> stack = new ArrayDeque<>();
            JsonValue ready = null;

            while (true) {
                // Deliver any completed value to the top of the stack, or return if root.
                if (ready != null) {
                    Frame top = stack.peek();
                    if (top == null) {
                        return ready;
                    }
                    if (top instanceof ObjectFrame of) {
                        of.members.put(of.pendingKey, ready);
                        of.pendingKey = null;
                        of.state = ObjectState.COMMA_OR_END;
                    } else {
                        ArrayFrame af = (ArrayFrame) top;
                        af.elements.add(ready);
                        af.state = ArrayState.COMMA_OR_END;
                    }
                    ready = null;
                    continue;
                }

                skipWhitespace();
                Frame top = stack.peek();

                if (top == null) {
                    ready = readScalarOrOpen(stack);
                    continue;
                }

                if (top instanceof ObjectFrame of) {
                    ready = stepObject(of, stack);
                } else {
                    ArrayFrame af = (ArrayFrame) top;
                    ready = stepArray(af, stack);
                }
            }
        }

        /**
         * Advance one step inside an object frame. Returns a completed {@link JsonValue} when the
         * object is closed or a nested scalar is ready; returns {@code null} when a nested
         * container was pushed or the step advanced only the state/position.
         */
        private JsonValue stepObject(ObjectFrame of, Deque<Frame> stack) {
            switch (of.state) {
                case KEY_OR_END -> {
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated object", pos);
                    }
                    if (input[pos] == '}') {
                        advancePastStructural('}');
                        stack.pop();
                        return of.members.isEmpty() ? JsonObject.empty()
                                : JsonObject.of(of.members);
                    }
                    readObjectKey(of);
                    return null;
                }
                case KEY -> {
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated object", pos);
                    }
                    readObjectKey(of);
                    return null;
                }
                case COLON -> {
                    if (pos >= input.length || input[pos] != ':') {
                        throw new JsonParseException("Expected ':' after object key", pos);
                    }
                    advancePastStructural(':');
                    of.state = ObjectState.VALUE;
                    return null;
                }
                case VALUE -> {
                    return readScalarOrOpen(stack);
                }
                case COMMA_OR_END -> {
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated object", pos);
                    }
                    if (input[pos] == '}') {
                        advancePastStructural('}');
                        stack.pop();
                        return of.members.isEmpty() ? JsonObject.empty()
                                : JsonObject.of(of.members);
                    }
                    if (input[pos] == ',') {
                        advancePastStructural(',');
                        skipWhitespace();
                        if (pos < input.length && input[pos] == '}') {
                            throw new JsonParseException("Trailing comma in object", pos);
                        }
                        of.state = ObjectState.KEY;
                        return null;
                    }
                    throw new JsonParseException("Expected ',' or '}' in object", pos);
                }
            }
            throw new AssertionError("unreachable");
        }

        /**
         * Advance one step inside an array frame. Mirrors {@link #stepObject} — see that method for
         * return-value semantics.
         */
        private JsonValue stepArray(ArrayFrame af, Deque<Frame> stack) {
            switch (af.state) {
                case VALUE_OR_END -> {
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated array", pos);
                    }
                    if (input[pos] == ']') {
                        advancePastStructural(']');
                        stack.pop();
                        return af.elements.isEmpty() ? JsonArray.empty()
                                : JsonArray.of(af.elements);
                    }
                    return readScalarOrOpen(stack);
                }
                case VALUE -> {
                    return readScalarOrOpen(stack);
                }
                case COMMA_OR_END -> {
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated array", pos);
                    }
                    if (input[pos] == ']') {
                        advancePastStructural(']');
                        stack.pop();
                        return af.elements.isEmpty() ? JsonArray.empty()
                                : JsonArray.of(af.elements);
                    }
                    if (input[pos] == ',') {
                        advancePastStructural(',');
                        skipWhitespace();
                        if (pos < input.length && input[pos] == ']') {
                            throw new JsonParseException("Trailing comma in array", pos);
                        }
                        af.state = ArrayState.VALUE;
                        return null;
                    }
                    throw new JsonParseException("Expected ',' or ']' in array", pos);
                }
            }
            throw new AssertionError("unreachable");
        }

        /**
         * Parses a JSON member key, rejects duplicates and blank keys (F15.R15, R11), and advances
         * the frame state to expect ':' next.
         */
        private void readObjectKey(ObjectFrame of) {
            if (input[pos] != '"') {
                throw new JsonParseException("Expected string key in object", pos);
            }
            String key = parseStringValue();
            if (key.isBlank()) {
                // @spec serialization.simd-jsonl.R15 — blank keys are rejected (stricter than RFC
                // 8259)
                throw new JsonParseException("Object key must not be blank", pos);
            }
            if (of.members.containsKey(key)) {
                throw new JsonParseException("Duplicate key: " + key, pos);
            }
            of.pendingKey = key;
            of.state = ObjectState.COLON;
        }

        /**
         * Reads the next scalar value, OR — if the next byte opens a container — pushes a new frame
         * onto {@code stack} and returns {@code null}. Depth check fires before the push so
         * {@code stack.size()} never exceeds {@link #maxDepth}.
         */
        private JsonValue readScalarOrOpen(Deque<Frame> stack) {
            if (pos >= input.length) {
                throw new JsonParseException("Unexpected end of input", pos);
            }
            byte b = input[pos];
            return switch (b) {
                case '{' -> {
                    if (stack.size() + 1 > maxDepth) {
                        throw new JsonParseException(
                                "Maximum nesting depth " + maxDepth + " exceeded", pos);
                    }
                    advancePastStructural('{');
                    stack.push(new ObjectFrame());
                    yield null;
                }
                case '[' -> {
                    if (stack.size() + 1 > maxDepth) {
                        throw new JsonParseException(
                                "Maximum nesting depth " + maxDepth + " exceeded", pos);
                    }
                    advancePastStructural('[');
                    stack.push(new ArrayFrame());
                    yield null;
                }
                case '"' -> parseString();
                case 't' -> parseLiteral("true", JsonPrimitive.ofBoolean(true));
                case 'f' -> parseLiteral("false", JsonPrimitive.ofBoolean(false));
                case 'n' -> parseLiteral("null", JsonNull.INSTANCE);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber();
                default ->
                    throw new JsonParseException("Unexpected character '" + (char) b + "'", pos);
            };
        }

        // ---- Frame types for the explicit parse stack ----

        private sealed interface Frame permits ObjectFrame, ArrayFrame {
        }

        private enum ObjectState {
            /** After '{' — may close immediately (empty object) or parse first key. */
            KEY_OR_END,
            /** After ',' — key is required, close not allowed. */
            KEY,
            /** After key — ':' is required. */
            COLON,
            /** After ':' — value is required (no end). */
            VALUE,
            /** After value — ',' or '}'. */
            COMMA_OR_END
        }

        private enum ArrayState {
            /** After '[' — may close immediately (empty array) or parse first element. */
            VALUE_OR_END,
            /** After ',' — value is required, close not allowed. */
            VALUE,
            /** After element — ',' or ']'. */
            COMMA_OR_END
        }

        private static final class ObjectFrame implements Frame {
            final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
            String pendingKey;
            ObjectState state = ObjectState.KEY_OR_END;
        }

        private static final class ArrayFrame implements Frame {
            final ArrayList<JsonValue> elements = new ArrayList<>();
            ArrayState state = ArrayState.VALUE_OR_END;
        }

        private JsonPrimitive parseString() {
            return JsonPrimitive.ofString(parseStringValue());
        }

        /**
         * Parses a JSON string value, handling all escape sequences including \\uXXXX and surrogate
         * pairs.
         */
        private String parseStringValue() {
            if (pos >= input.length || input[pos] != '"') {
                throw new JsonParseException("Expected '\"'", pos);
            }
            // Skip structural quote position in index
            advancePastStructural('"');

            var sb = new StringBuilder();
            while (pos < input.length) {
                byte b = input[pos];
                if (b == '"') {
                    // End of string — advance past closing quote
                    advancePastStructural('"');
                    return sb.toString();
                }
                if (b == '\\') {
                    pos++;
                    if (pos >= input.length) {
                        throw new JsonParseException("Unterminated string escape", pos);
                    }
                    byte escaped = input[pos];
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            pos++;
                            int codePoint = parseHex4();
                            if (Character.isHighSurrogate((char) codePoint)) {
                                // Must be followed by \\uXXXX low surrogate
                                if (pos + 1 >= input.length || input[pos] != '\\'
                                        || input[pos + 1] != 'u') {
                                    throw new JsonParseException(
                                            "High surrogate not followed by low surrogate", pos);
                                }
                                pos += 2; // skip backslash-u prefix
                                int low = parseHex4();
                                if (!Character.isLowSurrogate((char) low)) {
                                    throw new JsonParseException(
                                            "High surrogate followed by non-low-surrogate \\u"
                                                    + Integer.toHexString(low),
                                            pos);
                                }
                                int supplementary = Character.toCodePoint((char) codePoint,
                                        (char) low);
                                sb.appendCodePoint(supplementary);
                            } else if (Character.isLowSurrogate((char) codePoint)) {
                                throw new JsonParseException("Lone low surrogate", pos);
                            } else {
                                sb.append((char) codePoint);
                            }
                            continue; // pos already advanced by parseHex4
                        }
                        default -> throw new JsonParseException(
                                "Invalid escape sequence '\\" + (char) escaped + "'", pos - 1);
                    }
                    pos++;
                } else if (b < 0x20) {
                    throw new JsonParseException("Unescaped control character in string", pos);
                } else {
                    // Regular UTF-8 byte — decode properly
                    // For simplicity, since we got the bytes from String.getBytes(UTF_8),
                    // we can decode multi-byte sequences
                    if ((b & 0x80) == 0) {
                        // Single byte ASCII
                        sb.append((char) b);
                        pos++;
                    } else if ((b & 0xE0) == 0xC0) {
                        // 2-byte sequence
                        if (pos + 1 >= input.length) {
                            throw new JsonParseException("Truncated UTF-8 sequence", pos);
                        }
                        int cp = ((b & 0x1F) << 6) | (input[pos + 1] & 0x3F);
                        sb.appendCodePoint(cp);
                        pos += 2;
                    } else if ((b & 0xF0) == 0xE0) {
                        // 3-byte sequence
                        if (pos + 2 >= input.length) {
                            throw new JsonParseException("Truncated UTF-8 sequence", pos);
                        }
                        int cp = ((b & 0x0F) << 12) | ((input[pos + 1] & 0x3F) << 6)
                                | (input[pos + 2] & 0x3F);
                        sb.appendCodePoint(cp);
                        pos += 3;
                    } else if ((b & 0xF8) == 0xF0) {
                        // 4-byte sequence
                        if (pos + 3 >= input.length) {
                            throw new JsonParseException("Truncated UTF-8 sequence", pos);
                        }
                        int cp = ((b & 0x07) << 18) | ((input[pos + 1] & 0x3F) << 12)
                                | ((input[pos + 2] & 0x3F) << 6) | (input[pos + 3] & 0x3F);
                        sb.appendCodePoint(cp);
                        pos += 4;
                    } else {
                        throw new JsonParseException("Invalid UTF-8 byte", pos);
                    }
                }
            }
            throw new JsonParseException("Unterminated string", pos);
        }

        /**
         * Parses exactly 4 hex digits and returns the code unit value. Advances pos by 4.
         */
        private int parseHex4() {
            if (pos + 4 > input.length) {
                throw new JsonParseException("Truncated \\u escape", pos);
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                byte b = input[pos + i];
                int digit = hexDigit(b);
                if (digit < 0) {
                    throw new JsonParseException("Invalid hex digit in \\u escape", pos + i);
                }
                value = (value << 4) | digit;
            }
            pos += 4;
            return value;
        }

        private static int hexDigit(byte b) {
            if (b >= '0' && b <= '9')
                return b - '0';
            if (b >= 'a' && b <= 'f')
                return 10 + (b - 'a');
            if (b >= 'A' && b <= 'F')
                return 10 + (b - 'A');
            return -1;
        }

        private JsonValue parseLiteral(String expected, JsonValue value) {
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            if (pos + expectedBytes.length > input.length) {
                throw new JsonParseException("Truncated literal '" + expected + "'", pos);
            }
            for (int i = 0; i < expectedBytes.length; i++) {
                if (input[pos + i] != expectedBytes[i]) {
                    throw new JsonParseException("Invalid literal at position " + pos, pos);
                }
            }
            pos += expectedBytes.length;
            return value;
        }

        private JsonPrimitive parseNumber() {
            int start = pos;

            // Optional minus
            if (pos < input.length && input[pos] == '-') {
                pos++;
            }

            if (pos >= input.length) {
                throw new JsonParseException("Truncated number", start);
            }

            // Integer part
            if (input[pos] == '0') {
                pos++;
                // After a leading zero, the next char must not be a digit (no leading zeros)
                if (pos < input.length && input[pos] >= '0' && input[pos] <= '9') {
                    throw new JsonParseException("Leading zeros not allowed", start);
                }
            } else if (input[pos] >= '1' && input[pos] <= '9') {
                pos++;
                while (pos < input.length && input[pos] >= '0' && input[pos] <= '9') {
                    pos++;
                }
            } else {
                throw new JsonParseException("Invalid number", start);
            }

            // Fractional part
            if (pos < input.length && input[pos] == '.') {
                pos++;
                if (pos >= input.length || input[pos] < '0' || input[pos] > '9') {
                    throw new JsonParseException("Invalid number: expected digit after '.'", start);
                }
                while (pos < input.length && input[pos] >= '0' && input[pos] <= '9') {
                    pos++;
                }
            }

            // Exponent
            if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++;
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    pos++;
                }
                if (pos >= input.length || input[pos] < '0' || input[pos] > '9') {
                    throw new JsonParseException("Invalid number: expected digit in exponent",
                            start);
                }
                while (pos < input.length && input[pos] >= '0' && input[pos] <= '9') {
                    pos++;
                }
            }

            String rawText = new String(input, start, pos - start, StandardCharsets.UTF_8);
            return JsonPrimitive.ofNumber(rawText);
        }

        void skipWhitespace() {
            while (pos < input.length) {
                byte b = input[pos];
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        /**
         * Advances pos past the expected structural character and advances structIdx.
         */
        private void advancePastStructural(char expected) {
            assert pos < input.length && input[pos] == (byte) expected
                    : "structural mismatch: expected '" + expected + "' at pos " + pos
                            + " but found '" + (pos < input.length ? (char) input[pos] : "EOF")
                            + "'";
            // Advance structIdx to match current position if needed
            while (structIdx < structPositions.length && structPositions[structIdx] < pos) {
                structIdx++;
            }
            if (structIdx < structPositions.length && structPositions[structIdx] == pos) {
                structIdx++;
            }
            pos++;
        }

    }
}
