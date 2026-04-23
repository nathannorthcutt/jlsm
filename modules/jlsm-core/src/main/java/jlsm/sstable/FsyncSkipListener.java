package jlsm.sstable;

import java.nio.channels.Channel;
import java.nio.file.Path;

/**
 * Callback invoked by the SSTable writer each time a prescribed fsync cannot be performed because
 * the underlying output is not a {@link java.nio.channels.FileChannel}.
 *
 * <p>
 * The writer enforces a three-fsync discipline during finish (after data, after metadata, after
 * footer). When the output channel is a non-{@code FileChannel} (e.g. an S3 or GCS channel supplied
 * by a third-party NIO provider), the writer cannot call {@code force()} and instead invokes this
 * listener with the channel class and a reason string. The listener is expected to emit a metric or
 * structured log entry so operators can audit durability posture end-to-end.
 * </p>
 *
 * <p>
 * Registered on {@code TrieSSTableWriter.Builder}. Exactly one callback is invoked per skipped
 * fsync site per writer lifecycle; if the same writer performs three fsyncs and all are skipped,
 * the listener is invoked three times.
 * </p>
 *
 * <p>
 * Implementations must not throw: any runtime exception raised by the listener is allowed to
 * propagate and will cause the writer to move to the {@code FAILED} state.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R23
 */
@FunctionalInterface
public interface FsyncSkipListener {

    /**
     * Invoked when an fsync site is skipped because {@code channel} is not a
     * {@link java.nio.channels.FileChannel}.
     *
     * @param path the on-disk path the writer is producing; never {@code null}
     * @param channelClass the concrete {@link Channel} subclass that was supplied (e.g. an
     *            S3-backed NIO channel); never {@code null}
     * @param reason short machine-parseable reason code such as {@code "non-file-channel"} or
     *            {@code "atomic-rename-unsupported"}; never {@code null}
     * @spec sstable.end-to-end-integrity.R23
     */
    void onFsyncSkip(Path path, Class<? extends Channel> channelClass, String reason);
}
