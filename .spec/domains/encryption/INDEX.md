# Encryption — Spec Index

> Shard index for the encryption domain.
> Split this file when it exceeds ~50 entries.

## Feature Registry

| ID | Title | Status | Amends | Decision Refs |
|----|-------|--------|--------|---------------|
| F03 | Field-Level In-Memory Encryption | ACTIVE | — | field-encryption-api-design, encrypted-index-strategy |
| F41 | Encryption Lifecycle | ACTIVE | — | per-field-pre-encryption, per-field-key-binding, encryption-key-rotation, unencrypted-to-encrypted-migration, index-access-pattern-leakage |
| F42 | WAL Encryption | ACTIVE | — | wal-entry-encryption |
| F45 | Client-Side Encryption SDK | ACTIVE | — | client-side-encryption-sdk, per-field-pre-encryption, pre-encrypted-flag-persistence |
| F46 | Encrypted Prefix Index | ACTIVE | — | encrypted-prefix-wildcard-queries, encrypted-index-strategy |
| F47 | Encrypted Fuzzy Matcher | ACTIVE | — | encrypted-fuzzy-matching, encrypted-index-strategy |
