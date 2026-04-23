package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for {@link FsyncSkipListener}.
 *
 * <p>
 * These tests verify the interface's functional-lambda shape; they pass immediately since the
 * interface has no implementation body. If a future change removed {@code @FunctionalInterface} or
 * altered the method signature, the lambda-expression compilation in
 * {@link #canBeImplementedAsLambda()} would fail and catch the regression.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R23
 */
class FsyncSkipListenerTest {

    @Test
    void canBeImplementedAsLambda() {
        FsyncSkipListener l = (p, c, r) -> {
        };
        assertDoesNotThrow(() -> l.onFsyncSkip(Path.of("/tmp/x"), FileChannel.class, "reason"));
    }

    @Test
    void callbackReceivesArguments() {
        AtomicReference<Path> pathRef = new AtomicReference<>();
        AtomicReference<Class<? extends Channel>> classRef = new AtomicReference<>();
        AtomicReference<String> reasonRef = new AtomicReference<>();

        FsyncSkipListener listener = (p, c, r) -> {
            pathRef.set(p);
            classRef.set(c);
            reasonRef.set(r);
        };

        Path expectedPath = Path.of("/tmp/sstable.db");
        Class<? extends Channel> expectedClass = FileChannel.class;
        String expectedReason = "non-file-channel";

        listener.onFsyncSkip(expectedPath, expectedClass, expectedReason);

        assertEquals(expectedPath, pathRef.get());
        assertSame(expectedClass, classRef.get());
        assertEquals(expectedReason, reasonRef.get());
    }
}
