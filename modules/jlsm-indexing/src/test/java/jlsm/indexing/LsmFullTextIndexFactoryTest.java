package jlsm.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.Query;

/**
 * Exercises the {@link LsmFullTextIndexFactory} end-to-end: a real LSM-backed full-text index
 * produced by the factory must satisfy the index/search/remove/close contract across (tableName,
 * fieldName) pairs.
 */
class LsmFullTextIndexFactoryTest {

    @TempDir
    Path tempDir;

    private static MemorySegment utf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static String decode(MemorySegment seg) {
        return new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    @Test
    void factory_producesWorkingFullTextIndex() throws IOException {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        try (FullTextIndex<MemorySegment> idx = factory.create("users", "bio")) {
            idx.index(utf8("pk1"), Map.of("bio", "hello world"));
            idx.index(utf8("pk2"), Map.of("bio", "foo bar"));
            idx.index(utf8("pk3"), Map.of("bio", "hello there"));

            Iterator<MemorySegment> it = idx.search(new Query.TermQuery("bio", "hello"));
            Set<String> hits = new HashSet<>();
            it.forEachRemaining(seg -> hits.add(decode(seg)));
            assertEquals(Set.of("pk1", "pk3"), hits);
        }
    }

    @Test
    void factory_removesDocument() throws IOException {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        try (FullTextIndex<MemorySegment> idx = factory.create("users", "bio")) {
            idx.index(utf8("pk1"), Map.of("bio", "hello"));
            idx.remove(utf8("pk1"), Map.of("bio", "hello"));
            Iterator<MemorySegment> it = idx.search(new Query.TermQuery("bio", "hello"));
            assertFalse(it.hasNext(), "removed doc must not appear in search");
        }
    }

    @Test
    void factory_isolatesIndicesByTableAndField() throws IOException {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        try (FullTextIndex<MemorySegment> usersBio = factory.create("users", "bio");
                FullTextIndex<MemorySegment> articlesBody = factory.create("articles", "body")) {
            usersBio.index(utf8("u1"), Map.of("bio", "hello"));
            articlesBody.index(utf8("a1"), Map.of("body", "hello"));

            Iterator<MemorySegment> userHits = usersBio.search(new Query.TermQuery("bio", "hello"));
            Set<String> userHitSet = new HashSet<>();
            userHits.forEachRemaining(seg -> userHitSet.add(decode(seg)));
            assertEquals(Set.of("u1"), userHitSet);

            Iterator<MemorySegment> articleHits = articlesBody
                    .search(new Query.TermQuery("body", "hello"));
            Set<String> articleHitSet = new HashSet<>();
            articleHits.forEachRemaining(seg -> articleHitSet.add(decode(seg)));
            assertEquals(Set.of("a1"), articleHitSet);
        }
    }

    @Test
    void factory_rejectsNullTableName() {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class, () -> factory.create(null, "bio"));
    }

    @Test
    void factory_rejectsNullFieldName() {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class, () -> factory.create("users", null));
    }

    @Test
    void factory_rejectsBlankNames() {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        assertThrows(IllegalArgumentException.class, () -> factory.create("", "bio"));
        assertThrows(IllegalArgumentException.class, () -> factory.create("users", ""));
    }

    @Test
    void builder_rejectsNullRootDirectory() {
        assertThrows(NullPointerException.class,
                () -> LsmFullTextIndexFactory.builder().rootDirectory(null).build());
    }

    @Test
    void builder_rejectsMissingRootDirectory() {
        assertThrows(NullPointerException.class, () -> LsmFullTextIndexFactory.builder().build());
    }

    @Test
    void indexSearchRemoveClose_roundTripWithMultipleWrites() throws IOException {
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(tempDir)
                .build();
        try (FullTextIndex<MemorySegment> idx = factory.create("t", "f")) {
            for (int i = 0; i < 50; i++) {
                idx.index(utf8("pk" + i), Map.of("f", "word" + (i % 5)));
            }
            Iterator<MemorySegment> it = idx.search(new Query.TermQuery("f", "word2"));
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }
            // keys pk2, pk7, pk12, ..., pk47 → 10 hits
            assertTrue(count == 10, "expected 10 hits for word2, got " + count);
        }
    }
}
