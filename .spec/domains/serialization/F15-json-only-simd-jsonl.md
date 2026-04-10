---
{
  "id": "F15",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["serialization", "engine"],
  "requires": ["F13", "F14"],
  "invalidates": ["F14.R48", "F14.R49"],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [
    "algorithms/serialization/simd-on-demand-serialization",
    "algorithms/serialization/panama-ffm-inline-machine-code",
    "algorithms/serialization/simd-serialization-java-fallbacks"
  ],
  "open_obligations": []
}
---

# F15 — JSON-Only SIMD On-Demand Parser with JSONL Streaming

## Requirements

### YAML removal

R1. `JlsmDocument` must not expose any YAML-related methods. The `toYaml()` and `fromYaml(String, JlsmSchema)` methods must be removed entirely.

R2. The `YamlParser` and `YamlWriter` classes in `jlsm.table.internal` must be removed entirely. No YAML parsing or serialization code may remain in the codebase.

R3. All tests that reference YAML parsing or serialization (including `YamlRoundTripTest` and any YAML assertions in other test classes) must be removed as part of this change.

### JSON value types (jlsm-core)

R4. A `JsonValue` sealed interface must be defined in an exported package of `jlsm.core`. It must permit exactly four implementations: `JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`.

R5. `JsonNull` must be implemented as an enum with a single constant (e.g., `enum JsonNull implements JsonValue { INSTANCE }`). This ensures singleton identity across serialization, reflection, and normal construction. `equals()` must use identity comparison.

R6. `JsonPrimitive` must represent exactly three JSON primitive types: strings, numbers, and booleans. It must expose `isString()`, `isNumber()`, `isBoolean()` type-test methods.

R7. `JsonPrimitive.asString()` must return the string value when the primitive is a string, and throw `IllegalStateException` when it is not. The same pattern applies to `asBoolean()`.

R8. `JsonPrimitive` must store numbers as their original text representation from parsing. `asNumberText()` must return this original text. Convenience methods `asInt()`, `asLong()`, `asDouble()`, and `asBigDecimal()` must parse from the stored text on each call, throwing `NumberFormatException` if the value cannot be represented in the requested type (e.g., `asInt()` on `"1e2"` must throw because `1e2` is not an integer literal).

R9. `JsonObject` must provide Map-like access to its members: `get(String key)` returning `JsonValue`, `containsKey(String key)`, `keys()` returning a `Set<String>`, `size()`, `entrySet()`, and `getOrDefault(String key, JsonValue defaultValue)`.

R10. `JsonObject` must preserve insertion order of keys. Iterating `keys()` or `entrySet()` must return entries in the order they were added.

R11. `JsonObject` must reject duplicate keys at construction. Whether built from parsing or programmatic construction, adding a key that already exists must be rejected with `IllegalArgumentException`.

R12. `JsonArray` must provide List-like access to its elements: `get(int index)` returning `JsonValue`, `size()`, and `stream()` returning `Stream<JsonValue>`.

R13. All `JsonValue` implementations must be deeply immutable after construction. Internal collections must be defensively copied at construction time or wrapped with unmodifiable views. No reference to mutable state held by the caller may be retained.

R14. `JsonObject` and `JsonArray` must implement `equals()` and `hashCode()` based on structural deep equality of their contents. `JsonPrimitive` equality for numbers must be based on numeric value (using `BigDecimal.compareTo() == 0`), not on text representation. Two number primitives representing the same mathematical value must be equal regardless of their original text form.

R15. `JsonObject` must be constructable only via a static factory method or builder that validates all keys are non-null, non-blank strings and all values are non-null `JsonValue` instances. `JsonArray` must be constructable only via a static factory method that validates all elements are non-null `JsonValue` instances. Direct public constructors that bypass validation must not exist.

### On-demand JSON parser (jlsm-core)

R16. The JSON parser must implement a two-stage architecture internally: stage 1 builds a structural index of all token positions in the input, stage 2 materializes values from the indexed positions.

R17. The public `parse(String)` method must return a fully materialized, self-contained `JsonValue` tree that has no references to the original input buffer or structural index. The implementation uses the two-stage architecture internally to perform the materialization.

R18. Stage 1 structural indexing must classify characters into structural categories (commas, colons, brackets/braces, whitespace, quotes) using SIMD vector operations when the runtime tier supports it.

