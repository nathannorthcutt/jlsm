# jlsm Encryption Package

Field-level encryption primitives for the jlsm document table module.
Each class implements one encryption family; `EncryptionSpec` selects
which family a field uses. Keys are held in `EncryptionKeyHolder`
(Arena-backed, zeroed on close).

## Encryption Schemes

| Class | EncryptionSpec | Algorithm | Key Size | Purpose |
|-------|---------------|-----------|----------|---------|
| `AesSivEncryptor` | `Deterministic` | AES-SIV (RFC 5297) | 64 bytes | Same plaintext always produces same ciphertext; enables equality and keyword search |
| `BoldyrevaOpeEncryptor` | `OrderPreserving` | Boldyreva OPE with hypergeometric sampling | 16-32 bytes (AES key) | Ciphertext ordering matches plaintext ordering; enables range queries |
| `AesGcmEncryptor` | `Opaque` | AES-256-GCM | 32 bytes | Randomized authenticated encryption; no search capability |
| `DcpeSapEncryptor` | `DistancePreserving` | DCPE Scale-And-Perturb | 32 bytes | Approximate distance preservation on float vectors; enables ANN search |

## Capability Matrix

What each scheme supports when used with secondary indices:

| Capability | None | Deterministic | OrderPreserving | DistancePreserving | Opaque |
|-----------|------|--------------|-----------------|-------------------|--------|
| Equality  | Y    | Y            | Y               | -                 | -      |
| Range     | Y    | -            | Y               | -                 | -      |
| Keyword   | Y    | Y            | -               | -                 | -      |
| Phrase    | Y    | Y (T2)       | -               | -                 | -      |
| SSE       | Y    | Y (T3)       | -               | -                 | -      |
| ANN       | Y    | -            | -               | Y (approx)        | -      |

## OrderPreserving (OPE) Usage and Limitations

### Compatible Field Types

OPE requires a bounded numeric or string domain. The `IndexRegistry` rejects
`OrderPreserving` on field types where OPE is impractical:

| Field Type | OPE Supported | Notes |
|-----------|---------------|-------|
| INT8      | Yes           | Full 8-bit precision |
| INT16     | Yes           | Full 16-bit precision |
| INT32     | Yes           | Top 16 bits only (values differing only in low 16 bits share ciphertext order) |
| INT64     | Yes           | Top 16 bits only |
| TIMESTAMP | Yes           | Same as INT64 |
| BoundedString(n) | Yes   | First min(n, 2) bytes only |
| STRING (unbounded) | No  | Use `FieldType.string(maxLength)` instead |
| BOOLEAN   | No            | Only 2 values; use Deterministic |
| FLOAT*    | No            | Floating-point domain semantics differ |
| VECTOR    | No            | Use DistancePreserving |
| ARRAY     | No            | Not a scalar type |
| OBJECT    | No            | Not a scalar type |

### BoundedString for OPE on Strings

To use OrderPreserving encryption on a string field, declare it as a
`BoundedString` with a maximum byte length:

```java
JlsmSchema schema = JlsmSchema.builder("users", 1)
    .field("country_code", FieldType.string(2), EncryptionSpec.orderPreserving())
    .build();
```

The OPE domain is derived from `min(maxLength, 2)` bytes, giving up to
65,536 distinct orderable values. Strings longer than 2 bytes are ordered
by their first 2 bytes only.

### Performance Characteristics

The Boldyreva OPE scheme uses recursive bisection with hypergeometric
sampling. Each recursion level performs O(range/2) AES operations in the
worst case. Practical performance depends on the domain size:

| Domain (bytes) | Domain Size | Approx Recursion Depth | Encrypt Time |
|---------------|-------------|----------------------|--------------|
| 1 byte        | 256         | ~8 levels            | < 1 ms       |
| 2 bytes       | 65,536      | ~16 levels           | < 100 ms     |

The library caps the OPE byte count at 2 to keep encryption time
practical for synchronous operations. For fields wider than 2 bytes
(INT32, INT64, TIMESTAMP, BoundedString > 2), only the most-significant
bytes participate in ordering.

### Known Limitations

