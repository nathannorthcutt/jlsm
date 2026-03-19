package jlsm.table.internal;

import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.Float16;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

/**
 * Serializes a {@link JlsmDocument} to a YAML block-style string.
 *
 * <p>
 * Thread-safe for concurrent use; each {@link #write} call creates its own {@link StringBuilder}.
 * Output uses block mappings and block sequences (no flow style).
 */
public final class YamlWriter {

    /** Characters that require quoting a YAML string value. */
    private static final String SPECIAL_CHARS = "#:{}[]\n\r\t,&*?|>!%@`";

    /** Creates a new YamlWriter. */
    public YamlWriter() {
    }

    /**
     * Serializes the document to YAML block style.
     *
     * @param doc the document to serialize; must not be null
     * @return a YAML string (no trailing newline)
     */
    public String write(JlsmDocument doc) {
        assert doc != null : "doc must not be null";

        final StringBuilder sb = new StringBuilder(256);
        writeDocument(doc, sb, 0);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void writeDocument(JlsmDocument doc, StringBuilder sb, int depth) {
        assert doc != null : "doc must not be null";
        assert sb != null : "sb must not be null";
        assert depth >= 0 : "depth must not be negative";

        final JlsmSchema schema = doc.schema();
        final Object[] values = DocumentAccess.get().values(doc);

        for (int i = 0; i < schema.fields().size(); i++) {
            final FieldDefinition fd = schema.fields().get(i);
            final Object value = values[i];

            if (i > 0) {
                sb.append('\n');
            }

            appendIndent(sb, depth);
            sb.append(fd.name());
            sb.append(':');

            writeValue(fd.type(), value, sb, depth);
        }
    }

    private void writeValue(FieldType type, Object value, StringBuilder sb, int depth) {
        assert sb != null : "sb must not be null";

        if (value == null) {
            sb.append(" null");
            return;
        }

        switch (type) {
            case FieldType.Primitive p -> writePrimitive(p, value, sb);
            case FieldType.BoundedString _ -> writePrimitive(FieldType.Primitive.STRING, value, sb);
            case FieldType.ArrayType at -> writeArray(at, (Object[]) value, sb, depth);
            case FieldType.VectorType vt -> writeVector(vt, value, sb, depth);
            case FieldType.ObjectType _ -> {
                sb.append('\n');
                writeDocument((JlsmDocument) value, sb, depth + 1);
            }
        }
    }

    private void writePrimitive(FieldType.Primitive p, Object value, StringBuilder sb) {
        assert p != null : "p must not be null";
        assert value != null : "value must not be null";
        assert sb != null : "sb must not be null";

        sb.append(' ');
        switch (p) {
            case STRING -> writeYamlString((String) value, sb);
            case INT8 -> sb.append((Byte) value);
            case INT16 -> sb.append((Short) value);
            case INT32 -> sb.append((Integer) value);
            case INT64 -> sb.append((Long) value);
            case FLOAT16 -> {
                final float f = Float16.toFloat((Short) value);
                writeYamlFloat(f, sb);
            }
            case FLOAT32 -> writeYamlFloat((Float) value, sb);
            case FLOAT64 -> writeYamlDouble((Double) value, sb);
            case BOOLEAN -> sb.append((Boolean) value ? "true" : "false");
            case TIMESTAMP -> sb.append((Long) value);
        }
    }

    private void writeArray(FieldType.ArrayType at, Object[] elements, StringBuilder sb,
            int depth) {
        assert at != null : "at must not be null";
        assert elements != null : "elements must not be null";
        assert sb != null : "sb must not be null";

        for (int i = 0; i < elements.length; i++) {
            sb.append('\n');
            appendIndent(sb, depth);
            sb.append('-');
            writeArrayElement(at.elementType(), elements[i], sb, depth);
        }
    }

    private void writeVector(FieldType.VectorType vt, Object value, StringBuilder sb, int depth) {
        assert vt != null : "vt must not be null";
        assert value != null : "value must not be null";
        assert sb != null : "sb must not be null";

        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = (float[]) value;
            for (int i = 0; i < vec.length; i++) {
                sb.append('\n');
                appendIndent(sb, depth);
                sb.append("- ");
                writeYamlFloat(vec[i], sb);
            }
        } else {
            final short[] vec = (short[]) value;
            for (int i = 0; i < vec.length; i++) {
                sb.append('\n');
                appendIndent(sb, depth);
                sb.append("- ");
                writeYamlFloat(Float16.toFloat(vec[i]), sb);
            }
        }
    }

