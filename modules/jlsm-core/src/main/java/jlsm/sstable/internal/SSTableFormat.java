package jlsm.sstable.internal;

/**
 * Constants describing the SSTable binary file format.
 *
 * <p>
 * File layout:
 *
 * <pre>
 *   [Data Block 0 ... N]
 *   [Key Index          ]
 *   [Bloom Filter       ]
 *   [Footer — 48 bytes  ]
 * </pre>
 *
 * Footer layout (big-endian, 48 bytes):
 *
 * <pre>
 *   [long idxOffset   ]  8 bytes — file offset of Key Index section
 *   [long idxLength   ]  8 bytes — byte length of Key Index section
 *   [long fltOffset   ]  8 bytes — file offset of Bloom Filter section
 *   [long fltLength   ]  8 bytes — byte length of Bloom Filter section
 *   [long entryCount  ]  8 bytes — total number of entries
 *   [long magic       ]  8 bytes = MAGIC
 * </pre>
 */
public final class SSTableFormat {

    /** Magic number: ASCII "JLSMSST\x01" packed as a big-endian long. */
    public static final long MAGIC = 0x4A4C534D53535401L;

    /** Size of the footer section in bytes. */
    public static final int FOOTER_SIZE = 48;

    /** Target data block size in bytes; new block starts when current exceeds this threshold. */
    public static final int DEFAULT_BLOCK_SIZE = 4096;

    private SSTableFormat() {
    }
}
