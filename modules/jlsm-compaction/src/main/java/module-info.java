module jlsm.compaction {
    requires transitive jlsm.core;
    requires jlsm.sstable;
    requires jlsm.bloom;
    exports jlsm.compaction;
    // jlsm.compaction.internal intentionally not exported
}
