---
{
  "id": "serialization.simd-jsonl",
  "version": 3,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "serialization"
  ],
  "requires": [
    "F13",
    "F14"
  ],
  "invalidates": [
    "F14.R48",
    "F14.R49"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [
    "algorithms/serialization/simd-on-demand-serialization",
    "algorithms/serialization/panama-ffm-inline-machine-code",
    "algorithms/serialization/simd-serialization-java-fallbacks"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F15"
  ]
}
---
# serialization.simd-jsonl — JSON-Only SIMD On-Demand Parser with JSONL Streaming

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

R47. `JsonPrimitive.ofNumber(String)` must reject text that is not a valid JSON number at construction time, throwing `NumberFormatException`. Deferred validation (accepting arbitrary text and failing only at accessor time) is not permitted.

R9. `JsonObject` must provide Map-like access to its members: `get(String key)` returning `JsonValue`, `containsKey(String key)`, `keys()` returning a `Set<String>`, `size()`, `entrySet()`, and `getOrDefault(String key, JsonValue defaultValue)`.

R10. `JsonObject` must preserve insertion order of keys. Iterating `keys()` or `entrySet()` must return entries in the order they were added.

R11. `JsonObject` must reject duplicate keys at construction. Whether built from parsing or programmatic construction, adding a key that already exists must be rejected with `IllegalArgumentException`.

R12. `JsonArray` must provide List-like access to its elements: `get(int index)` returning `JsonValue`, `size()`, and `stream()` returning `Stream<JsonValue>`.

R13. All `JsonValue` implementations must be deeply immutable after construction. Internal collections must be defensively copied at construction time or wrapped with unmodifiable views. No reference to mutable state held by the caller may be retained.

R14. `JsonObject` and `JsonArray` must implement `equals()` and `hashCode()` based on structural deep equality of their contents. `JsonPrimitive` equality for numbers must be based on numeric value (using `BigDecimal.compareTo() == 0`), not on text representation. Two number primitives representing the same mathematical value must be equal regardless of their original text form.

R15. `JsonObject` must be constructable only via a static factory method or builder that validates all keys are non-null, non-blank strings and all values are non-null `JsonValue` instances. `JsonArray` must be constructable only via a static factory method that validates all elements are non-null `JsonValue` instances. Direct public constructors that bypass validation must not exist.

R48. `JsonObject.Builder` must enforce single-use semantics: after `build()` is called, subsequent calls to `put()` or `build()` must throw `IllegalStateException`. The builder must not be reusable.

### On-demand JSON parser (jlsm-core)

R16. The JSON parser must implement a two-stage architecture internally: stage 1 builds a structural index of all token positions in the input, stage 2 materializes values from the indexed positions.

R17. The public `parse(String)` method must return a fully materialized, self-contained `JsonValue` tree that has no references to the original input buffer or structural index. The implementation uses the two-stage architecture internally to perform the materialization.

R18. Stage 1 structural indexing must classify characters into structural categories (commas, colons, brackets/braces, whitespace, quotes) using SIMD vector operations when the runtime tier supports it.

R19. Stage 1 must correctly identify quoted string regions by computing the parity of consecutive backslash runs before each quote character. A quote preceded by an even number of backslashes (including zero) is a structural quote; a quote preceded by an odd number is escaped. The backslash-parity computation must handle runs of arbitrary length.

R49. Stage 1 structural indexing must carry backslash-parity state across processing block boundaries. A backslash run at the end of one block must affect escape classification of the first byte(s) of the next block and the scalar tail.

R20. The parser must implement three processing tiers with automatic runtime detection: Tier 1 (Panama FFM with PCLMULQDQ/PMULL for carry-less multiply quote masking), Tier 2 (Vector API for character classification and a shift-XOR cascade for prefix-XOR), Tier 3 (pure scalar byte-by-byte processing).

R21. Tier detection must occur once at class-load time and the result must be stored in a `static final` field so the JIT can constant-fold the dispatch.

R22. Tier detection must catch all exceptions from native access probing (including `IllegalCallerException`, `UnsupportedOperationException`, `UnsatisfiedLinkError`, and `NoClassDefFoundError`) and fall through to the next tier. A failure during tier 1 detection must never prevent tier 2 or tier 3 from being selected. A failure during tier 2 detection must never prevent tier 3 from being selected. Detection code must not trigger class-initialization failures.

R23. Tier 2 code that references `jdk.incubator.vector` classes must be isolated in a separate internal class that is loaded only during tier detection. The `module-info.java` for `jlsm.core` must NOT add a hard `requires jdk.incubator.vector` dependency. A `requires static jdk.incubator.vector` clause (or equivalent optional dependency mechanism) must be used so that the Vector API is optional. If Vector API classes are unavailable at runtime, tier detection must fall through to tier 3.

