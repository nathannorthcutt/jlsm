package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Tests for {@link EncryptionContext} — validates R80, R80a, R80a-1 factory contracts. */
class EncryptionContextTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final DomainId DOMAIN = new DomainId("d1");
    private static final TableId TABLE = new TableId("tbl1");
    private static final DekVersion V1 = new DekVersion(1);

    // ── direct constructor ──────────────────────────────────────────────

    @Test
    void constructor_rejectsNullPurpose() {
        assertThrows(NullPointerException.class, () -> new EncryptionContext(null, Map.of()));
    }

    @Test
    void constructor_rejectsNullAttributes() {
        assertThrows(NullPointerException.class,
                () -> new EncryptionContext(Purpose.HEALTH_CHECK, null));
    }

    @Test
    void constructor_defensivelyCopiesAttributes() {
        final Map<String, String> mutable = new HashMap<>();
        mutable.put("k1", "v1");
        final EncryptionContext ctx = new EncryptionContext(Purpose.HEALTH_CHECK, mutable);
        mutable.put("k1", "mutated");
        mutable.put("k2", "added");
        assertEquals("v1", ctx.attributes().get("k1"));
        assertFalse(ctx.attributes().containsKey("k2"));
    }

    @Test
    void attributes_areImmutable() {
        final EncryptionContext ctx = new EncryptionContext(Purpose.HEALTH_CHECK, Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.attributes().put("x", "y"));
    }

    // ── forDomainKek ────────────────────────────────────────────────────

    @Test
    void forDomainKek_rejectsNullTenant() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDomainKek(null, DOMAIN));
    }

    @Test
    void forDomainKek_rejectsNullDomain() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDomainKek(TENANT, null));
    }

    @Test
    void forDomainKek_purposeIsDomainKek() {
        final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);
        assertEquals(Purpose.DOMAIN_KEK, ctx.purpose());
    }

    @Test
    void forDomainKek_containsOnlyTenantAndDomain() {
        // R80a-1: non-DEK purposes must NOT include tableId or dekVersion.
        final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);
        assertEquals("t1", ctx.attributes().get("tenantId"));
        assertEquals("d1", ctx.attributes().get("domainId"));
        assertFalse(ctx.attributes().containsKey("tableId"));
        assertFalse(ctx.attributes().containsKey("dekVersion"));
    }

    // ── forDek ──────────────────────────────────────────────────────────

    @Test
    void forDek_rejectsNullTenant() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDek(null, DOMAIN, TABLE, V1));
    }

    @Test
    void forDek_rejectsNullDomain() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDek(TENANT, null, TABLE, V1));
    }

    @Test
    void forDek_rejectsNullTable() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDek(TENANT, DOMAIN, null, V1));
    }

    @Test
    void forDek_rejectsNullVersion() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forDek(TENANT, DOMAIN, TABLE, null));
    }

    @Test
    void forDek_purposeIsDek() {
        assertEquals(Purpose.DEK, EncryptionContext.forDek(TENANT, DOMAIN, TABLE, V1).purpose());
    }

    @Test
    void forDek_requiresTableIdAndDekVersionAttributes() {
        // R80a-1: DEK-purpose context MUST include tableId and dekVersion.
        final EncryptionContext ctx = EncryptionContext.forDek(TENANT, DOMAIN, TABLE, V1);
        assertTrue(ctx.attributes().containsKey("tenantId"));
        assertTrue(ctx.attributes().containsKey("domainId"));
        assertTrue(ctx.attributes().containsKey("tableId"));
        assertTrue(ctx.attributes().containsKey("dekVersion"));
        assertEquals("1", ctx.attributes().get("dekVersion"));
        assertEquals("tbl1", ctx.attributes().get("tableId"));
    }

    // ── forRekeySentinel ────────────────────────────────────────────────

    @Test
    void forRekeySentinel_purposeIsRekeySentinel() {
        final EncryptionContext ctx = EncryptionContext.forRekeySentinel(TENANT, DOMAIN);
        assertEquals(Purpose.REKEY_SENTINEL, ctx.purpose());
    }

    @Test
    void forRekeySentinel_omitsTableIdAndDekVersion() {
        // R80a-1: REKEY_SENTINEL must NOT include tableId or dekVersion.
        final EncryptionContext ctx = EncryptionContext.forRekeySentinel(TENANT, DOMAIN);
        assertFalse(ctx.attributes().containsKey("tableId"));
        assertFalse(ctx.attributes().containsKey("dekVersion"));
    }

    @Test
    void forRekeySentinel_rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forRekeySentinel(null, DOMAIN));
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forRekeySentinel(TENANT, null));
    }

    // ── forHealthCheck ──────────────────────────────────────────────────

    @Test
    void forHealthCheck_purposeIsHealthCheck() {
        final EncryptionContext ctx = EncryptionContext.forHealthCheck(TENANT, DOMAIN);
        assertEquals(Purpose.HEALTH_CHECK, ctx.purpose());
    }

    @Test
    void forHealthCheck_omitsTableIdAndDekVersion() {
        final EncryptionContext ctx = EncryptionContext.forHealthCheck(TENANT, DOMAIN);
        assertFalse(ctx.attributes().containsKey("tableId"));
        assertFalse(ctx.attributes().containsKey("dekVersion"));
    }

    @Test
    void forHealthCheck_rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forHealthCheck(null, DOMAIN));
        assertThrows(NullPointerException.class,
                () -> EncryptionContext.forHealthCheck(TENANT, null));
    }
}
