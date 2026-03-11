package jlsm.core.indexing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PorterStemmer}.
 *
 * <p>
 * Expected stems are derived from the canonical Porter (1980) algorithm vocabulary.
 */
class PorterStemmerTest {

    private final PorterStemmer stemmer = PorterStemmer.INSTANCE;

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    @Test
    void singleton_instanceProducesCorrectStem() {
        // Verifies that the singleton is both non-null and functionally correct
        assertEquals("run", PorterStemmer.INSTANCE.stem("running"),
                "INSTANCE.stem('running') must return 'run'");
    }

    @Test
    void singleton_deterministicOnRepeatedCalls() {
        // Two calls with the same input must produce the same output
        assertEquals(PorterStemmer.INSTANCE.stem("cats"), PorterStemmer.INSTANCE.stem("cats"),
                "repeated calls must return identical results");
    }

    // -----------------------------------------------------------------------
    // Short words (<=2 chars): unchanged
    // -----------------------------------------------------------------------

    @Nested
    class ShortWords {

        @Test
        void singleChar_unchanged() {
            assertEquals("a", stemmer.stem("a"));
        }

        @Test
        void twoChar_unchanged() {
            assertEquals("in", stemmer.stem("in"));
        }

        @Test
        void twoChar_at_unchanged() {
            assertEquals("at", stemmer.stem("at"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 1a — plurals
    // -----------------------------------------------------------------------

    @Nested
    class Step1aPlurals {

        @Test
        void caresses_to_caress() {
            assertEquals("caress", stemmer.stem("caresses"));
        }

        @Test
        void ponies_to_poni() {
            assertEquals("poni", stemmer.stem("ponies"));
        }

        @Test
        void ties_to_ti() {
            assertEquals("ti", stemmer.stem("ties"));
        }

        @Test
        void caress_unchanged() {
            assertEquals("caress", stemmer.stem("caress"));
        }

        @Test
        void cats_to_cat() {
            assertEquals("cat", stemmer.stem("cats"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 1b — past tense and gerunds
    // -----------------------------------------------------------------------

    @Nested
    class Step1bPastAndGerund {

        @Test
        void agreed_to_agre() {
            assertEquals("agre", stemmer.stem("agreed"));
        }

        @Test
        void disabled_to_disabl() {
            assertEquals("disabl", stemmer.stem("disabled"));
        }

        @Test
        void matting_to_mat() {
            assertEquals("mat", stemmer.stem("matting"));
        }

        @Test
        void running_to_run() {
            assertEquals("run", stemmer.stem("running"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 1c — y → i
    // -----------------------------------------------------------------------

    @Nested
    class Step1cYtoI {

        @Test
        void happy_to_happi() {
            assertEquals("happi", stemmer.stem("happy"));
        }

        @Test
        void sky_unchanged_no_vowel_in_stem() {
            // "sk" has no vowel → step 1c rule does not fire
            assertEquals("sky", stemmer.stem("sky"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 2
    // -----------------------------------------------------------------------

    @Nested
    class Step2 {

        @Test
        void relational_to_relat() {
            // step2: ational→ate → "relate", then step5a strips e (m=2>1) → "relat"
            assertEquals("relat", stemmer.stem("relational"));
        }

        @Test
        void conditional_to_condit() {
            // step2: tional→tion → "condition", then step4: ion (preceded by t) → "condit"
            assertEquals("condit", stemmer.stem("conditional"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 3
    // -----------------------------------------------------------------------

    @Nested
    class Step3 {

        @Test
        void rationalize_to_ration() {
            // step3: alize→al → "rational", then step4: al (m=2>1) → "ration"
            assertEquals("ration", stemmer.stem("rationalize"));
        }

        @Test
        void hopeful_to_hope() {
            assertEquals("hope", stemmer.stem("hopeful"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 4
    // -----------------------------------------------------------------------

    @Nested
    class Step4 {

        @Test
        void revival_to_reviv() {
            assertEquals("reviv", stemmer.stem("revival"));
        }

        @Test
        void allowance_to_allow() {
            assertEquals("allow", stemmer.stem("allowance"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 5a
    // -----------------------------------------------------------------------

    @Nested
    class Step5a {

        @Test
        void probate_to_probat() {
            assertEquals("probat", stemmer.stem("probate"));
        }

        @Test
        void rate_unchanged() {
            // measure(stem) == 1, so trailing 'e' is NOT dropped
            assertEquals("rate", stemmer.stem("rate"));
        }
    }

    // -----------------------------------------------------------------------
    // Step 5b
    // -----------------------------------------------------------------------

    @Nested
    class Step5b {

        @Test
        void controll_to_control() {
            assertEquals("control", stemmer.stem("controll"));
        }
    }

    // -----------------------------------------------------------------------
    // Reference vocabulary — canonical Porter stems
    // -----------------------------------------------------------------------

    @Nested
    class ReferenceVocabulary {

        @Test
        void generalizations_to_gener() {
            // canonical Porter1 output: generalizations → gener
            assertEquals("gener", stemmer.stem("generalizations"));
        }

        @Test
        void oscillating_to_oscil() {
            assertEquals("oscil", stemmer.stem("oscillating"));
        }

        @Test
        void presumably_to_presum() {
            assertEquals("presum", stemmer.stem("presumably"));
        }
    }

    // -----------------------------------------------------------------------
    // Stateless / concurrent
    // -----------------------------------------------------------------------

    @Test
    void concurrent_twoThreadsStemDifferentInputsCorrectly()
            throws InterruptedException, ExecutionException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return stemmer.stem("running");
            });
            Future<String> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return stemmer.stem("cats");
            });

            ready.await();
            go.countDown();

            assertEquals("run", f1.get(), "thread 1: 'running' must stem to 'run'");
            assertEquals("cat", f2.get(), "thread 2: 'cats' must stem to 'cat'");
        } finally {
            pool.shutdownNow();
        }
    }
}
