---
title: "JVM Key Handling Patterns"
aliases: ["key management", "secret key security", "key zeroing", "KMS integration"]
topic: "systems"
category: "security"
tags: ["encryption", "key-management", "JVM", "security", "KMS"]
complexity:
  time_build: "N/A"
  time_query: "N/A"
  space: "O(1) per key — 32-64 bytes"
research_status: "stable"
last_researched: "2026-03-18"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmSchema.java"
related:
  - "systems/security/three-level-key-hierarchy.md"
  - "systems/security/encryption-key-rotation-patterns.md"
  - "systems/security/wal-encryption-approaches.md"
  - "systems/security/client-side-encryption-patterns.md"
sources:
  - url: "https://github.com/dbsystel/SecureSecretKeySpec"
    title: "SecureSecretKeySpec — AutoCloseable/Destroyable SecretKey"
    accessed: "2026-03-18"
    type: "repo"
  - url: "https://www.javacodegeeks.com/2025/07/securing-sensitive-data-in-java-applications-with-jep-411-foreign-function-memory-api.html"
    title: "Securing Sensitive Data with JEP 411 (Foreign Function & Memory API)"
    accessed: "2026-03-18"
    type: "blog"
  - url: "https://docs.oracle.com/javase/8/docs/technotes/guides/security/crypto/CryptoSpec.html"
    title: "Java Cryptography Architecture Reference Guide"
    accessed: "2026-03-18"
    type: "docs"
  - url: "https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/java-example-code.html"
    title: "AWS Encryption SDK for Java examples"
    accessed: "2026-03-18"
    type: "docs"
---

# JVM Key Handling Patterns

## summary

