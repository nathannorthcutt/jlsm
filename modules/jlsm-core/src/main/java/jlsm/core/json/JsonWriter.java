package jlsm.core.json;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes {@link JsonValue} instances to JSON text.
 *
 * <p>
 * Supports both compact (no whitespace) and pretty-printed (indented) output. Handles all
 * {@link JsonValue} subtypes: {@link JsonObject}, {@link JsonArray}, {@link JsonPrimitive}, and
 * {@link JsonNull}.
 *
 * <p>
 * Uses an explicit stack (iterative) for nested structures to avoid stack overflow on deeply nested
 * JSON.
 *
 * <p>
 * This class is stateless and thread-safe.
 *
 */
// @spec serialization.simd-jsonl.R51 — escapes lone surrogates as \\uXXXX sequences
// @spec serialization.simd-jsonl.R59 — detects indentation overflow
public final class JsonWriter {

    /**
     * Serializes a {@link JsonValue} to a compact JSON string (no extra whitespace).
     *
     * @param value the value to serialize; must not be null
     * @return the compact JSON string
     * @throws NullPointerException if value is null
     */
    public static String write(JsonValue value) {
        Objects.requireNonNull(value, "value must not be null");
        return writeIterative(value, 0);
    }

    /**
     * Serializes a {@link JsonValue} to a pretty-printed JSON string.
     *
     * <p>
     * Each nesting level is indented by {@code indent} spaces. An indent of 0 produces compact
     * output equivalent to {@link #write(JsonValue)}.
     *
     * @param value the value to serialize; must not be null
     * @param indent the number of spaces per indentation level; must be non-negative
     * @return the JSON string
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if indent is negative
     */
    public static String write(JsonValue value, int indent) {
        Objects.requireNonNull(value, "value must not be null");
        if (indent < 0) {
            throw new IllegalArgumentException("indent must be non-negative, got " + indent);
        }
        return writeIterative(value, indent);
    }

    /**
     * Frame types for the iterative serialization stack.
     */
    private sealed interface Frame {
        record Value(JsonValue value, int depth) implements Frame {
        }

        record ObjectEntries(Iterator<Map.Entry<String, JsonValue>> iter, int depth,
                boolean first) implements Frame {
        }

        record ArrayElements(Iterator<JsonValue> iter, int depth, boolean first,
                int size) implements Frame {
        }

        record Literal(String text) implements Frame {
        }
    }

    private static String writeIterative(JsonValue root, int indent) {
        boolean pretty = indent > 0;
        StringBuilder sb = new StringBuilder();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame.Value(root, 0));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            switch (frame) {
                case Frame.Literal(String text) -> sb.append(text);

                case Frame.Value(JsonValue value, int depth) -> {
                    switch (value) {
                        case JsonNull _ -> sb.append("null");
                        case JsonPrimitive p -> writePrimitive(sb, p);
                        case JsonObject obj -> {
                            if (obj.size() == 0) {
                                sb.append("{}");
                            } else {
                                sb.append('{');
                                Iterator<Map.Entry<String, JsonValue>> iter = obj.entrySet()
                                        .iterator();
                                stack.push(new Frame.ObjectEntries(iter, depth, true));
                            }
                        }
                        case JsonArray arr -> {
                            if (arr.size() == 0) {
                                sb.append("[]");
                            } else {
                                sb.append('[');
                                Iterator<JsonValue> iter = arr.stream().iterator();
                                stack.push(new Frame.ArrayElements(iter, depth, true, arr.size()));
                            }
                        }
                    }
                }

                case Frame.ObjectEntries(Iterator<Map.Entry<String, JsonValue>> iter, int depth, boolean first) -> {
                    if (!iter.hasNext()) {
                        // Close the object
                        if (pretty) {
                            sb.append('\n');
                            appendIndent(sb, depth, indent);
                        }
                        sb.append('}');
                    } else {
                        Map.Entry<String, JsonValue> entry = iter.next();
                        if (!first) {
                            sb.append(',');
                        }
                        if (pretty) {
                            sb.append('\n');
                            appendIndent(sb, depth + 1, indent);
                        }
                        // Write key
                        writeJsonString(sb, entry.getKey());
                        sb.append(':');
                        if (pretty) {
                            sb.append(' ');
                        }
                        // Push continuation for remaining entries, then push the value
                        stack.push(new Frame.ObjectEntries(iter, depth, false));
                        stack.push(new Frame.Value(entry.getValue(), depth + 1));
                    }
                }

                case Frame.ArrayElements(Iterator<JsonValue> iter, int depth, boolean first, int size) -> {
                    if (!iter.hasNext()) {
                        // Close the array
                        if (pretty) {
                            sb.append('\n');
                            appendIndent(sb, depth, indent);
                        }
                        sb.append(']');
                    } else {
                        JsonValue element = iter.next();
                        if (!first) {
                            sb.append(',');
                        }
                        if (pretty) {
                            sb.append('\n');
                            appendIndent(sb, depth + 1, indent);
                        }
                        // Push continuation for remaining elements, then push the value
                        stack.push(new Frame.ArrayElements(iter, depth, false, size));
                        stack.push(new Frame.Value(element, depth + 1));
                    }
                }
            }
        }

        return sb.toString();
    }

    private static void writePrimitive(StringBuilder sb, JsonPrimitive p) {
        if (p.isString()) {
            writeJsonString(sb, p.asString());
        } else if (p.isBoolean()) {
            sb.append(p.asBoolean() ? "true" : "false");
        } else {
            // Number — use raw text
            assert p.isNumber();
            sb.append(p.asNumberText());
        }
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        sb.append("\\u");
                        sb.append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void appendIndent(StringBuilder sb, int level, int spacesPerLevel) {
        int total = Math.multiplyExact(level, spacesPerLevel);
        for (int i = 0; i < total; i++) {
            sb.append(' ');
        }
    }

    private JsonWriter() {
        // Static utility class — no instantiation
    }
}
