package jlsm.core.json;

/**
 * Singleton representation of JSON {@code null}.
 *
 * <p>
 * Implemented as an enum to guarantee exactly one instance. Use {@code JsonNull.INSTANCE} to obtain
 * the singleton.
 *
 * @spec F15.R5 — singleton enum with INSTANCE constant, identity equals
 */
public enum JsonNull implements JsonValue {

    /** The single JSON null value. */
    INSTANCE;
}
