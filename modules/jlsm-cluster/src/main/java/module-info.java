// @adr transport-module-placement — public jlsm.cluster + non-exported jlsm.cluster.internal
module jlsm.cluster {
    requires jlsm.core;
    requires java.logging;

    exports jlsm.cluster;
    // jlsm.cluster.internal not exported publicly; NodeAddressCodec exported
    // to jlsm.engine for membership-protocol use (qualified export).
    exports jlsm.cluster.internal to jlsm.engine;
}
