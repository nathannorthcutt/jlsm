module jlsm.engine {
    requires transitive jlsm.table;
    requires jlsm.core;

    exports jlsm.engine;
    // jlsm.engine.internal intentionally not exported
}
