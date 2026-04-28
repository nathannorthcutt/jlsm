module jlsm.core {
    requires static jdk.incubator.vector;

    exports jlsm.core.model;
    exports jlsm.core.memtable;
    exports jlsm.core.sstable;
    exports jlsm.core.wal;
    exports jlsm.core.bloom;
    exports jlsm.core.compaction;
    exports jlsm.core.cache;
    exports jlsm.core.io;
    exports jlsm.core.compression;
    exports jlsm.core.tree;
    exports jlsm.core.indexing;
    exports jlsm.bloom;
    exports jlsm.bloom.blocked;
    exports jlsm.wal.local;
    exports jlsm.wal.remote;
    exports jlsm.memtable;
    exports jlsm.sstable;
    exports jlsm.compaction;
    exports jlsm.cache;
    exports jlsm.tree;
    exports jlsm.encryption;
    exports jlsm.encryption.local;
    exports jlsm.encryption.spi;
    // Qualified export: jlsm.encryption.internal is not a public API, but jlsm.table
    // consumes OffHeapKeyMaterial during the WD-01 transition. Remove once
    // jlsm-table is migrated to the EncryptionKeyHolder facade (WD-02+).
    exports jlsm.encryption.internal to jlsm.table, jlsm.engine;
    exports jlsm.core.json; // @spec serialization.simd-jsonl.R40,R41 — public JSON API exported,
                            // internal package not
                            // exported
    // NOT exported (internal impl detail):
    // jlsm.bloom.hash, jlsm.wal.internal, jlsm.memtable.internal,
    // jlsm.sstable.internal, jlsm.compaction.internal, jlsm.tree.internal,
    // jlsm.encryption.internal
}
