package jlsm.encryption.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

import jlsm.encryption.DomainKekRevokedException;
import jlsm.encryption.KekRevokedException;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.TenantKekRevokedException;

/**
 * Centralised factory for the R83h dual-instance exception construction. Produces a tenant-visible
 * exception (truncated stack trace, opaque correlation ID, redacted scope) paired with a
 * deployer-internal exception (full diagnostics) so tenants never observe raw scope tuples,
 * KMS-provider class names, or region tags.
 *
 * <p>
 * An ArchUnit-style enforcement test on the test classpath asserts that no production code
 * constructs {@link TenantKekRevokedException} or {@link DomainKekRevokedException} directly
 * outside this factory — see {@code R83ExceptionFactoryEnforcementTest}.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83h, R83i, R83i-1).
 *
 * @spec encryption.primitives-lifecycle R83h
 * @spec encryption.primitives-lifecycle R83i-1
 */
public final class R83ExceptionFactory {

    private R83ExceptionFactory() {
        throw new UnsupportedOperationException("utility class — do not instantiate");
    }

    /**
     * Construct a dual-instance pair for a tenant-tier revocation. The tenant-visible instance
     * truncates its stack to top-of-public-API frames and substitutes a redacted message for the
     * raw scope; the deployer-internal instance carries full detail.
     *
     * @throws NullPointerException if any argument is null
     */
    public static R83Exceptions tenantRevoked(TenantId tenantId, TableScope scope,
            Throwable kmsCause) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kmsCause, "kmsCause");
        return constructPair(tenantId, scope, kmsCause, TenantKekRevokedException::new,
                TenantKekRevokedException::new);
    }

    /** Construct a dual-instance pair for a domain-tier revocation. */
    public static R83Exceptions domainRevoked(TenantId tenantId, TableScope scope,
            Throwable kmsCause) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kmsCause, "kmsCause");
        return constructPair(tenantId, scope, kmsCause, DomainKekRevokedException::new,
                DomainKekRevokedException::new);
    }

    private static R83Exceptions constructPair(TenantId tenantId, TableScope scope,
            Throwable kmsCause,
            java.util.function.Function<String, ? extends KekRevokedException> noCauseCtor,
            BiFunction<String, Throwable, ? extends KekRevokedException> withCauseCtor) {
        final String correlationId = UUID.randomUUID().toString();

        // Tenant-visible: redacted message + no chained cause + truncated stack trace. The
        // redaction discipline does NOT include domainId or tableId raw values; only the
        // tenantId (already redacted by TenantId.toString) and the correlation id appear.
        final String tenantMessage = "kek revoked for tenant; correlationId=" + correlationId;
        final KekRevokedException tenantVisible = noCauseCtor.apply(tenantMessage);
        // Stack-truncate: blank trace ensures no internal frames leak.
        tenantVisible.setStackTrace(new StackTraceElement[0]);

        // Deployer-internal: full scope tuple + chained cause. Scope's toString already redacts
        // identifier values; the deployer's logger has access to the raw scope via the
        // correlation id pivot in the audit channel.
        final String deployerMessage = "kek revoked for tenant=" + tenantId + " scope=" + scope
                + " correlationId=" + correlationId;
        final KekRevokedException deployerInternal = withCauseCtor.apply(deployerMessage, kmsCause);

        return new R83Exceptions(tenantVisible, deployerInternal, correlationId);
    }

    /** Pair of (tenant-visible, deployer-internal) exception instances per R83h. */
    public record R83Exceptions(Throwable tenantVisible, Throwable deployerInternal,
            String correlationId) {

        public R83Exceptions {
            Objects.requireNonNull(tenantVisible, "tenantVisible");
            Objects.requireNonNull(deployerInternal, "deployerInternal");
            Objects.requireNonNull(correlationId, "correlationId");
            if (correlationId.isEmpty()) {
                throw new IllegalArgumentException("correlationId must not be empty");
            }
        }
    }
}