Holding encryption keys in JVM memory requires deliberate protections because
the garbage collector can copy objects across heap regions (leaving stale
copies), heap dumps expose all live objects, and `String` is immutable (cannot
be zeroed). Three strategies apply to jlsm: (1) **off-heap via Arena** — store
key material in `MemorySegment` allocated from a confined `Arena`, zero with
`fill((byte) 0)` before close; (2) **byte[] with manual zeroing** — use
`byte[]` for key material, `Arrays.fill(key, (byte) 0)` immediately after use,
accept that GC may have copied the array; (3) **scoped lifetime** — keys exist
only as long as the table is open, never stored in fields beyond the encryption
component. For KMS integration, the library should accept key material as
`byte[]` (the universal interchange format from AWS/GCP/Azure KMS clients) and
take ownership (zero the caller's copy after import).

## how-it-works

### threat-model

| Threat | Description | Mitigation |
|--------|-------------|------------|
| Heap dump | Attacker obtains JVM heap dump (e.g., via jmap, OOM trigger) | Key material in off-heap Arena (not in heap dump) |
| GC stale copies | GC relocates objects, leaving old copies in freed heap regions | Off-heap avoids GC entirely; byte[] zeroing reduces window |
| String immutability | `new String(keyBytes)` creates unzeroable copy | Never convert key bytes to String — use byte[] or MemorySegment |
| Log/exception leak | Key bytes printed in error messages, toString(), logs | Override toString(), sanitize exception messages, never log key fields |
| Process memory scan | Attacker reads /proc/pid/mem or equivalent | Off-heap + zeroing minimizes window; full protection requires OS-level hardening |

### key-lifetime-model

```
Caller provides byte[] key material
         │
         ▼
  ┌─────────────────────────┐
  │ Import: copy to Arena   │  ← caller's byte[] zeroed immediately
  │ (off-heap MemorySegment)│
  └──────────┬──────────────┘
             │
             ▼
  ┌─────────────────────────┐
  │ Table lifetime: key     │  ← used for encrypt/decrypt operations
  │ lives in Arena segment  │
  └──────────┬──────────────┘
             │
             ▼
  ┌─────────────────────────┐
  │ Table close: zero Arena │  ← segment.fill((byte) 0) then arena.close()
  │ segment then release    │
  └─────────────────────────┘
```

### key-parameters

| Parameter | Description | Recommended | Impact |
|-----------|-------------|-------------|--------|
| Storage type | Where key bytes live | Arena (off-heap) | Heap dump protection |
| Key lifetime | How long key is in memory | Table open → close | Minimizes exposure window |
| Zeroing strategy | When key bytes are overwritten | On close + on import (caller copy) | Reduces stale copies |
| KMS interchange format | How keys arrive from external systems | byte[] | Universal KMS client format |

## algorithm-steps

### off-heap-key-storage-with-arena (recommended for jlsm)

1. **Accept key material**: method signature takes `byte[] keyMaterial`
2. **Allocate off-heap**: `Arena arena = Arena.ofConfined()`,
   `MemorySegment keySegment = arena.allocate(keyMaterial.length)`
3. **Copy to off-heap**: `MemorySegment.copy(keyMaterial, 0, keySegment, JAVA_BYTE, 0, keyMaterial.length)`
4. **Zero caller's copy**: `Arrays.fill(keyMaterial, (byte) 0)`
5. **Use during table lifetime**: read key bytes from `keySegment` for crypto ops
6. **On table close**: `keySegment.fill((byte) 0)` then `arena.close()`

### byte-array-fallback (simpler, weaker)

1. **Accept key**: copy `byte[]` into a `private final byte[]` field
2. **Zero caller's copy**: `Arrays.fill(callerKey, (byte) 0)`
3. **On close**: `Arrays.fill(this.key, (byte) 0)`, set field to null
4. **Caveat**: GC may have relocated the byte[] during compaction — stale
   copy may remain in freed heap space until overwritten

### destroyable-secretkey-pattern

1. **Wrap in javax.crypto.SecretKey**: implement `Destroyable` interface
2. **destroy()**: zeros the internal byte[], sets `destroyed = true`
3. **getEncoded()**: throws `IllegalStateException` if destroyed
4. **AutoCloseable**: implement for try-with-resources compatibility
5. **Note**: `SecretKeySpec` does NOT implement `Destroyable` properly —
   `destroy()` throws `DestroyFailedException`. Must use custom impl.

## implementation-notes

### java-25-specific

jlsm already uses `Arena` and `MemorySegment` extensively (ArenaBufferPool,
SSTable I/O). The off-heap key storage pattern fits naturally:

```java
// Key holder using confined Arena — same pattern as ArenaBufferPool
final class EncryptionKeyHolder implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment keySegment;
    private volatile boolean closed;

    EncryptionKeyHolder(byte[] keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial must not be null");
        assert keyMaterial.length == 32 || keyMaterial.length == 64
            : "key must be 256 or 512 bits";
        this.arena = Arena.ofConfined();
        this.keySegment = arena.allocate(keyMaterial.length);
        MemorySegment.copy(keyMaterial, 0, keySegment,
            ValueLayout.JAVA_BYTE, 0, keyMaterial.length);
        Arrays.fill(keyMaterial, (byte) 0); // zero caller's copy
    }

    byte[] getKeyBytes() {
        assert !closed : "key holder is closed";
        return keySegment.toArray(ValueLayout.JAVA_BYTE);
        // Caller must zero this temporary copy after use
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            keySegment.fill((byte) 0);
            arena.close();
        }
    }
}
```

### kms-integration-model

KMS clients (AWS, GCP, Azure) all return key material as `byte[]`:

| KMS | Method | Returns |
|-----|--------|---------|
| AWS KMS | `decrypt().plaintext().asByteArray()` | `byte[]` |
| GCP KMS | `decrypt().getPlaintext().toByteArray()` | `byte[]` |
| Azure Key Vault | `unwrapKey().getKey()` | `byte[]` |

jlsm's API should accept `byte[]` — this is the universal interchange format.
The library takes ownership by copying to off-heap and zeroing the input.
The caller is responsible for obtaining and managing the key via their KMS.

**Envelope encryption pattern**: KMS encrypts a data encryption key (DEK).
The DEK is stored encrypted alongside the table metadata. On table open,
the caller decrypts the DEK via KMS and passes the plaintext `byte[]` to jlsm.
jlsm never interacts with KMS directly — clean separation of concerns.

### edge-cases-and-gotchas

- **SecretKeySpec is unsafe**: stores key bytes on heap, `destroy()` throws
  `DestroyFailedException`. Do not use for long-lived keys.
- **Arena.ofConfined() thread restriction**: confined arena can only be accessed
  from the creating thread. Use `Arena.ofShared()` if encryption ops happen on
  multiple threads (e.g., concurrent table reads). jlsm's existing
  `ArenaBufferPool` uses shared arenas — follow the same pattern.
- **JIT may optimize away zeroing**: in theory, the JIT compiler could eliminate
  `Arrays.fill(key, 0)` if it determines the array is unused afterward.
  `MemorySegment.fill()` is a native operation and less susceptible to this.
  For extra safety, use a volatile read after zeroing to prevent elimination.
- **Key rotation**: requires re-encrypting all data. Not a library concern —
  the caller closes the old table and opens a new one with the new key.
  Document this as a limitation.

## tradeoffs

### strengths

- **Off-heap Arena**: not visible in heap dumps, deterministic lifetime, native
  zeroing via `fill()`. Fits jlsm's existing Arena-based I/O architecture.
- **byte[] interchange**: universal KMS compatibility, zero library dependencies
  on any cloud SDK.
- **Scoped lifetime**: key exists only while the table is open — minimum
  exposure window.

### weaknesses

- **Off-heap still in process memory**: a process memory scan (not heap dump)
  can still find key bytes. No JVM-level defense against this — OS hardening
  (mlock, encrypted swap) is needed.
- **Confined Arena thread restriction**: limits concurrent access without
  switching to shared Arena (slightly weaker containment guarantees).
- **GC copies of byte[]**: the temporary `byte[]` returned by `getKeyBytes()`
  may be copied by GC before the caller zeros it. Minimize by keeping the
  byte[] in a local variable with tight scope.

## practical-usage

### when-to-use

- Any time caller-provided encryption keys are held in JVM memory
- Table-level encryption where keys persist for the table's lifetime
- Integration with external KMS systems (AWS, GCP, Azure)

### when-not-to-use

- Short-lived encryption operations where the key is used once and discarded
  immediately — byte[] with zeroing is sufficient
- If the threat model doesn't include heap dump attacks — simpler byte[]
  handling may be adequate

## code-skeleton

```java
// KMS integration pattern for jlsm table encryption
public class EncryptedTableBuilder {
    private byte[] dataEncryptionKey; // temporary, zeroed after import

    public EncryptedTableBuilder keyMaterial(byte[] dekBytes) {
        // Copy and take ownership — caller's array will be zeroed
        this.dataEncryptionKey = Arrays.copyOf(dekBytes, dekBytes.length);
        Arrays.fill(dekBytes, (byte) 0);
        return this;
    }

    public JlsmTable build() {
        try {
            EncryptionKeyHolder keyHolder = new EncryptionKeyHolder(dataEncryptionKey);
            // keyHolder zeroed dataEncryptionKey in its constructor
            return new JlsmTable(schema, keyHolder, ...);
        } finally {
            if (dataEncryptionKey != null) {
                Arrays.fill(dataEncryptionKey, (byte) 0);
                dataEncryptionKey = null;
            }
        }
    }
}
```

## sources

1. [SecureSecretKeySpec](https://github.com/dbsystel/SecureSecretKeySpec) — AutoCloseable SecretKey with obfuscation and zeroing (Apache 2.0)
2. [JEP 411 / Foreign Memory API for secrets](https://www.javacodegeeks.com/2025/07/securing-sensitive-data-in-java-applications-with-jep-411-foreign-function-memory-api.html) — off-heap key storage via MemorySegment/Arena
3. [Java Cryptography Architecture Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/crypto/CryptoSpec.html) — JCA/JCE reference for SecretKey, KeySpec, Cipher APIs
4. [AWS Encryption SDK for Java](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/java-example-code.html) — envelope encryption pattern with KMS

---
*Researched: 2026-03-18 | Next review: 2027-03-18*

## Updates 2026-04-03

- **TOCTOU in key holders (encrypt-memory-data audit run-001):** Atomic boolean
  closed-check is insufficient when the read after the check is non-atomic
  (e.g., MemorySegment.toArray on arena-backed memory). Read-write lock pattern
  needed: readers acquire read lock, close acquires write lock. Documented in
  EncryptionKeyHolder (F-R1.concurrency.2.1).
- **Defensive copy for MemorySegment returns:** Methods returning MemorySegment
  views of arena-backed key material create escaped references. Return heap-backed
  copies when the segment may outlive the holder (F-R1.rl.1.3).
- **Domain-separated key derivation:** Simple concatenation/truncation for key
  size adaptation is cryptographically weak. Use HMAC-SHA256 with distinct labels
  per algorithm (F-R1.data_transformation.1.1, 1.2).
