# Security Lens — Attack Pattern Reference

Deep-dive attack patterns for the security lens. The Suspect subagent
consults this file when the cluster's active concerns include any of
`info_flow`, `auth`, `injection`, `crypto`, or `config_sensitivity`
(concerns 7-11 of the core assertion sweep), or when `assembly.md`
tagged the cluster's domain as "Security".

Generic concern areas (validation gaps, transformation fidelity,
contract violations, etc.) miss adversary-model bug classes: attacks
that exploit what the system *leaks* or what an attacker can *infer*,
rather than what the code *does wrong* in isolation. This file
documents the patterns those generic lenses miss.

---

## When to apply this lens

Activated automatically when Exploration's domain-signal detection
flags any of these (see `exploration.md` Step 5):

| Signal | Typical patterns |
|---|---|
| Crypto API | `Cipher`, `MessageDigest`, `encrypt`, `decrypt`, key management, hash, IV, nonce |
| Credential store | Password hashing/verification, API key storage, session token issuance, JWT signing/verification |
| PII handling | Fields named `ssn`, `email`, `phone`, `address`, `dob`; tables/collections that store personal data |
| Auth path | Authentication middleware, authorization checks, permission comparisons, role-based access |
| Input boundaries | HTTP handlers, RPC endpoints, deserialization entry points, SQL/query builders, path/URL parsers |

If a cluster has none of the signals above, skip this lens. Do not
apply security attack patterns to generic code — the false-positive
rate is high and the signal is low.

---

## Testability taxonomy

Not every security finding is fixable via prove-fix. Split findings by
**verification category** when you record them:

- **TESTABLE** — the attack has a deterministic failure mode that
  prove-fix can reproduce and the fix can prove. Examples:
  *key-material-not-zeroed*, *auth-check-missing*, *sql-injection*,
  *deserialization-without-validation*, *credential-in-logs*.
  Route normally through prove-fix.

- **ADVISORY** — the attack exploits non-functional properties
  (timing, cache state, allocation patterns). A prove-fix test would
  be flaky or platform-dependent, and "the fix" is a design choice
  (constant-time comparison, padding, rekeying) rather than a bug.
  Route as an advisory finding in the audit report — the user
  reviews and decides, but no adversarial test is produced.

Record the category in each finding's `severity_meta` field.

---

## Attack patterns (by concern)

### 7. Information flow / data exposure

**7.1 Credential in logs / errors / metrics**
- Password, API key, session token, or JWT appearing in any log line,
  error message body, or observability pipeline.
- Check every `log.*`, `error.*`, `stderr.write`, metric tag emission
  along the auth path.
- TESTABLE: assert log output contains no substring matching the
  credential value after an authenticated request.

**7.2 Timing channel on credential comparison**
- Using `==` / `strcmp` / non-constant-time comparison on a secret.
- An attacker can learn prefix length by measuring response time.
- ADVISORY: flag and recommend constant-time compare
  (`hmac.compare_digest` / `subtle.ConstantTimeCompare` / equivalent).

**7.3 Error message distinguishes existence from credentials**
- "User not found" vs "bad password" lets an attacker enumerate
  accounts.
- TESTABLE: assert response bodies are identical for "user exists +
  wrong password" and "user does not exist".

**7.4 Stack traces in responses**
- Framework default that returns exception chains (including
  environment details, file paths, internal type names) to untrusted
  callers.
- TESTABLE: error-response body contains no substring matching the
  exception class name or internal path prefix.

### 8. Auth / authorization logic

**8.1 Missing authorization check between endpoints**
- Two endpoints with the same permission requirement; only one has
  the check. Caller discovers the unchecked path.
- TESTABLE: call unchecked endpoint with token lacking required
  permission; assert rejected.

**8.2 Role check before ownership check**
- Admin can always see any record, but a regular user can see their
  own records. If ownership is checked after role and role fails open,
  privilege escalation follows.
- TESTABLE: set up a non-admin user, call resource, assert rejected
  before any database fetch reaches the record.

**8.3 Authentication on one method of a resource, not all**
- `GET /profile` checks auth; `HEAD /profile` or `OPTIONS /profile`
  does not. Some frameworks' auth middleware is method-scoped.
- TESTABLE: unauthenticated HEAD/OPTIONS returns same status as
  rejected GET.

**8.4 JWT without signature verification**
- Decodes JWT payload but skips signature verification (or uses
  `alg: "none"`).
- TESTABLE: forged JWT with attacker-chosen claims is accepted.

### 9. Injection / neutralization

