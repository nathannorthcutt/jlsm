package jlsm.table.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.Query;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.Predicate;

/**
 * Behavioural tests for {@link FullTextFieldIndex} against a fake backing {@link FullTextIndex}.
 * Exercises adapter semantics — field propagation, null handling on update, supports/lookup
 * dispatch, and close propagation — without requiring a real LSM-backed index.
 *
 * <p>
 * Integration coverage against {@code LsmFullTextIndex} lives in jlsm-indexing tests.
 */
// @spec query.full-text-index.R1,R2,R3,R4,R5,R6 — exercises the adapter wiring satisfied by WD-01
class FullTextFieldIndexTest {

    /**
     * Fake in-memory full-text index that records invocations and returns canned results for
     * TermQuery lookups keyed by (field, token).
     */
    private static final class FakeFullTextIndex implements FullTextIndex<MemorySegment> {

        final List<Call> indexCalls = new ArrayList<>();
        final List<Call> removeCalls = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void index(MemorySegment docId, Map<String, String> fields) {
            indexCalls.add(new Call(copy(docId), Map.copyOf(fields)));
        }

        @Override
        public void remove(MemorySegment docId, Map<String, String> fields) {
            removeCalls.add(new Call(copy(docId), Map.copyOf(fields)));
        }

        @Override
        public Iterator<MemorySegment> search(Query query) {
            // For the tests, resolve TermQuery by scanning recorded index calls whose field
            // contains the token. Simple substring match is sufficient for validation.
            if (!(query instanceof Query.TermQuery tq)) {
                throw new UnsupportedOperationException("fake only supports TermQuery");
            }
            final Set<MemorySegment> hits = new HashSet<>();
            for (Call call : indexCalls) {
                String text = call.fields.get(tq.field());
                if (text == null)
                    continue;
                for (String token : text.toLowerCase().split("\\s+")) {
                    if (token.equals(tq.term().toLowerCase())) {
                        hits.add(call.docId);
                        break;
                    }
                }
            }
            return hits.iterator();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
        }

        private static MemorySegment copy(MemorySegment src) {
            byte[] bytes = src.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }

        record Call(MemorySegment docId, Map<String, String> fields) {
        }
    }

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static IndexDefinition bioDef() {
        return new IndexDefinition("bio", IndexType.FULL_TEXT);
    }

