package jlsm.table.internal;

import java.util.ArrayList;
import java.util.List;

import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.Float16;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

/**
 * Line-oriented state-machine parser for the restricted YAML subset produced by {@link YamlWriter}.
 *
 * <p>
 * Supports block mappings and block sequences only. Parsing is schema-guided: the schema determines
 * the expected type of each field value.
 *
 * <p>
 * Thread-safe for concurrent use; each {@link #parse} call operates on local state only.
 */
public final class YamlParser {

    /** Creates a new YamlParser. */
    public YamlParser() {
    }

    /**
     * Parses a YAML string into a {@link JlsmDocument}.
     *
     * @param yaml the YAML string; must not be null
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     * @throws IllegalArgumentException if the YAML is malformed or a field type does not match
     */
    public JlsmDocument parse(String yaml, JlsmSchema schema) {
        if (yaml == null) {
            throw new IllegalArgumentException("yaml must not be null");
        }
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }

        final List<String> lines = splitLines(yaml);
        final int[] lineRef = { 0 };
        return parseMapping(lines, lineRef, 0, schema);
    }

    // -------------------------------------------------------------------------
    // Mapping parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a YAML block mapping at the given indent level.
     *
     * @param lines all lines
     * @param lineRef mutable line index
     * @param expectedIndent the indent level for keys in this mapping
     * @param schema the schema describing expected fields
     * @return a new JlsmDocument
     */
    private JlsmDocument parseMapping(List<String> lines, int[] lineRef, int expectedIndent,
            JlsmSchema schema) {
        assert lines != null : "lines must not be null";
        assert lineRef != null : "lineRef must not be null";
        assert schema != null : "schema must not be null";

        final Object[] values = new Object[schema.fields().size()];

        while (lineRef[0] < lines.size()) {
            final String line = lines.get(lineRef[0]);

            // Skip blank lines
            if (line.isBlank()) {
                lineRef[0]++;
                continue;
            }

            final int indent = measureIndent(line);

            // If indent is less than expected, we've exited this mapping
            if (indent < expectedIndent) {
                break;
            }

            // If indent is greater than expected, it's a formatting error at this level
            if (indent > expectedIndent) {
                throw new IllegalArgumentException("Unexpected indent at line " + (lineRef[0] + 1)
                        + ": expected " + expectedIndent + " spaces but got " + indent);
            }

            // Parse key: value
            final String trimmed = line.substring(indent);
            final int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                throw new IllegalArgumentException(
                        "Expected 'key: value' at line " + (lineRef[0] + 1) + ": " + line);
            }

            final String key = trimmed.substring(0, colonIdx);
            final String afterColon = trimmed.substring(colonIdx + 1);

            final int fieldIdx = schema.fieldIndex(key);
            if (fieldIdx < 0) {
                // Unknown field — skip this line and any nested content
                lineRef[0]++;
                skipNestedContent(lines, lineRef, expectedIndent);
                continue;
            }

            final FieldDefinition fd = schema.fields().get(fieldIdx);
            lineRef[0]++;

            values[fieldIdx] = parseFieldValue(lines, lineRef, expectedIndent, afterColon, fd);
        }

        return DocumentAccess.get().create(schema, values);
    }

    /**
     * Parses the value for a field, which may be inline (after the colon) or in subsequent indented
     * lines (for objects and arrays).
     */
    private Object parseFieldValue(List<String> lines, int[] lineRef, int mappingIndent,
            String afterColon, FieldDefinition fd) {
        assert lines != null : "lines must not be null";
        assert lineRef != null : "lineRef must not be null";
        assert fd != null : "fd must not be null";

        final String valuePart = afterColon.trim();

        return switch (fd.type()) {
            case FieldType.Primitive p -> {
                if (valuePart.isEmpty()) {
                    throw new IllegalArgumentException("Expected value for primitive field '"
                            + fd.name() + "' at line " + lineRef[0]);
                }
                yield parsePrimitiveValue(valuePart, p, fd.name());
            }
            case FieldType.ArrayType at -> {
                // Value should be empty (array items follow on next lines)
                if (!valuePart.isEmpty() && !"null".equals(valuePart)) {
                    throw new IllegalArgumentException("Expected block sequence for array field '"
                            + fd.name() + "' at line " + lineRef[0]);
                }
                if ("null".equals(valuePart)) {
                    yield null;
                }
                yield parseBlockSequence(lines, lineRef, mappingIndent, at);
            }
            case FieldType.VectorType vt -> {
                if ("null".equals(valuePart)) {
                    yield null;
                }
                yield parseVectorSequence(lines, lineRef, mappingIndent, vt);
            }
            case FieldType.ObjectType ot -> {
                if ("null".equals(valuePart)) {
                    yield null;
                }
                // Nested mapping follows on indented lines
                final JlsmSchema subSchema = ot.toSchema(fd.name(), 1);
                yield parseMapping(lines, lineRef, mappingIndent + 2, subSchema);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Block sequence parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a YAML block sequence (lines starting with "- ") at indent = mappingIndent.
     */
    private Object parseVectorSequence(List<String> lines, int[] lineRef, int mappingIndent,
            FieldType.VectorType vt) {
        assert lines != null : "lines must not be null";
        assert lineRef != null : "lineRef must not be null";
        assert vt != null : "vt must not be null";

        final int d = vt.dimensions();

        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = new float[d];
            for (int i = 0; i < d; i++) {
                if (lineRef[0] >= lines.size()) {
                    throw new IllegalArgumentException(
                            "Expected " + d + " vector elements but only got " + i);
                }
                final String line = lines.get(lineRef[0]);
                final String trimmed = line.stripLeading();
                if (!trimmed.startsWith("- ")) {
                    throw new IllegalArgumentException(
                            "Expected YAML list item for vector at line " + (lineRef[0] + 1));
                }
                vec[i] = Float.parseFloat(trimmed.substring(2).trim());
                lineRef[0]++;
            }
            return vec;
        } else {
            final short[] vec = new short[d];
            for (int i = 0; i < d; i++) {
                if (lineRef[0] >= lines.size()) {
                    throw new IllegalArgumentException(
                            "Expected " + d + " vector elements but only got " + i);
                }
                final String line = lines.get(lineRef[0]);
                final String trimmed = line.stripLeading();
                if (!trimmed.startsWith("- ")) {
                    throw new IllegalArgumentException(
                            "Expected YAML list item for vector at line " + (lineRef[0] + 1));
                }
                vec[i] = Float16.fromFloat(Float.parseFloat(trimmed.substring(2).trim()));
                lineRef[0]++;
            }
            return vec;
        }
    }

    private Object[] parseBlockSequence(List<String> lines, int[] lineRef, int mappingIndent,
            FieldType.ArrayType at) {
        assert lines != null : "lines must not be null";
        assert lineRef != null : "lineRef must not be null";
        assert at != null : "at must not be null";

        final ArrayList<Object> elements = new ArrayList<>();

        while (lineRef[0] < lines.size()) {
            final String line = lines.get(lineRef[0]);

            if (line.isBlank()) {
                lineRef[0]++;
                continue;
            }

            final int indent = measureIndent(line);

            // Sequence items are at the same indent as the parent mapping key
            if (indent != mappingIndent) {
                break;
            }

            final String trimmed = line.substring(indent);
            if (!trimmed.startsWith("- ") && !trimmed.equals("-")) {
                break;
            }

            final String itemValue = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";
            lineRef[0]++;

            if (at.elementType() instanceof FieldType.Primitive p) {
                elements.add(parsePrimitiveValue(itemValue, p, "array element"));
            } else if (at.elementType() instanceof FieldType.ObjectType ot) {
                if ("null".equals(itemValue)) {
                    elements.add(null);
                } else {
                    final JlsmSchema subSchema = ot.toSchema("element", 1);
                    elements.add(parseMapping(lines, lineRef, indent + 2, subSchema));
                }
            } else {
                throw new UnsupportedOperationException("Nested arrays not supported in YAML");
            }
        }

        return elements.toArray();
    }

    // -------------------------------------------------------------------------
    // Primitive value parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a scalar string value into the appropriate Java type based on the primitive type.
     */
    private Object parsePrimitiveValue(String raw, FieldType.Primitive p, String fieldName) {
        assert raw != null : "raw must not be null";
        assert p != null : "p must not be null";

        if ("null".equals(raw)) {
            return null;
        }

        try {
            return switch (p) {
                case STRING -> unquoteString(raw);
                case INT8 -> Byte.parseByte(raw);
                case INT16 -> Short.parseShort(raw);
                case INT32 -> Integer.parseInt(raw);
                case INT64 -> Long.parseLong(raw);
                case FLOAT16 -> Float16.fromFloat(Float.parseFloat(raw));
                case FLOAT32 -> Float.parseFloat(raw);
                case FLOAT64 -> Double.parseDouble(raw);
                case BOOLEAN -> parseBoolean(raw, fieldName);
                case TIMESTAMP -> Long.parseLong(raw);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' cannot parse value '" + raw + "' as " + p, e);
        }
    }

    /**
     * Parses a boolean value. Only accepts "true" and "false".
     */
    private static Boolean parseBoolean(String raw, String fieldName) {
        assert raw != null : "raw must not be null";

        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(
                "Field '" + fieldName + "' expects boolean (true/false), got '" + raw + "'");
    }

    /**
     * Unquotes a YAML string value. If the value is surrounded by double quotes, strips them and
     * unescapes. Otherwise returns as-is.
     */
    private static String unquoteString(String raw) {
        assert raw != null : "raw must not be null";

        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            final String inner = raw.substring(1, raw.length() - 1);
            return unescapeYamlString(inner);
        }
        return raw;
    }

    /**
     * Unescapes a double-quoted YAML string (backslash sequences).
     */
    private static String unescapeYamlString(String s) {
        assert s != null : "s must not be null";

        final StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                final char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append(c);
                        sb.append(next);
                    }
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /**
     * Splits a string into lines. Handles \n, \r\n, and \r.
     */
    private static List<String> splitLines(String s) {
        assert s != null : "s must not be null";

        final ArrayList<String> lines = new ArrayList<>();
        int start = 0;
        final int len = s.length();

        int i = 0;
        while (i < len) {
            if (s.charAt(i) == '\n') {
                lines.add(s.substring(start, i));
                start = i + 1;
                i++;
            } else if (s.charAt(i) == '\r') {
                lines.add(s.substring(start, i));
                i++;
                if (i < len && s.charAt(i) == '\n') {
                    i++;
                }
                start = i;
            } else {
                i++;
            }
        }

        // Last segment (even if empty)
        if (start <= len) {
            final String last = s.substring(start);
            if (!last.isEmpty()) {
                lines.add(last);
            }
        }

        return lines;
    }

    /**
     * Measures the number of leading spaces on a line.
     */
    private static int measureIndent(String line) {
        assert line != null : "line must not be null";

        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /**
     * Skips lines that are nested deeper than the given indent level.
     */
    private static void skipNestedContent(List<String> lines, int[] lineRef, int parentIndent) {
        assert lines != null : "lines must not be null";
        assert lineRef != null : "lineRef must not be null";

        while (lineRef[0] < lines.size()) {
            final String line = lines.get(lineRef[0]);
            if (line.isBlank()) {
                lineRef[0]++;
                continue;
            }
            final int indent = measureIndent(line);
            if (indent <= parentIndent) {
                break;
            }
            lineRef[0]++;
        }
    }
}
