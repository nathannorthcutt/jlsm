package jlsm.table.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.Float16;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

/**
 * State-machine JSON parser for {@link JlsmDocument}.
 *
 * <p>
 * Parses JSON objects into {@link JlsmDocument} instances according to a provided
 * {@link JlsmSchema}. Unknown fields are skipped; missing fields default to null. Type mismatches
 * throw {@link IllegalArgumentException}. Malformed JSON throws {@link IllegalArgumentException}.
 *
 * <p>
 * String scanning uses SIMD acceleration via {@code jdk.incubator.vector.ByteVector} to locate
 * quote and backslash delimiters in bulk.
 */
public final class JsonParser {

    private static final VectorSpecies<Byte> BSPECIES = ByteVector.SPECIES_PREFERRED;

    /** Creates a new JsonParser. */
    public JsonParser() {
    }

    /**
     * Parses a JSON string into a {@link JlsmDocument}.
     *
     * @param json the JSON string; must not be null
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     * @throws IllegalArgumentException if the JSON is malformed or a field type does not match the
     *             schema
     */
    public JlsmDocument parse(String json, JlsmSchema schema) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }

        final byte[] src = json.getBytes(StandardCharsets.UTF_8);
        final int[] posRef = { 0 };

        skipWhitespace(src, posRef);
        final JlsmDocument result = parseObject(src, posRef, schema);
        skipWhitespace(src, posRef);
        if (posRef[0] != src.length) {
            throw new IllegalArgumentException(
                    "Unexpected content after JSON object at position " + posRef[0]);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Object parsing
    // -------------------------------------------------------------------------

    private JlsmDocument parseObject(byte[] src, int[] pos, JlsmSchema schema) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";
        assert schema != null : "schema must not be null";

        expect(src, pos, '{');

        final Object[] values = new Object[schema.fields().size()];

        skipWhitespace(src, pos);
        if (pos[0] < src.length && src[pos[0]] == '}') {
            pos[0]++;
            return DocumentAccess.get().create(schema, values);
        }

        boolean first = true;
        while (pos[0] < src.length) {
            if (!first) {
                skipWhitespace(src, pos);
                expect(src, pos, ',');
            }
            first = false;

            skipWhitespace(src, pos);

            // Check for end of object (trailing comma tolerance)
            if (pos[0] < src.length && src[pos[0]] == '}') {
                break;
            }

            // Parse field key
            final String key = parseString(src, pos);

            skipWhitespace(src, pos);
            expect(src, pos, ':');
            skipWhitespace(src, pos);

            final int fieldIdx = schema.fieldIndex(key);
            if (fieldIdx < 0) {
                // Unknown field — skip its value
                skipValue(src, pos);
            } else {
                final FieldDefinition fd = schema.fields().get(fieldIdx);
                values[fieldIdx] = parseValue(src, pos, fd.type(), fd.name(), schema);
            }

            skipWhitespace(src, pos);
            if (pos[0] < src.length && src[pos[0]] == '}') {
                break;
            }
        }

        skipWhitespace(src, pos);
        expect(src, pos, '}');

        return DocumentAccess.get().create(schema, values);
    }

    // -------------------------------------------------------------------------
    // Value parsing (dispatch by FieldType)
    // -------------------------------------------------------------------------

    private Object parseValue(byte[] src, int[] pos, FieldType type, String fieldName,
            JlsmSchema parentSchema) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";
        assert type != null : "type must not be null";

        skipWhitespace(src, pos);

        if (pos[0] >= src.length) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }

        final byte b = src[pos[0]];

        // null literal
        if (b == 'n') {
            parseLiteral(src, pos, "null");
            return null;
        }

        return switch (type) {
            case FieldType.Primitive p -> parsePrimitive(src, pos, p, fieldName, b);
            case FieldType.ArrayType at -> parseArray(src, pos, at, fieldName, parentSchema);
            case FieldType.ObjectType ot -> {
                final JlsmSchema subSchema = ot.toSchema(fieldName, parentSchema.version());
                yield parseObject(src, pos, subSchema);
            }
        };
    }

    private Object parsePrimitive(byte[] src, int[] pos, FieldType.Primitive p, String fieldName,
            byte firstByte) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";
        assert p != null : "p must not be null";

        return switch (p) {
            case STRING -> {
                if (firstByte != '"') {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' expects STRING (quoted), got token starting with '"
                            + (char) firstByte + "'");
                }
                yield parseString(src, pos);
            }
            case BOOLEAN -> {
                if (firstByte != 't' && firstByte != 'f') {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' expects BOOLEAN (true/false), got token starting with '"
                            + (char) firstByte + "'");
                }
                yield parseBoolean(src, pos);
            }
            case INT8 -> {
                requireNumeric(fieldName, firstByte);
                yield Byte.parseByte(parseNumberString(src, pos));
            }
            case INT16 -> {
                requireNumeric(fieldName, firstByte);
                yield Short.parseShort(parseNumberString(src, pos));
            }
            case INT32 -> {
                requireNumeric(fieldName, firstByte);
                final String numStr = parseNumberString(src, pos);
                try {
                    yield Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' INT32 value out of range: " + numStr, e);
                }
            }
            case INT64 -> {
                requireNumeric(fieldName, firstByte);
                yield Long.parseLong(parseNumberString(src, pos));
            }
            case FLOAT16 -> {
                requireNumeric(fieldName, firstByte);
                final float f = Float.parseFloat(parseNumberString(src, pos));
                yield Float16.fromFloat(f);
            }
            case FLOAT32 -> {
                requireNumeric(fieldName, firstByte);
                yield Float.parseFloat(parseNumberString(src, pos));
            }
            case FLOAT64 -> {
                requireNumeric(fieldName, firstByte);
                yield Double.parseDouble(parseNumberString(src, pos));
            }
            case TIMESTAMP -> {
                requireNumeric(fieldName, firstByte);
                yield Long.parseLong(parseNumberString(src, pos));
            }
        };
    }

    private Object[] parseArray(byte[] src, int[] pos, FieldType.ArrayType at, String fieldName,
            JlsmSchema parentSchema) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";
        assert at != null : "at must not be null";

        expect(src, pos, '[');
        skipWhitespace(src, pos);

        if (pos[0] < src.length && src[pos[0]] == ']') {
            pos[0]++;
            return new Object[0];
        }

        final ArrayList<Object> list = new ArrayList<>();
        boolean first = true;

        while (pos[0] < src.length) {
            if (!first) {
                skipWhitespace(src, pos);
                expect(src, pos, ',');
                skipWhitespace(src, pos);
            }
            first = false;

            if (pos[0] < src.length && src[pos[0]] == ']') {
                break;
            }

            list.add(parseValue(src, pos, at.elementType(), fieldName + "[]", parentSchema));

            skipWhitespace(src, pos);
            if (pos[0] < src.length && src[pos[0]] == ']') {
                break;
            }
        }

        expect(src, pos, ']');
        return list.toArray();
    }

    // -------------------------------------------------------------------------
    // Primitive value parsers
    // -------------------------------------------------------------------------

    /**
     * Parses a JSON string value (surrounded by double-quotes). Uses SIMD acceleration via
     * {@link ByteVector} to scan for quote and backslash bytes in bulk.
     */
    private String parseString(byte[] src, int[] pos) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        expect(src, pos, '"');

        final StringBuilder sb = new StringBuilder();
        int i = pos[0];

        while (i < src.length) {
            // SIMD scan to the next quote or backslash
            final int delim = scanToStringEnd(src, i);

            // Append the safe segment
            if (delim > i) {
                sb.append(new String(src, i, delim - i, StandardCharsets.UTF_8));
            }

            if (delim >= src.length) {
                throw new IllegalArgumentException("Unterminated string in JSON");
            }

            final byte b = src[delim];
            if (b == '"') {
                // End of string
                i = delim + 1;
                break;
            }

            // Backslash escape
            if (delim + 1 >= src.length) {
                throw new IllegalArgumentException("Truncated escape sequence in JSON string");
            }
            final byte escape = src[delim + 1];
            switch (escape) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (delim + 5 >= src.length) {
                        throw new IllegalArgumentException(
                                "Truncated \\u escape sequence in JSON string");
                    }
                    final String hex = new String(src, delim + 2, 4, StandardCharsets.US_ASCII);
                    try {
                        final int codePoint = Integer.parseInt(hex, 16);
                        sb.append((char) codePoint);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid \\u escape sequence: \\u" + hex,
                                e);
                    }
                    i = delim + 6;
                    continue;
                }
                default -> throw new IllegalArgumentException(
                        "Unknown escape sequence: \\" + (char) escape);
            }
            i = delim + 2;
        }

        pos[0] = i;
        return sb.toString();
    }

    /**
     * Scans byte array from {@code pos} to find the next {@code "} or {@code \} byte using SIMD
     * acceleration via {@link ByteVector}.
     *
     * @param src the byte array
     * @param pos the starting position
     * @return the index of the first {@code "} or {@code \}, or {@code src.length} if not found
     */
    private static int scanToStringEnd(byte[] src, int pos) {
        assert src != null : "src must not be null";
        assert pos >= 0 : "pos must not be negative";

        final int len = BSPECIES.length();
        final ByteVector delimiters = ByteVector.broadcast(BSPECIES, (byte) '"');
        final ByteVector escapes = ByteVector.broadcast(BSPECIES, (byte) '\\');

        while (pos + len <= src.length) {
            final ByteVector chunk = ByteVector.fromArray(BSPECIES, src, pos);
            final var hit = chunk.eq(delimiters).or(chunk.eq(escapes));
            if (hit.anyTrue()) {
                return pos + hit.firstTrue();
            }
            pos += len;
        }

        // Scalar remainder
        while (pos < src.length) {
            final byte b = src[pos];
            if (b == '"' || b == '\\') {
                return pos;
            }
            pos++;
        }

        return pos; // not found — caller handles unterminated string
    }

    private boolean parseBoolean(byte[] src, int[] pos) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        if (src[pos[0]] == 't') {
            parseLiteral(src, pos, "true");
            return true;
        } else {
            parseLiteral(src, pos, "false");
            return false;
        }
    }

    /**
     * Parses a JSON number token and returns it as a String for further type conversion.
     *
     * @param src the source bytes
     * @param pos the current position (updated on return)
     * @return the raw number string
     */
    private static String parseNumberString(byte[] src, int[] pos) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        final int start = pos[0];
        int i = start;

        // Optional leading minus
        if (i < src.length && src[i] == '-') {
            i++;
        }

        // Integer digits
        while (i < src.length && src[i] >= '0' && src[i] <= '9') {
            i++;
        }

        // Optional fractional part
        if (i < src.length && src[i] == '.') {
            i++;
            while (i < src.length && src[i] >= '0' && src[i] <= '9') {
                i++;
            }
        }

        // Optional exponent
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            i++;
            if (i < src.length && (src[i] == '+' || src[i] == '-')) {
                i++;
            }
            while (i < src.length && src[i] >= '0' && src[i] <= '9') {
                i++;
            }
        }

        if (i == start) {
            throw new IllegalArgumentException("Expected a number at position " + start);
        }

        pos[0] = i;
        return new String(src, start, i - start, StandardCharsets.US_ASCII);
    }

    // -------------------------------------------------------------------------
    // Skip unknown values
    // -------------------------------------------------------------------------

    private void skipValue(byte[] src, int[] pos) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        skipWhitespace(src, pos);

        if (pos[0] >= src.length) {
            throw new IllegalArgumentException("Unexpected end of JSON while skipping value");
        }

        final byte b = src[pos[0]];
        if (b == '"') {
            parseString(src, pos);
        } else if (b == '{') {
            skipObject(src, pos);
        } else if (b == '[') {
            skipArray(src, pos);
        } else if (b == 't') {
            parseLiteral(src, pos, "true");
        } else if (b == 'f') {
            parseLiteral(src, pos, "false");
        } else if (b == 'n') {
            parseLiteral(src, pos, "null");
        } else if (b == '-' || (b >= '0' && b <= '9')) {
            parseNumberString(src, pos);
        } else {
            throw new IllegalArgumentException(
                    "Unexpected token '" + (char) b + "' at position " + pos[0]);
        }
    }

    private void skipObject(byte[] src, int[] pos) {
        expect(src, pos, '{');
        skipWhitespace(src, pos);
        if (pos[0] < src.length && src[pos[0]] == '}') {
            pos[0]++;
            return;
        }
        boolean first = true;
        while (pos[0] < src.length) {
            if (!first) {
                skipWhitespace(src, pos);
                expect(src, pos, ',');
            }
            first = false;
            skipWhitespace(src, pos);
            if (pos[0] < src.length && src[pos[0]] == '}') {
                break;
            }
            parseString(src, pos);
            skipWhitespace(src, pos);
            expect(src, pos, ':');
            skipWhitespace(src, pos);
            skipValue(src, pos);
            skipWhitespace(src, pos);
            if (pos[0] < src.length && src[pos[0]] == '}') {
                break;
            }
        }
        expect(src, pos, '}');
    }

    private void skipArray(byte[] src, int[] pos) {
        expect(src, pos, '[');
        skipWhitespace(src, pos);
        if (pos[0] < src.length && src[pos[0]] == ']') {
            pos[0]++;
            return;
        }
        boolean first = true;
        while (pos[0] < src.length) {
            if (!first) {
                skipWhitespace(src, pos);
                expect(src, pos, ',');
                skipWhitespace(src, pos);
            }
            first = false;
            if (pos[0] < src.length && src[pos[0]] == ']') {
                break;
            }
            skipValue(src, pos);
            skipWhitespace(src, pos);
            if (pos[0] < src.length && src[pos[0]] == ']') {
                break;
            }
        }
        expect(src, pos, ']');
    }

    // -------------------------------------------------------------------------
    // Low-level helpers
    // -------------------------------------------------------------------------

    private static void skipWhitespace(byte[] src, int[] pos) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        int i = pos[0];
        while (i < src.length) {
            final byte b = src[i];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                i++;
            } else {
                break;
            }
        }
        pos[0] = i;
    }

    private static void expect(byte[] src, int[] pos, char expected) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";

        if (pos[0] >= src.length) {
            throw new IllegalArgumentException(
                    "Expected '" + expected + "' but reached end of JSON");
        }
        if (src[pos[0]] != (byte) expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos[0]
                    + " but got '" + (char) src[pos[0]] + "'");
        }
        pos[0]++;
    }

    private static void parseLiteral(byte[] src, int[] pos, String literal) {
        assert src != null : "src must not be null";
        assert pos != null : "pos must not be null";
        assert literal != null : "literal must not be null";

        final byte[] litBytes = literal.getBytes(StandardCharsets.US_ASCII);
        if (pos[0] + litBytes.length > src.length) {
            throw new IllegalArgumentException(
                    "Expected '" + literal + "' at position " + pos[0] + " but input is too short");
        }
        for (int i = 0; i < litBytes.length; i++) {
            if (src[pos[0] + i] != litBytes[i]) {
                throw new IllegalArgumentException(
                        "Expected '" + literal + "' at position " + pos[0]);
            }
        }
        pos[0] += litBytes.length;
    }

    private static void requireNumeric(String fieldName, byte firstByte) {
        if (firstByte != '-' && (firstByte < '0' || firstByte > '9')) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' expects a numeric value, got token starting with '"
                            + (char) firstByte + "'");
        }
    }
}
