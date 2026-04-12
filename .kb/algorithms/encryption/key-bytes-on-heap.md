---
title: "Key bytes on heap"
type: adversarial-finding
domain: "security"
severity: "tendency"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/SseEncryptedIndex.java"
research_status: active
last_researched: "2026-03-25"
---
# Key bytes on heap

## What happens
Encryptors call `EncryptionKeyHolder.getKeyBytes()` to obtain key material, then
pass the byte[] to JCE (SecretKeySpec) or store it as a field, but never zero the
copy. The key material persists on the Java heap until garbage collection, violating
the jvm-key-handling-patterns KB guidance that key bytes should be zeroed after use.

## Why implementations default to this
JCE's SecretKeySpec accepts byte[] and copies it internally, so developers assume the
source array can be discarded. But "discarded" means "eligible for GC," not "zeroed."
The byte[] sits in memory and is visible in heap dumps. Storing derived keys as fields
(DcpeSapEncryptor.keyBytes, SseEncryptedIndex.prfKey/encKey) is worse — they persist
for the entire encryptor lifetime.

## Test guidance
- After constructing an encryptor, verify it still works after the key holder is
  closed (confirms key material was copied, not referenced)
- Check that intermediate byte[] from getKeyBytes() are zeroed after use in
  encryptor constructors
- For classes that store key-derived byte[] fields, verify the class implements
  cleanup or documents the security implication

## Found in
- encrypt-memory-data (round 1, 2026-03-25): AesGcmEncryptor, BoldyrevaOpeEncryptor, DcpeSapEncryptor, SseEncryptedIndex all leave key bytes on heap

## Updates 2026-04-03

- **AutoCloseable + key zeroing implemented (encrypt-memory-data audit run-001):**
  All four encryptors (AesGcmEncryptor, AesSivEncryptor, BoldyrevaOpeEncryptor,
  DcpeSapEncryptor) and SseEncryptedIndex now implement AutoCloseable with key
  material zeroing in close().
- **DcpeSapEncryptor:** keyBytes zeroed at end of constructor after deriving
  scaleFactor and seeding seedRng — not retained as a field.
- **SseEncryptedIndex:** prfKey and encKey zeroed in close(); SecretKeySpec
  instances cached and destroyed.
- **Accepted risk:** JDK SecretKeySpec.destroy() is a no-op on most implementations.
  SecretKeySpec clones the byte[] internally and the clone cannot be zeroed.
- **Accepted risk:** ThreadLocal Cipher instances retain key state until thread
  termination or close(). AutoCloseable pattern addresses well-behaved callers
  but cannot force cleanup on abandoned threads.