R24. The parser must throw `JsonParseException` (a new unchecked exception type in the jlsm-core JSON package) for all parse failures. The exception must include the byte offset of the error and a description of what was expected versus what was found.

R50. `JsonParseException` constructors must reject null message arguments with `NullPointerException`.

R25. The parser must correctly handle all JSON value types as defined by RFC 8259: objects, arrays, strings (with all escape sequences including `\uXXXX` surrogate pairs), numbers (integer and floating-point, including exponent notation), `true`, `false`, and `null`. The parser is intentionally stricter than RFC 8259 in the following ways: duplicate keys are rejected (R11), trailing content after a complete value is rejected (R28), and blank (empty or whitespace-only) keys are rejected (R15).

R51. The JSON writer must escape lone surrogate characters (U+D800-U+DFFF) as `\uXXXX` sequences. Lone surrogates in Java strings must not be written as raw UTF-8, which would produce invalid output.

R26. The parser must accept and correctly parse documents containing any valid Unicode content, including supplementary characters represented as surrogate pairs in `\uXXXX` escapes.

R27. The parser must handle empty objects `{}`, empty arrays `[]`, and nested structures up to a configurable maximum depth (default: 256). Inputs exceeding the maximum depth must be rejected with a `JsonParseException` indicating the depth limit was reached. The parser must not use recursion for depth traversal.

R52. The configurable maximum depth parameter must have both a lower bound (at least 1) and an upper bound (no greater than 4096). Values outside this range must be rejected with `IllegalArgumentException` at parse time.

R28. The parser must reject trailing content after a complete JSON value (e.g., `{"a":1}garbage`) with a `JsonParseException`.

R29. The parser must detect and report truncated input (unexpected end of input) with a `JsonParseException` indicating the parse was incomplete.

R59. The JSON writer must detect integer overflow when computing indentation width (`level * spacesPerLevel`) and fail with an exception rather than producing silently incorrect output.

### JSONL streaming (jlsm-core)

R30. A `JsonlReader` must read JSON Lines from an `InputStream`, producing one `JsonValue` per non-empty line. Blank lines (empty or whitespace-only) must be skipped.

R31. `JsonlReader` must support a `stream()` method returning `Stream<JsonValue>`, enabling composition with standard `Stream` operations (map, filter, collect, etc.).

R53. `JsonlReader.stream()` must be callable at most once per reader instance. A second invocation must throw `IllegalStateException`. This prevents creation of competing readers on the same underlying stream.

R32. `JsonlReader` must not accumulate data across lines. After processing each line and delivering the corresponding `JsonValue` to the caller, all input buffers and intermediate state for that line must be eligible for garbage collection. The memory required per line is proportional to the size of that line's JSON content, not to the total stream size.

R33. `JsonlReader` must support two error-handling modes, selectable at construction: fail-fast (throw on first malformed line) and skip-on-error (skip malformed lines and continue to the next).

R34. In skip-on-error mode, `JsonlReader` must report skipped lines via a caller-provided error handler (`Consumer<JsonlReader.ParseError>` or equivalent) that includes the line number and error details.

R35. `JsonlReader` must implement `AutoCloseable`. Closing the reader must close the underlying input stream.

R54. `JsonlReader.close()` must close all internal resources created by `stream()`, including any intermediate buffered reader. Resources must be closed using the deferred exception pattern (close all, propagate first exception).

R36. A `JsonlWriter` must write `JsonValue` instances to an `OutputStream`, one compact JSON value per line followed by a newline character (`\n`). Any `JsonValue` subtype (`JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`) must be accepted.