1. **Precision loss for wide types**: INT32/INT64/TIMESTAMP OPE ordering
   uses only the top 2 bytes. Values that differ only in their lower bytes
   will have identical ciphertext order.

2. **String ordering limited to first 2 bytes**: BoundedString fields with
   maxLength > 2 are ordered by their first 2 bytes only. Strings sharing
   a 2-byte prefix will have identical ciphertext order.

3. **OPE leaks order**: by design, the ciphertext reveals the relative
   ordering of plaintexts. This is the fundamental security/functionality
   tradeoff of OPE.

4. **AES PRF cipher is not thread-safe**: each `BoldyrevaOpeEncryptor`
   instance uses a cached AES cipher. Do not share instances across threads
   without external synchronization. `FieldEncryptionDispatch` creates
   per-field instances.

## Thread Safety

**All encryptors are thread-safe.** `Cipher` instances are cached in
`ThreadLocal` fields — each thread gets its own initialized `Cipher`,
eliminating both contention and the cost of `Cipher.getInstance()` +
`Cipher.init()` on every call.

### How it works

```java
// Each encryptor wraps its Cipher in ThreadLocal:
private final ThreadLocal<Cipher> cmacCipher = ThreadLocal.withInitial(() -> {
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
    c.init(ENCRYPT_MODE, cmacKeySpec);
    return c;
});
```

- **Zero contention** — no locks, no synchronization, no shared mutable state
- **Lazy initialization** — Cipher is created on first use per thread
- **Memory overhead** — ~240 bytes per thread per encryptor (one AES key schedule)
- **Performance impact** — `ThreadLocal.get()` is a single array lookup (~5ns),
  negligible vs the AES operations themselves (microsecond scale)

### Safe usage patterns

- **Single-threaded**: works as expected, same as a plain field
- **JlsmTable / partitioned tables**: safe. Each partition's serializer has
  its own encryptor instances, but even sharing a single encryptor across
  partition threads is now safe
- **Custom multi-threaded**: safe. A single `AesSivEncryptor` instance can
  be shared across any number of threads

```java
// SAFE — shared encryptor, each thread uses its own ThreadLocal Cipher
var siv = new AesSivEncryptor(keyHolder);
executor.submit(() -> siv.encrypt(data1, ad1));  // thread A's Cipher
executor.submit(() -> siv.encrypt(data2, ad2));  // thread B's Cipher
```

### Note on DcpeSapEncryptor

`DcpeSapEncryptor` uses `SecureRandom` (also wrapped in `ThreadLocal`)
rather than `Cipher`. The same thread-safety guarantees apply.

### Performance by concurrency level (current architecture)

| Partitions | OPE INT8 (ops/s) | OPE INT16 (ops/s) | AES-SIV (ops/s) |
|-----------|-------------------|-------------------|-----------------|
| 1         | ~7,000            | ~27               | ~1,000,000      |
| N (parallel) | ~7,000 × N    | ~27 × N           | ~1,000,000 × N  |

Scaling is linear because there is zero shared state between partitions.

## Key Management

`EncryptionKeyHolder` wraps a byte array key in an Arena-backed
MemorySegment. Call `close()` to zero the key material.

- AES-SIV requires a 64-byte key. If your table key is 32 bytes,
  `FieldEncryptionDispatch` derives a 64-byte key by repeating it.
- AES-GCM requires a 32-byte key. If your table key is 64 bytes,
  `FieldEncryptionDispatch` uses the first 32 bytes.
- OPE uses up to 32 bytes of the key for AES-ECB PRF.
- DCPE uses the key for scaling and perturbation parameters.

## Security Tradeoffs

| Scheme | Security Level | What Leaks |
|--------|---------------|------------|
| Opaque (AES-GCM) | Highest | Nothing (IND-CPA secure) |
| Deterministic (AES-SIV) | Medium | Frequency of equal values |
| OrderPreserving (OPE) | Low | Relative ordering of all values |
| DistancePreserving (DCPE) | Medium | Approximate distances between vectors |

Choose the weakest scheme that supports your required query operations.
Use `Opaque` for fields that are only read after decryption (e.g., PII,
medical records). Use `Deterministic` for fields needing equality search.
Use `OrderPreserving` only when range queries on encrypted data are required.
