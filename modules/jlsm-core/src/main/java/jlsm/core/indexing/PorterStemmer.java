package jlsm.core.indexing;

import java.util.Objects;

/**
 * Porter stemming algorithm implementation.
 *
 * <p>
 * This is a stateless, deterministic implementation of the classic Porter stemmer. Use the
 * singleton {@link #INSTANCE} rather than constructing new instances.
 *
 * <p>
 * The implementation is entirely method-local: no instance state is read or written during
 * {@link #stem(String)}. A method-local {@code char[]} buffer is allocated for each call, making
 * the singleton safe for concurrent use.
 *
 * <p>
 * Reference: M.F. Porter, "An algorithm for suffix stripping", Program, 14(3): 130-137, 1980.
 */
public final class PorterStemmer implements Stemmer {

    /** Singleton instance. Thread-safe because the implementation is stateless. */
    public static final PorterStemmer INSTANCE = new PorterStemmer();

    private PorterStemmer() {
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    @Override
    public String stem(String token) {
        Objects.requireNonNull(token, "token must not be null");
        if (token.length() <= 2) {
            return token;
        }
        char[] b = token.toCharArray();
        // state[0] = j (working end-of-stem / boundary index)
        // state[1] = k (inclusive last index of the current word end)
        int[] state = new int[]{ b.length - 1, b.length - 1 };
        step1ab(b, state);
        step1c(b, state);
        step2(b, state);
        step3(b, state);
        step4(b, state);
        step5(b, state);
        return new String(b, 0, state[1] + 1);
    }

    // -----------------------------------------------------------------------
    // Vowel / consonant helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code b[i]} is a consonant in the Porter sense. 'y' is a consonant
     * when at position 0 or immediately after a vowel. The classification is computed iteratively
     * to avoid unbounded stack growth on adversarial inputs (e.g. a word composed entirely of 'y'
     * characters).
     *
     * <p>
     * Derivation: each 'y' in a run has the opposite classification of the character before it.
     * Walk left until hitting a non-'y' anchor (or index 0), then propagate parity back to {@code
     * i}: {@code b[i]} is a consonant iff the anchor is a consonant XOR the run length is odd.
     */
    private static boolean cons(char[] b, int i) {
        // Find the leftmost non-'y' anchor at or below i.
        int j = i;
        while (j > 0 && b[j] == 'y') {
            j--;
        }
        // Classify the anchor directly (non-'y' characters have an unambiguous classification).
        boolean anchorIsConsonant = switch (b[j]) {
            case 'a', 'e', 'i', 'o', 'u' -> false;
            case 'y' -> true; // j==0 and b[0]=='y': by definition a consonant
            default -> true;
        };
        // Propagate parity: each step right through a 'y' flips the classification.
        // b[i] is consonant iff anchorIsConsonant XOR (run length i-j is odd).
        return anchorIsConsonant ^ ((i - j) % 2 != 0);
    }

    /**
     * Computes the measure m of {@code b[0..j]} (the number of VC sequences). The stem region
     * checked is {@code b[0..j]}, but {@code k} is passed along because {@link #cons(char[], int)}
     * may need context beyond {@code j} for 'y'.
     *
     * <p>
     * In practice the Porter algorithm always evaluates {@code m} with {@code k == j} when calling
     * from the step helpers, so we only need {@code j}.
     */
    private static int m(char[] b, int j) {
        int n = 0;
        int i = 0;
        // skip leading consonants
        while (i <= j && cons(b, i)) {
            i++;
        }
        // count VC pairs
        while (i <= j) {
            // skip vowels
            while (i <= j && !cons(b, i)) {
                i++;
            }
            // skip consonants, counting one VC pair per run
            while (i <= j && cons(b, i)) {
                i++;
            }
            n++;
        }
        return n;
    }

    /** Returns {@code true} if {@code b[0..j]} contains at least one vowel. */
    private static boolean vowelInStem(char[] b, int j) {
        for (int i = 0; i <= j; i++) {
            if (!cons(b, i)) {
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} if {@code b[j-1]} and {@code b[j]} are identical consonants. */
    private static boolean doublec(char[] b, int j) {
        return j >= 1 && b[j] == b[j - 1] && cons(b, j);
    }

    /**
     * Returns {@code true} if {@code b[i-2..i]} forms a consonant-vowel-consonant pattern and the
     * final consonant is not w, x, or y.
     */
    private static boolean cvc(char[] b, int i) {
        if (i < 2 || !cons(b, i) || !cons(b, i - 2) || cons(b, i - 1)) {
            return false;
        }
        char c = b[i];
        return c != 'w' && c != 'x' && c != 'y';
    }

    // -----------------------------------------------------------------------
    // Suffix helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code b[0..state[1]]} ends with the string {@code s}, and sets
     * {@code state[0]} to {@code state[1] - s.length()} (the index of the last character before the
     * suffix).
     */
    private static boolean ends(char[] b, int[] state, String s) {
        int k = state[1];
        int l = s.length();
        if (l > k + 1) {
            return false;
        }
        int offset = k - l + 1;
        for (int i = 0; i < l; i++) {
            if (b[offset + i] != s.charAt(i)) {
                return false;
            }
        }
        state[0] = k - l;
        return true;
    }

    /**
     * Replaces {@code b[(state[0]+1)..state[1]]} with the characters of {@code s} and adjusts
     * {@code state[1]} accordingly.
     */
    private static void setto(char[] b, int[] state, String s) {
        int l = s.length();
        int o = state[0] + 1;
        for (int i = 0; i < l; i++) {
            b[o + i] = s.charAt(i);
        }
        state[1] = state[0] + l;
    }

    /** Calls {@link #setto} only when the measure of the stem exceeds 0. */
    private static void r(char[] b, int[] state, String s) {
        if (m(b, state[0]) > 0) {
            setto(b, state, s);
        }
    }

    // -----------------------------------------------------------------------
    // Algorithm steps
    // -----------------------------------------------------------------------

    private static void step1ab(char[] b, int[] state) {
        // Step 1a — handle plurals
        if (ends(b, state, "sses")) {
            state[1] -= 2;
        } else if (ends(b, state, "ies")) {
            setto(b, state, "i");
        } else if (!ends(b, state, "ss") && ends(b, state, "s")) {
            state[1]--;
        }

        // Step 1b — past tense and gerunds
        if (ends(b, state, "eed")) {
            if (m(b, state[0]) > 0) {
                state[1]--;
            }
        } else if ((ends(b, state, "ed") || ends(b, state, "ing")) && vowelInStem(b, state[0])) {
            // Truncate to the stem boundary found by ends()
            state[1] = state[0];
            if (ends(b, state, "at")) {
                setto(b, state, "ate");
            } else if (ends(b, state, "bl")) {
                setto(b, state, "ble");
            } else if (ends(b, state, "iz")) {
                setto(b, state, "ize");
            } else if (doublec(b, state[1])) {
                char last = b[state[1]];
                if (last != 'l' && last != 's' && last != 'z') {
                    state[1]--;
                }
            } else if (m(b, state[1]) == 1 && cvc(b, state[1])) {
                setto(b, state, "e");
            }
        }
    }

    private static void step1c(char[] b, int[] state) {
        if (ends(b, state, "y") && vowelInStem(b, state[0])) {
            b[state[1]] = 'i';
        }
    }

    private static void step2(char[] b, int[] state) {
        if (state[1] < 1) {
            return;
        }
        switch (b[state[1] - 1]) {
            case 'a' -> {
                if (ends(b, state, "ational")) {
                    r(b, state, "ate");
                } else if (ends(b, state, "tional")) {
                    r(b, state, "tion");
                }
            }
            case 'c' -> {
                if (ends(b, state, "enci")) {
                    r(b, state, "ence");
                } else if (ends(b, state, "anci")) {
                    r(b, state, "ance");
                }
            }
            case 'e' -> {
                if (ends(b, state, "izer")) {
                    r(b, state, "ize");
                }
            }
            case 'l' -> {
                if (ends(b, state, "abli")) {
                    r(b, state, "able");
                } else if (ends(b, state, "alli")) {
                    r(b, state, "al");
                } else if (ends(b, state, "entli")) {
                    r(b, state, "ent");
                } else if (ends(b, state, "eli")) {
                    r(b, state, "e");
                } else if (ends(b, state, "ousli")) {
                    r(b, state, "ous");
                }
            }
            case 'o' -> {
                if (ends(b, state, "ization")) {
                    r(b, state, "ize");
                } else if (ends(b, state, "ation")) {
                    r(b, state, "ate");
                } else if (ends(b, state, "ator")) {
                    r(b, state, "ate");
                }
            }
            case 's' -> {
                if (ends(b, state, "alism")) {
                    r(b, state, "al");
                } else if (ends(b, state, "iveness")) {
                    r(b, state, "ive");
                } else if (ends(b, state, "fulness")) {
                    r(b, state, "ful");
                } else if (ends(b, state, "ousness")) {
                    r(b, state, "ous");
                }
            }
            case 't' -> {
                if (ends(b, state, "aliti")) {
                    r(b, state, "al");
                } else if (ends(b, state, "iviti")) {
                    r(b, state, "ive");
                } else if (ends(b, state, "biliti")) {
                    r(b, state, "ble");
                }
            }
            default -> {
                /* no suffix matched */ }
        }
    }

    private static void step3(char[] b, int[] state) {
        if (state[1] < 1) {
            return;
        }
        switch (b[state[1]]) {
            case 'e' -> {
                if (ends(b, state, "icate")) {
                    r(b, state, "ic");
                } else if (ends(b, state, "ative")) {
                    r(b, state, "");
                } else if (ends(b, state, "alize")) {
                    r(b, state, "al");
                }
            }
            case 'i' -> {
                if (ends(b, state, "iciti")) {
                    r(b, state, "ic");
                }
            }
            case 'l' -> {
                if (ends(b, state, "ical")) {
                    r(b, state, "ic");
                } else if (ends(b, state, "ful")) {
                    r(b, state, "");
                }
            }
            case 's' -> {
                if (ends(b, state, "ness")) {
                    r(b, state, "");
                }
            }
            default -> {
                /* no suffix matched */ }
        }
    }

    private static void step4(char[] b, int[] state) {
        if (state[1] < 1) {
            return;
        }
        boolean matched = switch (b[state[1] - 1]) {
            case 'a' -> ends(b, state, "al");
            case 'c' -> ends(b, state, "ance") || ends(b, state, "ence");
            case 'e' -> ends(b, state, "er");
            case 'i' -> ends(b, state, "ic");
            case 'l' -> ends(b, state, "able") || ends(b, state, "ible");
            case 'n' -> ends(b, state, "ant") || ends(b, state, "ement") || ends(b, state, "ment")
                    || ends(b, state, "ent");
            case 'o' -> {
                if (ends(b, state, "ion")) {
                    // ion is only removed when preceded by 's' or 't'
                    int j = state[0];
                    yield j >= 0 && (b[j] == 's' || b[j] == 't');
                }
                yield ends(b, state, "ou");
            }
            case 's' -> ends(b, state, "ism");
            case 't' -> ends(b, state, "ate") || ends(b, state, "iti");
            case 'u' -> ends(b, state, "ous");
            case 'v' -> ends(b, state, "ive");
            case 'z' -> ends(b, state, "ize");
            default -> false;
        };
        if (matched && m(b, state[0]) > 1) {
            state[1] = state[0];
        }
    }

    private static void step5(char[] b, int[] state) {
        // Step 5a — remove trailing 'e' when measure allows
        if (b[state[1]] == 'e') {
            int stemEnd = state[1] - 1;
            int a = m(b, stemEnd);
            if (a > 1 || (a == 1 && !cvc(b, stemEnd))) {
                state[1]--;
            }
        }
        // Step 5b — remove one 'l' from double-l ending when measure > 1
        if (b[state[1]] == 'l' && doublec(b, state[1]) && m(b, state[1]) > 1) {
            state[1]--;
        }
    }
}