**9.1 String-concatenated SQL / query**
- Any user-influenced string flowing into a query without
  parameterization.
- TESTABLE: injected fragment executes (e.g. `'; DROP TABLE` in a
  test database, or a query-builder assertion that user input never
  reaches `.raw()` / `.unsafe()`).

**9.2 Deserialization of untrusted data without class whitelist**
- Java `ObjectInputStream`, Python `pickle`, Node.js `vm.runInNew…`
  without input validation.
- TESTABLE: craft a payload that triggers class instantiation or
  side effect; assert rejected.

**9.3 Path traversal in file access**
- Constructing a file path from user input without canonicalizing and
  re-validating against an allowlist.
- TESTABLE: input `../../../etc/passwd` returns the canonical
  resolved path's containment check, or is rejected.

**9.4 Command injection**
- Passing user input to shell / `subprocess` without `shell=False` and
  argv-form separation.
- TESTABLE: input `; <payload>` does not execute `<payload>`.

### 10. Cryptographic misuse

**10.1 IV / nonce reuse for stream or AEAD modes**
- Same IV used across multiple encryptions with the same key
  (CTR/GCM/CBC-with-PKCS7). Two ciphertexts XORed recover plaintext
  XOR of the originals.
- TESTABLE: two calls encrypting distinct plaintext produce distinct
  IVs (assert IV is random or counter-increments).

**10.2 Key material not zeroed after use**
- Key bytes linger in memory longer than necessary. Heap dumps or
  swap leaks expose the key.
- TESTABLE: after the key-using call returns, the byte array
  referenced by the key holder is zeroed. (JVM: track the byte[],
  assert all zero bytes after the operation.)

**10.3 Weak RNG for secret material**
- `java.util.Random` / `Math.random()` / Python `random` used for
  key, salt, token, IV generation.
- TESTABLE: assert the secret generator is a cryptographic RNG
  (SecureRandom / secrets module / `crypto.randomBytes`).

**10.4 MAC-before-encrypt or no MAC**
- Authenticity decided by decrypt-and-check-plaintext instead of
  MAC-before-decrypt, enabling padding oracles; or encryption without
  any authenticity (CBC without MAC).
- ADVISORY: recommend AEAD (GCM, ChaCha20-Poly1305) and flag the
  pattern.

**10.5 Ciphertext without integrity tag**
- Ciphertext stored or transmitted without MAC/AEAD tag. Attacker
  can tamper without detection.
- TESTABLE: flip a bit in ciphertext; assert decrypt rejects.

**10.6 Hash-then-cache for credential check**
- Bcrypt/argon2 result cached in a fast-lookup map keyed by the
  plaintext password. Cache hit skips the bcrypt verify.
- TESTABLE: two different plaintext passwords with colliding cache
  keys both succeed.

### 11. Configuration / environment sensitivity

**11.1 Secure defaults absent**
- A configuration flag controls whether TLS / signature verification
  is enforced. Default is "off" (or unset treated as off).
- TESTABLE: unset the flag, assert secure behavior still applies.

**11.2 Secret embedded in config loaded into logs**
- Loading config and logging the parsed config object. Secrets in
  config appear in logs.
- TESTABLE: seed config with a recognizable secret; assert it does
  not appear in any log sink after config load.

**11.3 Environment-variable fallback reveals secret source**
- Code falls back to `ENV["SECRET"]` and logs "loaded secret from
  environment" without noting the fallback is used for local dev
  only.
- ADVISORY: recommend explicit "insecure-dev-mode" gate that rejects
  at startup in production.

---

## Output format

Record security findings with these additional fields:

```yaml
security_concern: info_flow | auth | injection | crypto | config_sensitivity
verification: TESTABLE | ADVISORY
attack_surface: <specific trigger — input, sequence, or adversary capability>
adversary_model: <who can exploit this — remote unauthenticated user,
  authenticated low-privilege user, local attacker, etc.>
```

The standard `severity` field still applies (high / medium / low),
but note that an ADVISORY finding can be high severity even without a
prove-fix path — the audit report surfaces it for user decision.

---

## What this lens must NOT do

- Generic input-validation findings that the `contract_boundaries`
  lens already covers. Only flag input validation when the adversary
  can *weaponize* the gap (not just trigger a crash).
- Architectural security reviews (threat model, trust boundary
  validation). Those are `/spec-author` territory, not prove-fix
  audit.
- Speculative advisories without a concrete attack path. "Log aggregation
  might someday leak this" is not a finding; "this log line contains a
  bcrypt hash that appears in the response body" is.
