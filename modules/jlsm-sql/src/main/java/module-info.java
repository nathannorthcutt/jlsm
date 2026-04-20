// @spec F07.R1 — jlsm-sql exports only the jlsm.sql package
module jlsm.sql {
    requires transitive jlsm.table;

    exports jlsm.sql;
}
