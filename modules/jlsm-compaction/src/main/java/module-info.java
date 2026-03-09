module jlsm.compaction {
    requires jlsm.core;
    requires jlsm.sstable;
    requires jlsm.bloom;
    exports jlsm.compaction;
    // jlsm.compaction.internal intentionally not exported
}
