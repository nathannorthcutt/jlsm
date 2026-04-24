package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import jlsm.encryption.TenantId;

/**
 * Tests for {@link ShardPathResolver} — deterministic fn(TenantId) → Path (R82, R82b).
 */
class ShardPathResolverTest {

    private static final Path ROOT = Paths.get("/tmp/jlsm-registry");

    @Test
    void shardPath_deterministic_sameInputYieldsSamePath() {
        final TenantId tenant = new TenantId("tenantA");
        final Path a = ShardPathResolver.shardPath(ROOT, tenant);
        final Path b = ShardPathResolver.shardPath(ROOT, tenant);
        assertEquals(a, b);
    }

    @Test
    void shardPath_differentTenantsYieldDifferentPaths() {
        final Path a = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        final Path b = ShardPathResolver.shardPath(ROOT, new TenantId("tenantB"));
        assertNotEquals(a, b);
    }

    @Test
    void shardPath_rootedAtRegistryRoot() {
        final Path p = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        assertTrue(p.startsWith(ROOT), "shard path must be under the registry root");
    }

    @Test
    void shardPath_slashInTenantIdIsSafe_noPathTraversal() {
        // "a/b" must not produce a path that escapes into a subdirectory literally.
        final Path p = ShardPathResolver.shardPath(ROOT, new TenantId("a/b"));
        // Must remain anchored under ROOT — no traversal.
        assertTrue(p.startsWith(ROOT));
        // Must not contain a literal "a/b" segment (encoding required).
        assertFalse(p.toString().contains("/a/b/"),
                "tenantId with slash must be encoded, not embedded literally");
    }

    @Test
    void shardPath_dotDotInTenantIdIsSafe_noPathTraversal() {
        final Path p = ShardPathResolver.shardPath(ROOT, new TenantId(".."));
        assertTrue(p.startsWith(ROOT));
        // The normalized path must still be under ROOT — no traversal.
        assertTrue(p.normalize().startsWith(ROOT),
                "tenantId '..' must not produce a traversal path");
    }

    @Test
    void shardPath_emojiTenantIdProducesStableUsablePath() {
        final TenantId tenant = new TenantId("😀-tenant"); // grinning emoji + dash + name
        final Path a = ShardPathResolver.shardPath(ROOT, tenant);
        final Path b = ShardPathResolver.shardPath(ROOT, tenant);
        assertEquals(a, b);
        // Filesystem-safe encoding: no non-ASCII characters in resulting path components.
        final String s = a.toString();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            assertTrue(c < 0x80 || c == java.io.File.separatorChar,
                    "encoded path must be ASCII-only, found: U+" + Integer.toHexString(c));
        }
    }

    @Test
    void shardPath_nullRootRejected() {
        assertThrows(NullPointerException.class,
                () -> ShardPathResolver.shardPath(null, new TenantId("x")));
    }

    @Test
    void shardPath_nullTenantIdRejected() {
        assertThrows(NullPointerException.class, () -> ShardPathResolver.shardPath(ROOT, null));
    }

    @Test
    void tempPath_siblingOfShardPath_sameParent() {
        final Path shard = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        final Path temp = ShardPathResolver.tempPath(shard, "abc123");
        assertEquals(shard.getParent(), temp.getParent());
    }

    @Test
    void tempPath_endsWithTmpSuffix() {
        final Path shard = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        final Path temp = ShardPathResolver.tempPath(shard, "abc123");
        assertTrue(temp.getFileName().toString().endsWith(".tmp"),
                "tempPath must end with .tmp; was: " + temp.getFileName());
        assertTrue(temp.getFileName().toString().contains("abc123"),
                "tempPath must include the suffix; was: " + temp.getFileName());
    }

    @Test
    void tempPath_differentSuffixesYieldDifferentPaths() {
        final Path shard = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        final Path a = ShardPathResolver.tempPath(shard, "s1");
        final Path b = ShardPathResolver.tempPath(shard, "s2");
        assertNotEquals(a, b);
    }

    @Test
    void tempPath_nullShardRejected() {
        assertThrows(NullPointerException.class, () -> ShardPathResolver.tempPath(null, "s1"));
    }

    @Test
    void tempPath_nullSuffixRejected() {
        final Path shard = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        assertThrows(NullPointerException.class, () -> ShardPathResolver.tempPath(shard, null));
    }

    @Test
    void tempPath_emptySuffixRejected() {
        final Path shard = ShardPathResolver.shardPath(ROOT, new TenantId("tenantA"));
        assertThrows(IllegalArgumentException.class, () -> ShardPathResolver.tempPath(shard, ""));
    }
}
