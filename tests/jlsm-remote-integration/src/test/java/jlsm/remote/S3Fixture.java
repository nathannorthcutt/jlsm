package jlsm.remote;

import com.adobe.testing.s3mock.S3MockApplication;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import software.amazon.nio.spi.s3.S3XFileSystemProvider;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * JUnit 5 extension that starts an in-process S3Mock server backed by in-memory storage and opens
 * an S3 NIO {@link FileSystem} pointing at it.
 *
 * <p>
 * Uses {@code S3XFileSystemProvider} (scheme {@code s3x://}) which supports encoding the endpoint
 * and credentials directly in the URI: {@code s3x://accessKey:secretKey@host:port/bucket}. The
 * {@code s3.spi.endpoint-protocol} system property is set to {@code "http"} so the SPI directs its
 * internal S3 client at the local HTTP server.
 *
 * <p>
 * S3Mock 4.x fully supports modern AWS SDK CRT headers (unlike S3Proxy which rejects
 * {@code x-amz-sdk-checksum-algorithm}), so {@code newFileSystem()} succeeds without any
 * CRT-compatibility workarounds.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * {@literal @}ExtendWith(S3Fixture.class)
 * class MyS3Test {
 *     {@literal @}BeforeEach void setup(S3Fixture fixture) { dir = fixture.newTestDirectory(); }
 * }
 * </pre>
 */
public class S3Fixture implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    static final String BUCKET = "jlsm-test";
    static final String ACCESS_KEY = "test-access";
    static final String SECRET_KEY = "test-secret";

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace
            .create(S3Fixture.class);

    // Per-class state, initialized in beforeAll
    private S3MockApplication s3Mock;
    private FileSystem s3fs;

    // ---- JUnit 5 lifecycle ----

    @Override
    public void beforeAll(ExtensionContext ctx) throws Exception {
        // Start S3Mock on a random HTTP port with in-memory storage
        s3Mock = S3MockApplication.start("--" + S3MockApplication.PROP_HTTP_PORT + "=0",
                "--" + S3MockApplication.PROP_SILENT + "=true");
        @SuppressWarnings("removal")
        int port = s3Mock.getHttpPort();

        // Tell the S3NioSpiConfiguration (which reads from system properties) to use HTTP
        // and force path style — must be set before newFileSystem() creates the SPI config.
        System.setProperty("s3.spi.endpoint-protocol", "http");
        System.setProperty("s3.spi.force-path-style", "true");
        // Also set standard AWS creds so the CRT credential chain can resolve them
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
        System.setProperty("aws.region", "us-east-1");

        // Open S3 NIO FileSystem via S3XFileSystemProvider, which parses endpoint and
        // credentials directly from the URI: s3x://accessKey:secretKey@host:port/bucket
        // S3Mock handles the createBucket call from newFileSystem() — including any
        // modern CRT headers — so this succeeds without extra pre-creation steps.
        S3XFileSystemProvider provider = new S3XFileSystemProvider();
        URI fsUri = URI.create(
                "s3x://" + ACCESS_KEY + ":" + SECRET_KEY + "@localhost:" + port + "/" + BUCKET);
        s3fs = provider.newFileSystem(fsUri, Map.of());

        ctx.getStore(NS).put("fixture", this);
    }

    @Override
    public void afterAll(ExtensionContext ctx) throws Exception {
        ctx.getStore(NS).remove("fixture");
        if (s3fs != null) {
            try {
                s3fs.close();
            } catch (Exception ignored) {
            }
        }
        if (s3Mock != null) {
            try {
                s3Mock.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- ParameterResolver: inject S3Fixture into @BeforeEach / test methods ----

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        return pc.getParameter().getType() == S3Fixture.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        ExtensionContext cur = ec;
        while (cur != null) {
            S3Fixture f = cur.getStore(NS).get("fixture", S3Fixture.class);
            if (f != null)
                return f;
            cur = cur.getParent().orElse(null);
        }
        throw new IllegalStateException(
                "S3Fixture not found — is @ExtendWith(S3Fixture.class) on the test class?");
    }

    // ---- Public API ----

    /**
     * Returns a fresh S3 "directory" path (unique UUID prefix) for isolating one test's files. In
     * S3, directories are implicit (key prefixes). The path returned is relative to the filesystem
     * root ({@code /bucket-name} is the root for {@code s3x://} URIs).
     */
    public Path newTestDirectory() {
        assert s3fs != null : "S3Fixture not started";
        return s3fs.getPath("/" + UUID.randomUUID());
    }
}