    private void writeArrayElement(FieldType elementType, Object value, StringBuilder sb,
            int depth) {
        assert sb != null : "sb must not be null";

        if (value == null) {
            sb.append(" null");
            return;
        }

        switch (elementType) {
            case FieldType.Primitive p -> writePrimitive(p, value, sb);
            case FieldType.BoundedString _ -> writePrimitive(FieldType.Primitive.STRING, value, sb);
            case FieldType.ArrayType _ ->
                throw new UnsupportedOperationException("Nested arrays not supported in YAML");
            case FieldType.VectorType _ ->
                throw new UnsupportedOperationException("Nested vectors not supported in YAML");
            case FieldType.ObjectType _ -> {
                sb.append('\n');
                writeDocument((JlsmDocument) value, sb, depth + 1);
            }
        }
    }

    private static void writeYamlFloat(float f, StringBuilder sb) {
        assert sb != null : "sb must not be null";
        if (!Float.isFinite(f)) {
            sb.append("null");
        } else {
            sb.append(f);
        }
    }

    private static void writeYamlDouble(double d, StringBuilder sb) {
        assert sb != null : "sb must not be null";
        if (!Double.isFinite(d)) {
            sb.append("null");
        } else {
            sb.append(d);
        }
    }

    /**
     * Writes a YAML string value. Quotes with double-quotes if the string is empty, looks like a
     * YAML keyword (true/false/null), looks like a number, or contains special characters.
     */
    private static void writeYamlString(String s, StringBuilder sb) {
        assert s != null : "s must not be null";
        assert sb != null : "sb must not be null";

        if (needsQuoting(s)) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            sb.append('"');
        } else {
            sb.append(s);
        }
    }

    /**
     * Determines whether a YAML scalar string needs to be quoted.
     */
    private static boolean needsQuoting(String s) {
        assert s != null : "s must not be null";

        // Empty string must be quoted
        if (s.isEmpty()) {
            return true;
        }

        // YAML keywords
        if ("true".equals(s) || "false".equals(s) || "null".equals(s) || "True".equals(s)
                || "False".equals(s) || "Null".equals(s) || "TRUE".equals(s) || "FALSE".equals(s)
                || "NULL".equals(s) || "yes".equals(s) || "no".equals(s) || "Yes".equals(s)
                || "No".equals(s) || "YES".equals(s) || "NO".equals(s) || "on".equals(s)
                || "off".equals(s) || "On".equals(s) || "Off".equals(s) || "ON".equals(s)
                || "OFF".equals(s)) {
            return true;
        }

        // Check if it looks like a number
        if (looksLikeNumber(s)) {
            return true;
        }

        // Leading/trailing whitespace
        if (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') {
            return true;
        }

        // Contains special characters
        for (int i = 0; i < s.length(); i++) {
            if (SPECIAL_CHARS.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the string looks like a YAML number (integer or float).
     */
    private static boolean looksLikeNumber(String s) {
        assert s != null && !s.isEmpty() : "s must not be null or empty";

        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            i = 1;
        }
        if (i >= s.length()) {
            return false;
        }

        boolean hasDigit = false;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            hasDigit = true;
            i++;
        }

        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                hasDigit = true;
                i++;
            }
        }

        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
        }

        return hasDigit && i == s.length();
    }

    private static void appendIndent(StringBuilder sb, int depth) {
        assert sb != null : "sb must not be null";
        assert depth >= 0 : "depth must not be negative";

        final int spaces = 2 * depth;
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }
}
