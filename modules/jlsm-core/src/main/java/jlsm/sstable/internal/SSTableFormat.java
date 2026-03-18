package jlsm.sstable.internal;

/**
 * Constants describing the SSTable binary file format.
 *
 * <h3>v1 layout (uncompressed)</h3>
 *
 * <pre>
 *   [Data Block 0 ... N]
 *   [Key Index          ]
 *   [Bloom Filter       ]
 *   [Footer — 48 bytes  ]
 * </pre>
 *
 * v1 Footer (big-endian, 48 bytes):
 *
 * <pre>
 *   [long idxOffset   ]  8 bytes — file offset of Key Index section
 *   [long idxLength   ]  8 bytes — byte length of Key Index section
 *   [long fltOffset   ]  8 bytes — file offset of Bloom Filter section
 *   [long fltLength   ]  8 bytes — byte length of Bloom Filter section
 *   [long entryCount  ]  8 bytes — total number of entries
 *   [long magic       ]  8 bytes = MAGIC (0x4A4C534D53535401)
 * </pre>
 *
 * <h3>v2 layout (per-block compression)</h3>
 *
 * <pre>
 *   [Compressed Data Block 0 ... N]
 *   [Compression Map              ]
 *   [Key Index                    ]
 *   [Bloom Filter                 ]
 *   [Footer — 64 bytes            ]
 * </pre>
 *
 * v2 Footer (big-endian, 64 bytes):
 *
 * <pre>
 *   [long mapOffset   ]  8 bytes — file offset of Compression Map section
 *   [long mapLength   ]  8 bytes — byte length of Compression Map section
 *   [long idxOffset   ]  8 bytes — file offset of Key Index section
 *   [long idxLength   ]  8 bytes — byte length of Key Index section
 *   [long fltOffset   ]  8 bytes — file offset of Bloom Filter section
 *   [long fltLength   ]  8 bytes — byte length of Bloom Filter section
 *   [long entryCount  ]  8 bytes — total number of entries
 *   [long magic       ]  8 bytes = MAGIC_V2 (0x4A4C534D53535402)
 * </pre>
 *
 * @see CompressionMap
 */
public final class SSTableFormat {

    /** Magic number v1: ASCII "JLSMSST\x01" packed as a big-endian long. */
    public static final long MAGIC = 0x4A4C534D53535401L;

    /** Magic number v2: ASCII "JLSMSST\x02" — SSTable with compression map. */
    public static final long MAGIC_V2 = 0x4A4C534D53535402L;

    /** Size of the v1 footer section in bytes. */
    public static final int FOOTER_SIZE = 48;

    /** Size of the v2 footer section in bytes (adds mapOffset + mapLength). */
    public static final int FOOTER_SIZE_V2 = 64;

    /** Target data block size in bytes; new block starts when current exceeds this threshold. */
    public static final int DEFAULT_BLOCK_SIZE = 4096;

    /** Size of a single compression map entry in bytes. */
    public static final int COMPRESSION_MAP_ENTRY_SIZE = 17;

    private SSTableFormat() {
    }
}
