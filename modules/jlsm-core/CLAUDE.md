# jlsm-core

This is the central module of the library. All interfaces and all implementations live here.
Higher-level modules (`jlsm-indexing`, `jlsm-vector`) depend on this module only.

## Internal Packages

These packages are not exported in `module-info.java` and must not be made public:

- `jlsm.bloom.hash`
- `jlsm.wal.internal`
- `jlsm.memtable.internal`
- `jlsm.sstable.internal`
- `jlsm.compaction.internal`
- `jlsm.tree.internal`

## Key Constraint

Do not add dependencies on `jlsm-indexing` or `jlsm-vector` — the dependency arrow points
one way only. If shared code is needed, it belongs here in `jlsm-core`.