module jlsm.tree {
    requires transitive jlsm.core;
    requires jlsm.compaction;
    exports jlsm.tree;
    // jlsm.tree.internal intentionally not exported
}
