module jlsm.engine {
    requires transitive jlsm.table;
    requires jlsm.core;

    exports jlsm.engine;
    exports jlsm.engine.cluster;
    // jlsm.engine.internal intentionally not exported
    // jlsm.engine.cluster.internal intentionally not exported
}
