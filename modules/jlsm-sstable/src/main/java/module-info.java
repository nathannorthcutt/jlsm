module jlsm.sstable {
    requires transitive jlsm.core;
    requires transitive jlsm.bloom;
    exports jlsm.sstable;
    // jlsm.sstable.internal intentionally not exported
}
