package jlsm.sstable;

/**
 * Result of dictionary training during SSTable compaction.
 *
 * <p>
 * Package-private — used only by {@link TrieSSTableWriter} during dictionary-assisted compression.
 * This is a stub created to unblock compilation; the full implementation is part of WU-3.
 *
 * @param attempted whether dictionary training was attempted
 * @param trained whether a dictionary was successfully trained
 * @param reason reason string if training failed or was skipped; null on success
 * @param dictionaryId the dictionary ID; 0 if no dictionary
 */
record DictionaryTrainingResult(boolean attempted, boolean trained, String reason,
        int dictionaryId) {
}
