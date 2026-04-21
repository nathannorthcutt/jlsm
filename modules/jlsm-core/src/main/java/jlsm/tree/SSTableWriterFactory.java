package jlsm.tree;

import jlsm.core.model.Level;
import jlsm.core.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory that creates a new {@link SSTableWriter} for the given SSTable identity and destination.
 *
 * <p>
 * Each invocation must produce a writer that writes to a new, empty file at {@code path}.
 */
// @spec sstable.compaction.R1 — compactor uses SSTableWriterFactory
@FunctionalInterface
public interface SSTableWriterFactory {

    /**
     * Creates a new writer for the SSTable identified by {@code id} at the given {@code level},
     * writing to {@code path}.
     *
     * @param id unique identifier for the new SSTable
     * @param level the LSM level this SSTable belongs to
     * @param path the output file path; must not exist
     * @return a new, open {@link SSTableWriter}; never null
     * @throws IOException if the file cannot be created or opened
     */
    SSTableWriter create(long id, Level level, Path path) throws IOException;
}