    @Test
    void definition_returnsDefinitionPassedAtConstruction() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        IndexDefinition def = bioDef();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(def, fake)) {
            assertEquals(def, idx.definition());
        }
    }

    @Test
    void onInsert_propagatesFieldNameAndValueToBackingIndex() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onInsert(stringKey("pk1"), "hello world");
            assertEquals(1, fake.indexCalls.size());
            FakeFullTextIndex.Call call = fake.indexCalls.get(0);
            assertEquals(Map.of("bio", "hello world"), call.fields());
        }
    }

    @Test
    void onInsert_nullFieldValue_isNoOp() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onInsert(stringKey("pk1"), null);
            assertTrue(fake.indexCalls.isEmpty(),
                    "null field value must not produce an index call");
        }
    }

    @Test
    void onUpdate_removesOldThenInsertsNew() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), "old text", "new text");
            assertEquals(1, fake.removeCalls.size(), "old value must be removed");
            assertEquals(Map.of("bio", "old text"), fake.removeCalls.get(0).fields());
            assertEquals(1, fake.indexCalls.size(), "new value must be inserted");
            assertEquals(Map.of("bio", "new text"), fake.indexCalls.get(0).fields());
        }
    }

    @Test
    void onUpdate_nullOld_isInsertOnly() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), null, "new text");
            assertTrue(fake.removeCalls.isEmpty(), "null old value must not trigger remove");
            assertEquals(1, fake.indexCalls.size());
            assertEquals(Map.of("bio", "new text"), fake.indexCalls.get(0).fields());
        }
    }

    @Test
    void onUpdate_nullNew_isDeleteOnly() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), "old text", null);
            assertEquals(1, fake.removeCalls.size());
            assertEquals(Map.of("bio", "old text"), fake.removeCalls.get(0).fields());
            assertTrue(fake.indexCalls.isEmpty(), "null new value must not trigger index");
        }
    }

    @Test
    void onDelete_removesFieldValue() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onDelete(stringKey("pk1"), "hello world");
            assertEquals(1, fake.removeCalls.size());
            assertEquals(Map.of("bio", "hello world"), fake.removeCalls.get(0).fields());
        }
    }

    @Test
    void onDelete_nullFieldValue_isNoOp() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onDelete(stringKey("pk1"), null);
            assertTrue(fake.removeCalls.isEmpty());
        }
    }

    @Test
    void supports_trueForFullTextMatchOnOwnField() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            assertTrue(idx.supports(new Predicate.FullTextMatch("bio", "hello")));
        }
    }

    @Test
    void supports_falseForFullTextMatchOnOtherField() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            assertFalse(idx.supports(new Predicate.FullTextMatch("other", "hello")));
        }
    }

    @Test
    void supports_falseForNonFullTextPredicates() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            assertFalse(idx.supports(new Predicate.Eq("bio", "x")));
            assertFalse(idx.supports(new Predicate.Ne("bio", "x")));
            assertFalse(idx.supports(new Predicate.Between("bio", "a", "z")));
        }
    }

    @Test
    void lookup_fullTextMatch_returnsPrimaryKeysOfMatchingDocuments() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            idx.onInsert(stringKey("pk1"), "hello world");
            idx.onInsert(stringKey("pk2"), "foo bar");
            idx.onInsert(stringKey("pk3"), "hello there");

            Iterator<MemorySegment> it = idx.lookup(new Predicate.FullTextMatch("bio", "hello"));
            List<MemorySegment> hits = new ArrayList<>();
            it.forEachRemaining(hits::add);

            Set<String> stringHits = new HashSet<>();
            for (MemorySegment seg : hits) {
                stringHits.add(new String(seg.toArray(ValueLayout.JAVA_BYTE),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals(Set.of("pk1", "pk3"), stringHits);
        }
    }

    @Test
    void lookup_unsupportedPredicate_throwsUOE() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        try (FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> idx.lookup(new Predicate.Eq("bio", "hello")));
        }
    }

    @Test
    void constructor_rejectsNonFullTextIndexType() {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        IndexDefinition wrong = new IndexDefinition("bio", IndexType.EQUALITY);
        assertThrows(IllegalArgumentException.class, () -> new FullTextFieldIndex(wrong, fake));
    }

    @Test
    void constructor_rejectsNullDefinition() {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        assertThrows(NullPointerException.class, () -> new FullTextFieldIndex(null, fake));
    }

    @Test
    void constructor_rejectsNullBacking() {
        IndexDefinition def = bioDef();
        assertThrows(NullPointerException.class, () -> new FullTextFieldIndex(def, null));
    }

    @Test
    void close_closesBackingIndex() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), fake);
        idx.close();
        assertTrue(fake.closed.get(), "backing index must be closed when adapter closes");
    }

    @Test
    void close_idempotent_doesNotDoubleCloseBacking() throws IOException {
        FakeFullTextIndex fake = new FakeFullTextIndex();
        AtomicBoolean secondCloseThrew = new AtomicBoolean(false);
        FullTextIndex<MemorySegment> backing = new FullTextIndex<>() {
            boolean closed = false;

            @Override
            public void index(MemorySegment docId, Map<String, String> fields) {
            }

            @Override
            public void remove(MemorySegment docId, Map<String, String> fields) {
            }

            @Override
            public Iterator<MemorySegment> search(Query query) {
                return Collections.emptyIterator();
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    secondCloseThrew.set(true);
                    throw new IOException("backing closed twice");
                }
                closed = true;
                fake.closed.set(true);
            }
        };
        FullTextFieldIndex idx = new FullTextFieldIndex(bioDef(), backing);
        idx.close();
        idx.close(); // second close must be a no-op; must not propagate to backing
        assertFalse(secondCloseThrew.get(), "second close must not propagate to backing");
    }
}
