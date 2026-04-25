---
title: "Error-Message Info-Flow Discipline — Redact File-Shape Diagnostics"
aliases:
  - "exception-message redaction"
  - "format-version byte leak in IOException"
  - "structural validator info-flow"
  - "redact offending bytes in exception"
type: adversarial-finding
topic: "patterns"
category: "validation"
tags:
  - "information-leak"
  - "exception-messages"
  - "redaction"
  - "encryption"
  - "format-validation"
  - "footer-parsing"
  - "envelope-codec"
  - "CWE-209"
  - "info-flow"
research_status: "active"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/TableCatalog.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/V6Footer.java"
related:
  - "patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md"
  - "patterns/validation/version-discovery-self-only-no-external-cross-check.md"
  - "patterns/validation/dispatch-discriminant-corruption-bypass.md"
  - "patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag.md"
  - "systems/security/sstable-block-level-ciphertext-envelope.md"
decision_refs: []
source_audit: "implement-encryption-lifecycle--wd-02"
sources:
  - url: "https://cwe.mitre.org/data/definitions/209.html"
    title: "CWE-209 — Generation of Error Message Containing Sensitive Information"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://cwe.mitre.org/data/definitions/532.html"
    title: "CWE-532 — Insertion of Sensitive Information into Log File"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://owasp.org/www-community/Improper_Error_Handling"
    title: "OWASP — Improper Error Handling"
    accessed: "2026-04-25"
    type: "docs"
  - title: "F-R1.contract_boundaries.2.3 — TableCatalog.readMetadata leaked offending format-version byte"
    accessed: "2026-04-25"
    type: "audit-finding"
---

# Error-Message Info-Flow Discipline — Redact File-Shape Diagnostics

## summary

