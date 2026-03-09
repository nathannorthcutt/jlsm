module jlsm.sstable {
    requires jlsm.core;
    requires jlsm.bloom;
    exports jlsm.sstable;
    // jlsm.sstable.internal intentionally not exported
}
