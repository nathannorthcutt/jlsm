package jlsm.core.json;

/**
 * Root type for all JSON values in the jlsm JSON model.
 *
 * <p>
 * This is a sealed interface permitting exactly four subtypes that correspond to the four
 * structural categories of JSON values defined by RFC 8259: objects, arrays, primitives (strings,
 * numbers, booleans), and null.
 *
 * <p>
 * Pattern matching with {@code switch} expressions is the intended consumption mechanism:
 *
 * <pre>{@code
 * switch (value) {
 *     case JsonObject obj -> ...
 *     case JsonArray arr  -> ...
 *     case JsonPrimitive p -> ...
 *     case JsonNull n     -> ...
 * }
 * }</pre>
 *
 * <p>
 * All implementations are deeply immutable. Equality semantics are defined per subtype.
 *
 * @spec F15.R4 — sealed interface permitting JsonObject, JsonArray, JsonPrimitive, JsonNull
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonPrimitive, JsonNull {
}
