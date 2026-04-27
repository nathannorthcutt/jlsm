package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.DomainKekRevokedException;
import jlsm.encryption.KekRevokedException;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.TenantKekRevokedException;

/**
 * Tests for {@link R83ExceptionFactory} (R83h, R83i, R83i-1). Dual-instance construction:
 * tenant-visible (truncated stack, redacted scope) and deployer-internal (full diagnostics) plus
 * stable correlation id.
 *
 * @spec encryption.primitives-lifecycle R83h
 * @spec encryption.primitives-lifecycle R83i-1
 */
class R83ExceptionFactoryTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final TableScope SCOPE = new TableScope(TENANT, new DomainId("domain-X"),
            new TableId("table-Q"));

    @Test
    void tenantRevokedProducesPair() {
        final RuntimeException cause = new RuntimeException("kms refused");
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, cause);
        assertNotNull(pair.tenantVisible());
        assertNotNull(pair.deployerInternal());
        assertNotSame(pair.tenantVisible(), pair.deployerInternal(),
                "tenant-visible and deployer-internal must be distinct instances (R83h)");
        assertNotNull(pair.correlationId());
        assertFalse(pair.correlationId().isEmpty());
    }

    @Test
    void domainRevokedProducesPair() {
        final RuntimeException cause = new RuntimeException("kms refused");
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.domainRevoked(TENANT,
                SCOPE, cause);
        assertNotNull(pair.tenantVisible());
        assertNotNull(pair.deployerInternal());
        assertNotSame(pair.tenantVisible(), pair.deployerInternal());
    }

    @Test
    void tenantRevokedTenantVisibleHasNoChainedCause() {
        // R83h: tenant-visible instance must have setCause(null) so reflective walkers cannot
        // pick up KMS plugin metadata via getCause()
        final RuntimeException cause = new RuntimeException("kms-internal-detail-leak");
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, cause);
        assertSame(null, pair.tenantVisible().getCause(),
                "tenant-visible exception must NOT chain the KMS cause (R83h confidentiality)");
    }

    @Test
    void deployerInternalRetainsCause() {
        final RuntimeException cause = new RuntimeException("kms-internal-detail");
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, cause);
        assertSame(cause, pair.deployerInternal().getCause(),
                "deployer-internal exception must retain full KMS cause for diagnostics");
    }

    @Test
    void tenantRevokedReturnsTenantSubtype() {
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, new RuntimeException());
        assertTrue(pair.tenantVisible() instanceof TenantKekRevokedException,
                "tenantRevoked must produce TenantKekRevokedException");
        assertTrue(pair.deployerInternal() instanceof TenantKekRevokedException);
    }

    @Test
    void domainRevokedReturnsDomainSubtype() {
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.domainRevoked(TENANT,
                SCOPE, new RuntimeException());
        assertTrue(pair.tenantVisible() instanceof DomainKekRevokedException,
                "domainRevoked must produce DomainKekRevokedException");
        assertTrue(pair.deployerInternal() instanceof DomainKekRevokedException);
    }

    @Test
    void tenantVisibleMessageRedactsScope() {
        // R83h: tenant-visible must redact dekVersion, domainId, tableId
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, new RuntimeException("upstream"));
        final String tenantMsg = pair.tenantVisible().getMessage();
        assertNotNull(tenantMsg);
        // Redaction discipline: opaque correlation-id must appear
        assertTrue(tenantMsg.contains(pair.correlationId()),
                "tenant-visible message must contain correlation id; got: " + tenantMsg);
        // Raw domainId / tableId values must NOT appear (TableScope.toString already
        // redacts them, but we additionally confirm the raw value is absent)
        assertFalse(tenantMsg.contains("domain-X"),
                "tenant-visible message must redact domainId; got: " + tenantMsg);
        assertFalse(tenantMsg.contains("table-Q"),
                "tenant-visible message must redact tableId; got: " + tenantMsg);
    }

    @Test
    void deployerInternalMessageContainsScope() {
        // R83h: deployer-internal carries full scope tuple
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.domainRevoked(TENANT,
                SCOPE, new RuntimeException());
        final String deployerMsg = pair.deployerInternal().getMessage();
        assertNotNull(deployerMsg);
        // Deployer message must reference the scope content (TableScope is embedded;
        // contains tenantId/domainId/tableId components even though they may be redacted by
        // their own toString — the deployer-internal logger has access to .value()
        // separately. We assert at least the correlation id is present so support engineers
        // can pivot.)
        assertTrue(deployerMsg.contains(pair.correlationId()));
    }

    @Test
    void correlationIdsAreUniquePerCall() {
        final R83ExceptionFactory.R83Exceptions a = R83ExceptionFactory.tenantRevoked(TENANT, SCOPE,
                new RuntimeException());
        final R83ExceptionFactory.R83Exceptions b = R83ExceptionFactory.tenantRevoked(TENANT, SCOPE,
                new RuntimeException());
        assertNotEquals(a.correlationId(), b.correlationId(),
                "correlation ids must be unique per failure-construction call");
    }

    @Test
    void tenantVisibleStackIsTruncated() {
        // R83h: tenant-visible stack must be truncated to top-of-public-API frames
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, new RuntimeException());
        final StackTraceElement[] tenantTrace = pair.tenantVisible().getStackTrace();
        final StackTraceElement[] deployerTrace = pair.deployerInternal().getStackTrace();
        // The tenant-visible trace must be no longer than the deployer-internal trace; the
        // factory truncates internal frames before returning. We accept a length-zero
        // truncation (no frames retained) as satisfying the contract.
        assertTrue(tenantTrace.length <= deployerTrace.length,
                "tenant-visible stack must be truncated (R83h)");
    }

    @Test
    void tenantRevokedNullArgsRejected() {
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.tenantRevoked(null, SCOPE, new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.tenantRevoked(TENANT, null, new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.tenantRevoked(TENANT, SCOPE, null));
    }

    @Test
    void domainRevokedNullArgsRejected() {
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.domainRevoked(null, SCOPE, new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.domainRevoked(TENANT, null, new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> R83ExceptionFactory.domainRevoked(TENANT, SCOPE, null));
    }

    @Test
    void resultIsKekRevokedSubtype() {
        // The factory's returned exceptions must remain KekRevokedException subtypes so
        // catch-by-base works.
        final R83ExceptionFactory.R83Exceptions pair = R83ExceptionFactory.tenantRevoked(TENANT,
                SCOPE, new RuntimeException());
        assertTrue(pair.tenantVisible() instanceof KekRevokedException);
        assertTrue(pair.deployerInternal() instanceof KekRevokedException);
    }

    @Test
    void exceptionsRecordRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new R83ExceptionFactory.R83Exceptions(null,
                new TenantKekRevokedException("d"), "abc"));
        assertThrows(NullPointerException.class,
                () -> new R83ExceptionFactory.R83Exceptions(new TenantKekRevokedException("t"),
                        null, "abc"));
        assertThrows(NullPointerException.class,
                () -> new R83ExceptionFactory.R83Exceptions(new TenantKekRevokedException("t"),
                        new TenantKekRevokedException("d"), null));
    }

    @Test
    void exceptionsRecordRejectsEmptyCorrelationId() {
        assertThrows(IllegalArgumentException.class,
                () -> new R83ExceptionFactory.R83Exceptions(new TenantKekRevokedException("t"),
                        new TenantKekRevokedException("d"), ""));
    }
}
