package jlsm.wal.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Utilities for WAL segment file naming. File names follow the pattern {@code wal-NNNNNNNNNNNNNNNN.log}
 * where N is a zero-padded 16-digit decimal number.
 */
public final class SegmentFile {

    private static final String PREFIX = "wal-";
    private static final String SUFFIX = ".log";
    private static final Pattern FILE_PATTERN = Pattern.compile("^wal-(\\d{16})\\.log$");

    private SegmentFile() {}

    /**
     * Returns the segment file name for the given numeric identifier.
     */
    public static String toFileName(long number) {
        assert number >= 0 : "number must be non-negative";
        return PREFIX + "%016d".formatted(number) + SUFFIX;
    }

    /**
     * Parses the numeric identifier from a segment file name.
     *
     * @return the number, or {@code -1} if the name does not match the WAL pattern
     */
    public static long parseNumber(String fileName) {
        if (fileName == null) return -1;
        Matcher m = FILE_PATTERN.matcher(fileName);
        if (!m.matches()) return -1;
        return Long.parseLong(m.group(1));
    }

    /**
     * Lists all WAL segment files in {@code directory}, sorted by segment number ascending.
     */
    public static List<Path> listSorted(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(p -> parseNumber(p.getFileName().toString()) >= 0)
                    .sorted(Comparator.comparingLong(p -> parseNumber(p.getFileName().toString())))
                    .toList();
        }
    }
}