R37. `JsonlWriter` must not buffer an unbounded number of writes. Each `write(JsonValue)` call must produce output (subject to the underlying stream's buffering).

R38. `JsonlWriter` must implement `AutoCloseable`. Closing the writer must flush pending output and close the underlying output stream.

R55. `JsonlWriter.close()` must be idempotent. A second call to `close()` must not throw an exception or perform redundant I/O operations.

R56. `JsonlWriter.close()` must release the underlying output stream even when `flush()` throws an exception. The flush exception must propagate to the caller, but stream closure must not be skipped.

R39. `JsonlWriter` must reject null `JsonValue` arguments with a `NullPointerException`.

### Module boundary

R40. The JSON value types (`JsonValue`, `JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`), the JSON parser, `JsonParseException`, `JsonlReader`, and `JsonlWriter` must reside in an exported package of the `jlsm.core` module.

R41. The `jlsm.core` `module-info.java` must export the new JSON package.

R42. `JlsmDocument.fromJson(String, JlsmSchema)` in `jlsm-table` must use the `jlsm-core` JSON parser internally. The public API signature must not change.

R43. `JlsmDocument.toJson()` and `toJson(boolean)` in `jlsm-table` must use the `jlsm-core` JSON writer infrastructure internally. The public API signature must not change.

R44. The adaptation from `JsonValue` to `JlsmDocument` field values (used by `fromJson`) must apply the same type validation and range checking as the current `JlsmDocument.of()` factory. Numeric narrowing (e.g., JSON number to INT8) must reject out-of-range values with `IllegalArgumentException`. JSON `null` must map to an absent field (null in the values array). JSON types that do not match the schema field type must be rejected with a descriptive error including the field name, expected type, and actual JSON type.

R57. FLOAT64 inbound adaptation must reject non-finite values (NaN, positive infinity, negative infinity) with `IllegalArgumentException`, consistent with the FLOAT16 and FLOAT32 adaptation paths.

R58. Numeric type adaptation from JSON to schema fields (INT8, INT16, INT32) must distinguish overflow from format errors by providing distinct error messages. Overflow of a valid integer must produce an "out of range" error; non-integer text must produce a "not a valid integer" error.

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

---

## Verification Notes

### Verified: v2 — 2026-04-16

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `JlsmDocument.java` has no `toYaml()`/`fromYaml()` methods |
| R2 | SATISFIED | no `YamlParser`/`YamlWriter` in main source tree (only in `.claude/worktrees/audit-v2/` snapshot — not part of build) |
| R3 | SATISFIED | no YAML references in test sources |
| R4 | SATISFIED | `JsonValue.java:28` — sealed, permits exactly 4 subtypes |
| R5 | SATISFIED | `JsonNull.java:12-16` — enum with single INSTANCE |
| R6 | SATISFIED | `JsonPrimitive.java:32-36, 97-109` — Kind enum + `isString/isNumber/isBoolean` |
| R7 | SATISFIED | `JsonPrimitive.java:118-123, 132-137` — ISE on wrong kind |
| R8 | SATISFIED | `JsonPrimitive.java:146-195` — `asNumberText` returns stored text; `asInt` uses `Integer.parseInt` (rejects `"1e2"`); `asLong/asDouble/asBigDecimal` all parse on each call |
| R47 | SATISFIED | `JsonPrimitive.java:78-82` — `new BigDecimal(rawText)` at construction, rethrows NFE |
| R9 | SATISFIED | `JsonObject.java:87-142` — all required accessors present |
| R10 | SATISFIED | `JsonObject.java:52, 173` — `LinkedHashMap` preserves order |
| R11 | SATISFIED | `JsonObject.java:195-197` Builder; `JsonParser.java:193-195` parser; `JsonObject.of` uses LinkedHashMap so a `Map` with duplicates can't reach it |
| R12 | SATISFIED | `JsonArray.java:81-101` — `get`, `size`, `stream` |
| R13 | SATISFIED | `JsonObject.java:59` / `JsonArray.java:47` — `Collections.unmodifiableMap` / `List.copyOf` |
| R14 | SATISFIED | `JsonObject.java:144-156` / `JsonArray.java:103-115` — delegates to collection equals; `JsonPrimitive.java:197-234` uses `BigDecimal.compareTo == 0` for numbers |
| R15 | PARTIAL | static factories/builder enforce non-null keys/values and use private constructors. Gap: spec requires "non-null, **non-blank** strings" but `JsonObject.of` (`:51-58`) and `JsonObject.Builder.put` (`:193-200`) only check null via `Objects.requireNonNull` — a blank (`""` or whitespace-only) key is currently accepted |
| R48 | SATISFIED | `JsonObject.java:189-192, 208-211` — `built` flag throws ISE on second `put`/`build` |
| R16 | SATISFIED | `JsonParser.java:78-82` — stage 1 via `StructuralIndexer`, stage 2 via `Materializer` |
| R17 | SATISFIED | `JsonParser.java:82-90` — returned `JsonValue` holds only deeply-copied data; no references to input or struct index |
| R18 | SATISFIED | `StructuralIndexer.java:26-30` dispatches to Panama/Vector/Scalar scanners |
| R19 | SATISFIED | `ScalarStage1` and `VectorStage1` track `skipNext` for backslash escaping |
| R49 | SATISFIED | `VectorStage1.java:18, 54-115` — `skipNext` carries across chunk and tail boundaries |
| R20 | SATISFIED | `TierDetector.java:22-30` — three tier constants; `PanamaStage1`/`VectorStage1`/`ScalarStage1` implementations present |
| R21 | SATISFIED | `TierDetector.java:36` — `public static final int TIER = detectTier()` |
| R22 | SATISFIED | `TierDetector.java:57, 73` — `catch (Throwable _)` fall-through between tiers |
| R23 | SATISFIED | `module-info.java:2` — `requires static jdk.incubator.vector` (optional); `VectorStage1` isolated in `internal` package, loaded only via `Class.forName` during detection |
| R24 | SATISFIED | `JsonParseException.java:14` extends RuntimeException, carries `offset` |
| R50 | SATISFIED | `JsonParseException.java:51` — `Objects.requireNonNull(message, ...)` |
| R25 | SATISFIED | `JsonParser.java:130-140` switch-dispatch handles objects/arrays/strings/numbers/true/false/null |
| R51 | SATISFIED | `JsonWriter.java:200-202` — `Character.isSurrogate(c)` → `\uXXXX` escape |
| R26 | SATISFIED | `JsonParser.java:314-330` — high/low surrogate pair handling |
| R27 | **VIOLATED** | `JsonParser.java:205` — `parseObject` calls `parseValue` which calls `parseObject`/`parseArray`, i.e. mutual recursion. Code comment at `:153-156` explicitly acknowledges: *"To truly satisfy the 'no recursion' spec, we implement objects and arrays with an explicit stack-based approach... But the simplest correct approach that satisfies maxDepth bounds is this bounded mutual recursion."* Bounded recursion + R52's `[1, 4096]` cap keeps stack depth finite (~12k frames at the limit), but the spec is categorical: "must not use recursion for depth traversal" |
| R52 | SATISFIED | `JsonParser.java:35, 67-70` — `MAX_DEPTH_LIMIT = 4096`; IAE on `maxDepth <= 0 \|\| maxDepth > 4096` |
| R28 | SATISFIED | `JsonParser.java:85-88` — post-parse whitespace skip then trailing-content check |
| R29 | SATISFIED | `JsonParser.java:125-127, 168-170, 239-241` — `pos >= input.length` checks raise JsonParseException |
| R59 | SATISFIED | `JsonWriter.java:213` — `Math.multiplyExact(level, spacesPerLevel)` detects overflow |
| R30 | SATISFIED | `JsonlReader.java:131-162` — reads one value per non-blank line; blank lines skipped at `:143-145` |
| R31 | SATISFIED | `JsonlReader.java:115-166` — `stream()` returns `Stream<JsonValue>` via Spliterator |
| R53 | SATISFIED | `JsonlReader.java:116-120` — `streamCalled` flag throws ISE on second call |
| R32 | SATISFIED | Spliterator processes one line at a time; no cross-line buffering in `tryAdvance` |
| R33 | SATISFIED | `JsonlReader.java:52-57, 151-160` — `FAIL_FAST`/`SKIP_ON_ERROR` modes |
| R34 | SATISFIED | `JsonlReader.java:66-73, 157` — `ParseError(lineNumber, line, cause)` passed to callback |
| R35 | SATISFIED | `JsonlReader.java:47` implements `AutoCloseable`; `:174-182` closes underlying input |
| R54 | SATISFIED | `JsonlReader.java:175-181` — `try/finally` closes `buffered` then `input` (deferred-exception pattern) |
| R36 | SATISFIED | `JsonlWriter.java:47-53` — compact write + `\n` + flush |
| R37 | SATISFIED | no internal buffering beyond `output.write/flush` — each call produces output |
| R38 | SATISFIED | `JsonlWriter.java:64-74` — flush then close |
| R55 | SATISFIED | `JsonlWriter.java:65-68` — `closed` flag short-circuits second call |
| R56 | SATISFIED | `JsonlWriter.java:69-73` — `try(flush)/finally(close)` ensures close even on flush failure |
| R39 | SATISFIED | `JsonlWriter.java:48` — `Objects.requireNonNull(value, ...)` |
| R40 | SATISFIED | all types in `jlsm.core.json` package |
| R41 | SATISFIED | `module-info.java:25` — `exports jlsm.core.json` |
| R42 | SATISFIED | `JlsmDocument.java:409-410` — `JsonParser.parse(json)` + `JsonValueAdapter.fromJsonValue` |
| R43 | SATISFIED | `JlsmDocument.java:381-393` — `JsonWriter.write(...)` via `JsonValueAdapter.toJsonValue` |
| R44 | SATISFIED | `JsonValueAdapter.java:211-263` — per-field type check + range check with descriptive errors |
| R57 | SATISFIED | `JsonValueAdapter.java:311-320` — `Double.isFinite()` check rejects NaN/±Infinity |
| R58 | SATISFIED | `JsonValueAdapter.java:216-224, 231-239, 247-255` — distinct "out of range" vs "not a valid integer" messages |
| R45 | SATISFIED | front matter `invalidates: ["F14.R48", "F14.R49"]` |
| R46 | SATISFIED | `JlsmDocument.toJson/fromJson` at `:381-410` delegate to `jlsm-core` JSON infrastructure |

**Overall: FAIL**

Obligations resolved: 0
Obligations remaining: 1 (newly created for R27)
Undocumented behavior:
- `JsonObject.of(Map)` uses `LinkedHashMap` to back the defensive copy, which preserves insertion order. This is relied on for R10 but isn't explicitly documented — a `SortedMap` input would silently be reordered by iteration, and that's fine here, but worth calling out in javadoc.
- `JsonlReader.close()` is declared `throws Exception` because the signature inherits from `AutoCloseable`; the underlying implementation only throws `IOException`. Narrowing the throws clause would be a small API improvement.
- `JsonPrimitive.equals` for numbers has a `try/catch` fall-back to raw text comparison if `BigDecimal` can't parse either side (`:212-214`). Given R47 enforces parseability at construction, this catch block is structurally unreachable. Either codify the invariant (remove the catch + assert) or document why the defensive path is kept.

### Verified: v3 — 2026-04-16

Re-verification after R15 and R27 repairs.

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1–R14, R16–R26, R28–R58 | SATISFIED | unchanged from v2 |
| R15 | SATISFIED | `JsonObject.java:53-62, 193-199` — both `JsonObject.of(Map)` and `Builder.put` now reject blank keys via `isBlank()` with `IllegalArgumentException`. Regression tests `JsonObjectTest.ofRejectsEmptyKey`, `ofRejectsWhitespaceOnlyKey`, `builderRejectsEmptyKey`, `builderRejectsWhitespaceOnlyKey`. |
| R25 | SATISFIED (amended) | text now lists three stricter-than-RFC behaviours: duplicate keys (R11), trailing content (R28), blank keys (R15). |
| R27 | SATISFIED | `JsonParser.java:134-173` — `parseValue()` now uses an explicit `Deque<Frame>` stack; `parseObject()` / `parseArray()` / `currentDepth()` removed. Depth is tracked by `stack.size()`; JVM call stack depth is O(1) regardless of input nesting depth. Regression test `JsonParserTest.Errors.deepNestingAtMaxDepthDoesNotOverflowStack` runs the parser on a 128 KiB-stack worker thread at `maxDepth=4096` — the old mutual-recursion implementation consumed ~12k frames and would overflow that stack; the iterative implementation completes cleanly. |

**Overall: PASS**

#### Amendments
- **R25**: updated enumeration of stricter-than-RFC behaviours to include blank-key rejection alongside duplicate-key and trailing-content rejection. Unchanged otherwise.

Amendments applied: 1 (R25 enumeration update)
Code fixes applied: 2 (R15 blank-key enforcement in `JsonObject.of` and `Builder.put`; R27 full rewrite of Materializer's container-parsing logic from mutual recursion to an explicit frame stack, with `currentDepth()` deleted in favour of `stack.size()`)
Regression tests added: 5 — `ofRejectsEmptyKey`, `ofRejectsWhitespaceOnlyKey`, `builderRejectsEmptyKey`, `builderRejectsWhitespaceOnlyKey`, `deepNestingAtMaxDepthDoesNotOverflowStack`.
Existing tests updated: 4 — `JsonObjectTest` lost the three "accepts blank key" tests (added under F-R1.cb.4.4) and `core/json/ContractBoundariesAdversarialTest` lost the three "accepts blank key" adversarial tests (superseded note left in place); `test_Materializer_contractBoundary_advancePastStructuralValidatesExpectedChar` was retargeted to invoke `advancePastStructural` directly via reflection after `parseObject`'s removal.
Obligations resolved: 1 (`OBL-F15-R27`)
Obligations remaining: 0
Undocumented behavior: none new.

#### R15 override note
The F-R1.cb.4.4 audit had previously *relaxed* blank-key rejection in an effort to match RFC 8259. The spec text always required non-blank keys. v3 explicitly prioritises the spec requirement over RFC conformance: jlsm is stricter than RFC 8259 in three ways, and R25 now enumerates all three. Callers migrating JSON from other libraries that write empty-string keys will hit the new rejection — this is by design.
