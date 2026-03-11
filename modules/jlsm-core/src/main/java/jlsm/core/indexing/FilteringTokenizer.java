package jlsm.core.indexing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link Tokenizer} decorator that removes stop words from the token stream produced by a
 * delegate tokenizer.
 */
public final class FilteringTokenizer implements Tokenizer {

    private final Tokenizer delegate;
    private final Set<String> stopWords;

    /**
     * Constructs a {@code FilteringTokenizer} that removes {@code stopWords} from every token
     * collection returned by {@code delegate}.
     *
     * @param delegate the tokenizer to delegate to; must not be null
     * @param stopWords the set of stop words to filter out; must not be null
     */
    public FilteringTokenizer(Tokenizer delegate, Set<String> stopWords) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.stopWords = Set
                .copyOf(Objects.requireNonNull(stopWords, "stopWords must not be null"));
    }

    @Override
    public Collection<String> tokenize(String text) {
        Objects.requireNonNull(text, "text must not be null");
        assert delegate != null : "delegate must not be null";
        assert stopWords != null : "stopWords must not be null";
        Collection<String> tokens = delegate.tokenize(text);
        List<String> result = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!stopWords.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }
}
