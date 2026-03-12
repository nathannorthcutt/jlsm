module jlsm.table {
    requires transitive jlsm.core;
    requires jdk.incubator.vector;

    exports jlsm.table;
    // jlsm.table.internal intentionally not exported
}
