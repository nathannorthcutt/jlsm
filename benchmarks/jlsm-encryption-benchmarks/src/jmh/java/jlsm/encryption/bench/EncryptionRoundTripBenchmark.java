package jlsm.encryption.bench;

import jlsm.encryption.AesGcmEncryptor;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.DcpeSapEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Regression benchmark: round-trip (encrypt + decrypt) cost for each EncryptionSpec variant.
 *
 * <p>
 * Guards against degradation in:
 * <ul>
 * <li>AES-SIV (Deterministic) — CMAC chain + AES-CTR encrypt
 * <li>AES-GCM (Opaque) — SecureRandom IV + GCM encrypt/decrypt with tag verification
 * <li>Boldyreva OPE (OrderPreserving) — recursive hypergeometric sampling + detached MAC
 * <li>DCPE (DistancePreserving) — scale-and-perturb + detached MAC on float[]
 * </ul>
 *
 * <p>
 * Input sizes chosen to represent common field shapes: 64-byte string for byte-level schemes, INT16
 * domain for OPE, 128-dimension float vector for DCPE.
 *
 * @spec encryption.primitives-dispatch.R21 — performance impact measurable via JMH benchmarks across all four variants
 */
// @spec encryption.primitives-dispatch.R21
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class EncryptionRoundTripBenchmark {

    private static final byte[] FIELD_AD = "field".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAIN_64 = new byte[64];

    static {
        for (int i = 0; i < PLAIN_64.length; i++) {
            PLAIN_64[i] = (byte) i;
        }
    }

    private EncryptionKeyHolder keyHolder64;
    private EncryptionKeyHolder keyHolder32;

    private AesSivEncryptor siv;
    private AesGcmEncryptor gcm;
    private BoldyrevaOpeEncryptor ope;
    private DcpeSapEncryptor dcpe;

    private float[] vector128;

    @Setup(Level.Trial)
    public void setUp() {
        final byte[] key64 = new byte[64];
        for (int i = 0; i < 64; i++) {
            key64[i] = (byte) (0xA0 + i);
        }
        final byte[] key32 = new byte[32];
        for (int i = 0; i < 32; i++) {
            key32[i] = (byte) (0xB0 + i);
        }
        keyHolder64 = EncryptionKeyHolder.of(key64);
        keyHolder32 = EncryptionKeyHolder.of(key32);

        siv = new AesSivEncryptor(keyHolder64);
        gcm = new AesGcmEncryptor(keyHolder32);
        // Domain/range chosen to match FieldEncryptionDispatch's INT16 OPE config:
        // domain = 256^2 = 65536, range = 10 * domain = 655360
        ope = new BoldyrevaOpeEncryptor(keyHolder32, 65_536L, 655_360L);
        dcpe = new DcpeSapEncryptor(keyHolder32, 128);

        vector128 = new float[128];
        for (int i = 0; i < 128; i++) {
            vector128[i] = (float) Math.sin(i * 0.01);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (siv != null) {
            siv.close();
        }
        if (gcm != null) {
            gcm.close();
        }
        if (ope != null) {
            ope.close();
        }
        if (dcpe != null) {
            dcpe.close();
        }
        if (keyHolder64 != null) {
            keyHolder64.close();
        }
        if (keyHolder32 != null) {
            keyHolder32.close();
        }
    }

    // @spec encryption.primitives-dispatch.R21 — SIV round-trip
    @Benchmark
    public void sivRoundTrip(Blackhole bh) {
        final byte[] ct = siv.encrypt(PLAIN_64, FIELD_AD);
        final byte[] pt = siv.decrypt(ct, FIELD_AD);
        bh.consume(pt);
    }

    // @spec encryption.primitives-dispatch.R21 — GCM round-trip
    @Benchmark
    public void gcmRoundTrip(Blackhole bh) {
        final byte[] ct = gcm.encrypt(PLAIN_64);
        final byte[] pt = gcm.decrypt(ct);
        bh.consume(pt);
    }

    // @spec encryption.primitives-dispatch.R21 — OPE encrypt + decrypt on the domain endpoint (worst case for recursion)
    @Benchmark
    public void opeRoundTrip(Blackhole bh) {
        final long ct = ope.encrypt(42L);
        final long pt = ope.decrypt(ct);
        bh.consume(pt);
    }

    // @spec encryption.primitives-dispatch.R21 — DCPE round-trip on 128-dim float vector with MAC
    @Benchmark
    public void dcpeRoundTrip(Blackhole bh) {
        final DcpeSapEncryptor.EncryptedVector ev = dcpe.encrypt(vector128, FIELD_AD);
        final float[] pt = dcpe.decrypt(ev, FIELD_AD);
        bh.consume(pt);
    }
}