R19. Stage 1 must correctly identify quoted string regions by computing the parity of consecutive backslash runs before each quote character. A quote preceded by an even number of backslashes (including zero) is a structural quote; a quote preceded by an odd number is escaped. The backslash-parity computation must handle runs of arbitrary length.

R20. The parser must implement three processing tiers with automatic runtime detection: Tier 1 (Panama FFM with PCLMULQDQ/PMULL for carry-less multiply quote masking), Tier 2 (Vector API for character classification and a shift-XOR cascade for prefix-XOR), Tier 3 (pure scalar byte-by-byte processing).

R21. Tier detection must occur once at class-load time and the result must be stored in a `static final` field so the JIT can constant-fold the dispatch.

R22. Tier detection must catch all exceptions from native access probing (including `IllegalCallerException`, `UnsupportedOperationException`, `UnsatisfiedLinkError`, and `NoClassDefFoundError`) and fall through to the next tier. A failure during tier 1 detection must never prevent tier 2 or tier 3 from being selected. A failure during tier 2 detection must never prevent tier 3 from being selected. Detection code must not trigger class-initialization failures.

R23. Tier 2 code that references `jdk.incubator.vector` classes must be isolated in a separate internal class that is loaded only during tier detection. The `module-info.java` for `jlsm.core` must NOT add a hard `requires jdk.incubator.vector` dependency. A `requires static jdk.incubator.vector` clause (or equivalent optional dependency mechanism) must be used so that the Vector API is optional. If Vector API classes are unavailable at runtime, tier detection must fall through to tier 3.

R24. The parser must throw `JsonParseException` (a new unchecked exception type in the jlsm-core JSON package) for all parse failures. The exception must include the byte offset of the error and a description of what was expected versus what was found.

R25. The parser must correctly handle all JSON value types as defined by RFC 8259: objects, arrays, strings (with all escape sequences including `\uXXXX` surrogate pairs), numbers (integer and floating-point, including exponent notation), `true`, `false`, and `null`. The parser is intentionally stricter than RFC 8259 in the following ways: duplicate keys are rejected (R11), and trailing content after a complete value is rejected (R28).

R26. The parser must accept and correctly parse documents containing any valid Unicode content, including supplementary characters represented as surrogate pairs in `\uXXXX` escapes.

R27. The parser must handle empty objects `{}`, empty arrays `[]`, and nested structures up to a configurable maximum depth (default: 256). Inputs exceeding the maximum depth must be rejected with a `JsonParseException` indicating the depth limit was reached. The parser must not use recursion for depth traversal.

R28. The parser must reject trailing content after a complete JSON value (e.g., `{"a":1}garbage`) with a `JsonParseException`.

R29. The parser must detect and report truncated input (unexpected end of input) with a `JsonParseException` indicating the parse was incomplete.

### JSONL streaming (jlsm-core)

R30. A `JsonlReader` must read JSON Lines from an `InputStream`, producing one `JsonValue` per non-empty line. Blank lines (empty or whitespace-only) must be skipped.

R31. `JsonlReader` must support a `stream()` method returning `Stream<JsonValue>`, enabling composition with standard `Stream` operations (map, filter, collect, etc.).

R32. `JsonlReader` must not accumulate data across lines. After processing each line and delivering the corresponding `JsonValue` to the caller, all input buffers and intermediate state for that line must be eligible for garbage collection. The memory required per line is proportional to the size of that line's JSON content, not to the total stream size.

R33. `JsonlReader` must support two error-handling modes, selectable at construction: fail-fast (throw on first malformed line) and skip-on-error (skip malformed lines and continue to the next).

R34. In skip-on-error mode, `JsonlReader` must report skipped lines via a caller-provided error handler (`Consumer<JsonlReader.ParseError>` or equivalent) that includes the line number and error details.

R35. `JsonlReader` must implement `AutoCloseable`. Closing the reader must close the underlying input stream.

R36. A `JsonlWriter` must write `JsonValue` instances to an `OutputStream`, one compact JSON value per line followed by a newline character (`\n`). Any `JsonValue` subtype (`JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`) must be accepted.

