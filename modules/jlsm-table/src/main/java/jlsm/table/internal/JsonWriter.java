package jlsm.table.internal;

import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.Float16;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

/**
 * Serializes a {@link JlsmDocument} to a JSON string.
 *
 * <p>
 * Thread-safe for concurrent use; each {@link #write} call creates its own {@link StringBuilder}.
 */
public final class JsonWriter {

    /** Creates a new JsonWriter. */
    public JsonWriter() {
    }

    /**
     * Serializes the document to JSON.
     *
     * @param doc the document to serialize; must not be null
     * @param indent spaces per indent level (0 = compact, &gt;0 = pretty)
     * @return a JSON string
     */
    public String write(JlsmDocument doc, int indent) {
        assert doc != null : "doc must not be null";
        assert indent >= 0 : "indent must not be negative";

        final StringBuilder sb = new StringBuilder(256);
        writeDocument(doc, sb, indent, 0);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void writeDocument(JlsmDocument doc, StringBuilder sb, int indent, int depth) {
        assert doc != null : "doc must not be null";
        assert sb != null : "sb must not be null";

        final JlsmSchema schema = doc.schema();
        final Object[] values = DocumentAccess.get().values(doc);

        sb.append('{');

        boolean firstField = true;
        for (int i = 0; i < schema.fields().size(); i++) {
            final FieldDefinition fd = schema.fields().get(i);
            final Object value = values[i];

            if (!firstField) {
                sb.append(',');
            }
            firstField = false;

            if (indent > 0) {
                sb.append('\n');
                appendIndent(sb, indent, depth + 1);
            }

            writeString(fd.name(), sb);
            sb.append(':');
            if (indent > 0) {
                sb.append(' ');
            }

            writeValue(fd.type(), value, sb, indent, depth + 1);
        }

        if (indent > 0 && !schema.fields().isEmpty()) {
            sb.append('\n');
            appendIndent(sb, indent, depth);
        }
        sb.append('}');
    }

    private void writeValue(FieldType type, Object value, StringBuilder sb, int indent, int depth) {
        assert sb != null : "sb must not be null";

        if (value == null) {
            sb.append("null");
            return;
        }

        switch (type) {
            case FieldType.Primitive p -> writePrimitive(p, value, sb, indent, depth);
            case FieldType.BoundedString _ ->
                writePrimitive(FieldType.Primitive.STRING, value, sb, indent, depth);
            case FieldType.ArrayType at -> writeArray(at, (Object[]) value, sb, indent, depth);
            case FieldType.VectorType vt -> writeVector(vt, value, sb, indent, depth);
            case FieldType.ObjectType _ -> writeDocument((JlsmDocument) value, sb, indent, depth);
        }
    }

    private void writePrimitive(FieldType.Primitive p, Object value, StringBuilder sb, int indent,
            int depth) {
        assert p != null : "p must not be null";
        assert value != null : "value must not be null";
        assert sb != null : "sb must not be null";

        switch (p) {
            case STRING -> writeString((String) value, sb);
            case INT8 -> sb.append((Byte) value);
            case INT16 -> sb.append((Short) value);
            case INT32 -> sb.append((Integer) value);
            case INT64 -> sb.append((Long) value);
            case FLOAT16 -> {
                final float f = Float16.toFloat((Short) value);
                writeFloat(f, sb);
            }
            case FLOAT32 -> writeFloat((Float) value, sb);
            case FLOAT64 -> writeDouble((Double) value, sb);
            case BOOLEAN -> sb.append((Boolean) value ? "true" : "false");
            case TIMESTAMP -> sb.append((Long) value);
        }
    }

    private void writeArray(FieldType.ArrayType at, Object[] elements, StringBuilder sb, int indent,
            int depth) {
        assert at != null : "at must not be null";
        assert elements != null : "elements must not be null";
        assert sb != null : "sb must not be null";

        sb.append('[');
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                sb.append(',');
                if (indent > 0) {
                    sb.append(' ');
                }
            }
            writeValue(at.elementType(), elements[i], sb, indent, depth);
        }
        sb.append(']');
    }

    private void writeVector(FieldType.VectorType vt, Object value, StringBuilder sb, int indent,
            int depth) {
        assert vt != null : "vt must not be null";
        assert value != null : "value must not be null";
        assert sb != null : "sb must not be null";

        sb.append('[');
        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = (float[]) value;
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) {
                    sb.append(',');
                    if (indent > 0) {
                        sb.append(' ');
                    }
                }
                writeFloat(vec[i], sb);
            }
        } else {
            final short[] vec = (short[]) value;
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) {
                    sb.append(',');
                    if (indent > 0) {
                        sb.append(' ');
                    }
                }
                writeFloat(Float16.toFloat(vec[i]), sb);
            }
        }
        sb.append(']');
    }

    private static void writeFloat(float f, StringBuilder sb) {
        assert sb != null : "sb must not be null";

        if (!Float.isFinite(f)) {
            sb.append("null");
        } else {
            sb.append(f);
        }
    }

    private static void writeDouble(double d, StringBuilder sb) {
        assert sb != null : "sb must not be null";

        if (!Double.isFinite(d)) {
            sb.append("null");
        } else {
            sb.append(d);
        }
    }

    /**
     * Appends a JSON-escaped string (with surrounding quotes) to the builder.
     *
     * @param s the string to escape; must not be null
     * @param sb the builder to append to; must not be null
     */
    private static void writeString(String s, StringBuilder sb) {
        assert s != null : "s must not be null";
        assert sb != null : "sb must not be null";

        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void appendIndent(StringBuilder sb, int indent, int depth) {
        assert sb != null : "sb must not be null";
        assert indent >= 0 : "indent must not be negative";
        assert depth >= 0 : "depth must not be negative";

        final int spaces = indent * depth;
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }
}
