package jlsm.tree;

import jlsm.core.sstable.SSTableReader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory that opens an existing SSTable file for reading.
 *
 * <p>
 * Each invocation opens the file at {@code path} and returns a ready-to-use reader.
 */
@FunctionalInterface
public interface SSTableReaderFactory {

    /**
     * Opens the SSTable file at {@code path} and returns a reader.
     *
     * @param path the path to an existing, completed SSTable file; must not be null
     * @return a new, open {@link SSTableReader}; never null
     * @throws IOException if the file cannot be opened or its header is malformed
     */
    SSTableReader open(Path path) throws IOException;
}