Error messages thrown from format-validation paths — catalog loaders,
SSTable footer parsers, envelope codecs, version dispatchers — routinely
embed the offending byte value, format-version byte, length-prefix value,
or other file-shape diagnostics to aid debugging. In an encrypted system,
this is an information-flow leak: even when the exception is caught and
logged at a higher layer, structural metadata derived from a tampered or
malformed file becomes observable to anyone with log access. The
defensive discipline is to redact the offending byte's numeric value,
identify the file (path), describe the failure category in canonical
terms ("unknown format version", "version-not-found", "corrupt scope
section"), and let downstream tooling consult the actual file bytes if
operator-level investigation is needed. This sits alongside the
well-established key-material redaction discipline and extends it to
format/structural bytes that travel inside exception messages instead of
log lines directly.

## problem

A typical loader is written for the operator's debugging convenience:

```java
private Metadata readMetadata(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    byte version = bytes[0];
    return switch (version) {
        case 1, 2, 3, 4 -> readLegacy(version, bytes);
        case 5 -> readV5(bytes);
        case 6 -> readV6(bytes);
        default -> throw new IOException(
            "unknown format version 0x%02X at %s".formatted(version, path));
    };
}
```

Three failure modes follow:

1. **Format-version oracle.** An attacker who can submit malformed files
   to the catalog (a multi-tenant catalog accepting tenant-supplied
   metadata, a backup-restore path, an unsealed S3 prefix) can probe
   which format versions the deployment recognizes by reading the
   exception messages from logs. The message confirms or denies the byte
   they wrote. Combined with version-specific feature detection elsewhere
   in the system, this enables capability fingerprinting.
2. **File-shape diagnostic leak.** Length-prefix values, offset bytes,
   record counts — every numeric the validator extracts from an
   untrusted file becomes a potential probe channel. `IOException("expected
   16 records, got %d")` lets an attacker iterate length values and read
   back the count; on a system that segments format-version-N files into
   N records, this leaks the format-version indirectly.
3. **Cross-tenant information flow.** A multi-tenant catalog where
   tenant T1's malformed file produces an exception logged into a shared
   operator log readable by tenant T2's support staff: T2 learns about
   T1's file shape. (CWE-209 + CWE-532 in combination.)

The leak does not require an attacker; ordinary observability — APM
captures of exception messages, log shipping to third-party SaaS, support
bundles included in customer-facing tickets — sufficiently propagates the
file-shape data to violate the deployment's information-flow boundary.

The pattern is structurally identical to key-material redaction (well
established): a `byte[] keyBytes` is never `String.format(... keyBytes ...)`
into an exception. The extension this pattern adds: file-shape bytes
deserve the same treatment, even though the sensitivity is less obvious.

## symptoms

- An adversarial test crafts a file with an unrecognized format version
  byte (e.g., `0xFE`), invokes the loader, and asserts that the
  exception message does not contain the substring `"FE"`, `"0xfe"`,
  `"0xFE"`, `"254"`, or any rendering of the byte's numeric value.
  Naive impl fails because the message contains `"unknown format
  version 0xFE"`.
- A second test asserts the message *does* contain (a) the file path or
  a stable identifier, and (b) a canonical failure category like
  `"unknown format version"` (not the offending value, not the version
  byte location).
- An audit's contract_boundaries lens flags the construct because the
  exception message embeds an untrusted-input-derived field.
- Production logs ingested into a SaaS observability platform show
  format-version bytes from tenant files in shared dashboards.

## root-cause

Three cooperating misconceptions:

- **"Exceptions are for the operator."** Authors imagine that exception
  messages are read by the engineer debugging a corrupt file, who
  benefits from seeing the offending byte. They do not picture the same
  message being captured by an APM tool, shipped to a vendor, included
  in a customer-facing error response, or read by another tenant's
  support engineer.
- **"Format-version bytes are not sensitive."** Compared to key
  material, structural bytes feel obviously safe. But they are
  *attacker-derived* (the attacker chose what to write into the file)
  and *capability-revealing* (they confirm or deny the version-set the
  validator recognizes). Both properties make them sensitive in the
  cross-tenant or untrusted-tenant model.
- **"`%s` formatting is fine for an IOException message."** Every
  `String.format` call is a potential channel for untrusted-input
  reflection. Most calls in non-format-validation code are
  trusted-input-only; format-validation code by definition processes
  untrusted input.

## fix

A two-rule discipline at every structural validator boundary:

```java
private Metadata readMetadata(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    byte version = bytes[0];
    return switch (version) {
        case 1, 2, 3, 4 -> readLegacy(version, bytes);
        case 5 -> readV5(bytes);
        case 6 -> readV6(bytes);
        default -> throw new IOException(
            "unknown format version at " + path);   // category + identifier ONLY
    };
}
```

Rules for the fix:

1. **Redact the numeric value of any untrusted-input-derived field from
   the exception message.** Format-version byte, length prefix, offset,
   record count, magic bytes, scope-section byte count — all redacted.
2. **Include the canonical failure category and a stable identifier.**
   Category is one of a documented small set: "unknown format version",
   "version-not-found", "corrupt scope section", "checksum mismatch",
   "truncated record", "decode bounds violation". Identifier is the file
   `Path` (or a tenant-scoped identifier that does not leak across
   tenants). The category supports operator-level triage; the identifier
   supports operator-level investigation; together they replace the
   numeric-value diagnostic.
3. **Push numeric diagnostics into a structured, access-controlled
   channel.** If the operator genuinely needs the offending byte for
   forensic work, expose it through a debug-only logger
   (`logger.debug("format version byte at %s = 0x%02X", path, version)`)
   that is gated by the deployment's debug-logging policy, *not* through
   the exception. The exception is the cross-cutting channel; the debug
   log is the controlled channel.
4. **Apply the discipline at every structural validator.** Footer
   parsers, envelope codecs, scope-section decoders, dispatch
   discriminants. A test suite that audits exception-message contents
   for every documented exception shape is the only reliable enforcement.
5. **Document the redaction discipline in the validator's class-level
   contract.** Future maintainers will be tempted to add the byte back
   into the message during a debugging session; the inline doc gives the
   reviewer the rule to point at.

## verification

Two tests per validator:

1. **Negative-content assertion** — for each malformed-input variant,
   craft the input, invoke the validator, capture the IOException
   message, and assert that the message does *not* contain any rendering
   of the offending field's numeric value (decimal, hex, hex with `0x`
   prefix, hex without prefix, two-digit hex with leading zero,
   space-separated bytes).
2. **Positive-content assertion** — assert that the message *does*
   contain (a) the documented canonical category string, and (b) the
   file path or stable identifier.