R37. `JsonlWriter` must not buffer an unbounded number of writes. Each `write(JsonValue)` call must produce output (subject to the underlying stream's buffering).

R38. `JsonlWriter` must implement `AutoCloseable`. Closing the writer must flush pending output and close the underlying output stream.

R39. `JsonlWriter` must reject null `JsonValue` arguments with a `NullPointerException`.

### Module boundary

R40. The JSON value types (`JsonValue`, `JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`), the JSON parser, `JsonParseException`, `JsonlReader`, and `JsonlWriter` must reside in an exported package of the `jlsm.core` module.

R41. The `jlsm.core` `module-info.java` must export the new JSON package.

R42. `JlsmDocument.fromJson(String, JlsmSchema)` in `jlsm-table` must use the `jlsm-core` JSON parser internally. The public API signature must not change.

R43. `JlsmDocument.toJson()` and `toJson(boolean)` in `jlsm-table` must use the `jlsm-core` JSON writer infrastructure internally. The public API signature must not change.

R44. The adaptation from `JsonValue` to `JlsmDocument` field values (used by `fromJson`) must apply the same type validation and range checking as the current `JlsmDocument.of()` factory. Numeric narrowing (e.g., JSON number to INT8) must reject out-of-range values with `IllegalArgumentException`. JSON `null` must map to an absent field (null in the values array). JSON types that do not match the schema field type must be rejected with a descriptive error including the field name, expected type, and actual JSON type.

### Cross-spec amendments

R45. This spec supersedes F14.R48 (`toYaml()`) and F14.R49 (`fromYaml()`). Those requirements are invalidated by this feature.

R46. F14.R45 (`toJson()`), F14.R46 (`toJson(boolean)`), and F14.R47 (`fromJson()`) remain valid but their implementation must delegate to the `jlsm-core` JSON infrastructure introduced by this spec.

---

## Design Narrative

### Intent

Consolidate JSON processing as a first-class, high-performance capability in `jlsm-core`, remove the YAML alternative, and add JSONL streaming for bulk import/export. The SIMD on-demand parser brings near-native parsing throughput to the pure-Java library. The JSON value types provide a composable foundation for schema-free JSON manipulation that can be extended to schema-aware document conversion.

### Why this approach

The SIMD on-demand two-stage architecture is well-researched (KB: `algorithms/serialization/` — 3 entries covering architecture, Panama FFM, and fallback strategy) and proven by simdjson. Moving JSON to `jlsm-core` makes it available to all modules, not just `jlsm-table`. YAML removal simplifies the codebase and eliminates a format that adds maintenance cost without proportional value for a pre-1.0 library. JSONL streaming with `Stream<JsonValue>` provides natural Java composability for import/export pipelines.

The three-tier fallback strategy ensures the parser works everywhere while maximizing performance on capable hardware. The key architectural insight from the KB research: carry-less multiplication (PCLMULQDQ/PMULL) is the only operation requiring Panama FFM; all other SIMD operations work through the Vector API. This isolates the native access requirement to a single code path.

The `requires static` approach for `jdk.incubator.vector` prevents the incubator dependency from propagating to all `jlsm-core` consumers. Only applications that want SIMD acceleration need the flag.

### What was ruled out and why

- **Keeping YAML as deprecated:** Pre-1.0 library with no external consumers; deprecation adds maintenance cost for no benefit. Clean removal.
- **Formal JSON Schema validation (draft-07/2020-12):** Deferred to a future feature; the value types are designed to support it later.
- **Schema-aware JSONL streaming (`Stream<JlsmDocument>`):** Would couple the streaming API to `jlsm-table`. Instead, `Stream<JsonValue>.map(...)` provides the same capability without the coupling. The adaptation layer in `jlsm-table` handles schema-typed conversion.
- **SAX-style event parser:** The on-demand model provides better ergonomics (value-level access vs event handling) with comparable memory characteristics.
- **Hard `requires jdk.incubator.vector` on jlsm-core:** Would force `--add-modules` flag on all consumers, even those never using JSON parsing. Unacceptable for a core dependency module.
- **Recursive descent parser:** Violates project coding guidelines (bounded iteration, no recursion). Iterative state machine with explicit depth tracking is required.

### Known limitations

- Number equality uses `BigDecimal.compareTo()`, meaning `1.0` and `1.00` are equal but `1.0` and `1.0e0` are also equal. This matches mathematical equality but may surprise callers who expect text-level distinction. The original text is always available via `asNumberText()`.
- The "constant memory" property of JSONL streaming holds only when the caller processes elements sequentially. Collecting the stream into a list (`stream().toList()`) will hold all values in memory — this is the caller's responsibility, not the reader's.
- The parser is intentionally stricter than RFC 8259: duplicate keys and trailing content are rejected. This prevents silent data loss but may reject documents accepted by more permissive parsers.
