// @spec F05.R74,R75 — public API packages only; internal packages intentionally not exported;
// transitive requires on jlsm.table exposes shared document/schema/query types
module jlsm.engine {
    requires transitive jlsm.table;
    requires jlsm.core;

    exports jlsm.engine;
    exports jlsm.engine.cluster;
    // jlsm.engine.internal intentionally not exported
    // jlsm.engine.cluster.internal intentionally not exported
}