A debug-channel verification is also useful: assert that
`logger.debug` was invoked with the offending byte at DEBUG level (so
the operator path remains intact) but that no INFO/WARN/ERROR
log invocation contains the byte.

## relationship to other patterns

- `defensive-copy-accessor-defeats-zeroize-on-close`: that pattern
  applies the same threat-model (sensitive bytes in heap memory) to
  byte-array fields. This pattern applies it to bytes that escape via
  exception messages.
- `version-discovery-self-only-no-external-cross-check`: that pattern
  addresses how a reader determines the format version (use external
  authority where available). This pattern addresses what happens to
  the version byte after the reader decides — it must not appear in
  exception messages even when the version-discovery itself is correct.
- `dispatch-discriminant-corruption-bypass`: that pattern addresses
  malformed magic bytes that bypass CRC. The exception thrown when CRC
  later fails must redact the dispatch-discriminant byte.
- `silent-fallthrough-integrity-defense-coupled-to-flag`: when an
  integrity defense fires, the exception describing why must redact the
  flag value and the byte that triggered the flag check.

## tradeoffs

**Strengths.** Eliminates the format-version oracle completely.
Aligns exception-message discipline with the key-material redaction
already required by R69 / CWE-316. Stable category strings make
log-aggregation rules far easier to write — a regex matching category
text is robust across releases, a regex matching numeric bytes is not.

**Weaknesses.** Operator debugging slows down marginally when the
exception message no longer points directly at the offending byte —
the operator must consult the file (with `xxd`, `hexdump`, or a debug
log) to learn the actual byte. For deployments with low-trust file
sources but trusted operators, this is fine; for deployments where
the operator-debugging budget is scarce, the debug-channel discipline
must be enforced rigorously to keep the data accessible. Inconsistent
application — message redacted in one validator, leaked in another —
is worse than uniform leak (the redacted ones are an oracle for
"feature implemented" while the leaky ones reveal raw data); the
discipline must be applied to *every* structural validator at once.

## when-to-apply

Every structural validator that processes untrusted file bytes and
throws an IOException describing the validation failure. Specifically:

- Catalog metadata loaders (`TableCatalog.readMetadata` and siblings).
- SSTable footer parsers (`V6Footer.parse`, legacy footer parsers).
- Envelope codecs (per-block ciphertext envelopes, WAL record envelopes).
- Compaction manifest readers, recovery log readers, schema migrators.
- Any deserialization path that accepts a file the producer did not
  fully trust.

**When not to apply.** Pure in-memory format validators where the input
came from a trusted in-process source (the rule still applies, but the
threat model is much weaker; documenting the trusted-source assumption
inline is a reasonable substitute for the redaction discipline).

## reference-implementation

`modules/jlsm-table/src/main/java/jlsm/table/internal/TableCatalog.java`
— `readMetadata` IOException messages now contain only category +
file path; the offending format-version byte is redacted from the
exception and emitted to the debug logger.

`modules/jlsm-core/src/main/java/jlsm/sstable/internal/V6Footer.java`
— `parse` IOException messages were checked manually during the WD-02
audit and found compliant with this discipline; the new pattern
documents the contract uniformly so future validators do not
re-introduce the leak.

Audit finding that surfaced the pattern:

- `F-R1.contract_boundaries.2.3` — `TableCatalog.readMetadata` leaked
  offending format-version byte value in IOException; fixed by
  redacting the byte and routing it to a DEBUG logger instead.

WD-02 ciphertext-format audit, 2026-04-25.

## sources

1. [CWE-209 — Generation of Error Message Containing Sensitive Information](https://cwe.mitre.org/data/definitions/209.html) — canonical weakness; the file-shape leak is a structural-byte instance of CWE-209.
2. [CWE-532 — Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html) — exception messages logged at ERROR level by default; redaction must happen at the throw site, not the log site.
3. [OWASP — Improper Error Handling](https://owasp.org/www-community/Improper_Error_Handling) — guidance: error messages should be useful to operators without disclosing implementation detail; the canonical-category-plus-identifier shape is consistent with OWASP's guidance.

---
*Researched: 2026-04-25 | Next review: 2026-07-25*
