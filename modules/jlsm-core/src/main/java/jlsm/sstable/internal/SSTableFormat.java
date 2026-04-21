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
 * <h3>v3 layout (per-block compression with CRC32C checksums)</h3>
 *
 * <pre>
 *   [Compressed Data Block 0 ... N]
 *   [Compression Map v3           ]
 *   [Key Index                    ]
 *   [Bloom Filter                 ]
 *   [Footer — 72 bytes            ]
 * </pre>
 *
 * v3 Footer (big-endian, 72 bytes):
 *
 * <pre>
 *   [long mapOffset   ]  8 bytes — file offset of Compression Map section
 *   [long mapLength   ]  8 bytes — byte length of Compression Map section
 *   [long idxOffset   ]  8 bytes — file offset of Key Index section
 *   [long idxLength   ]  8 bytes — byte length of Key Index section
 *   [long fltOffset   ]  8 bytes — file offset of Bloom Filter section
 *   [long fltLength   ]  8 bytes — byte length of Bloom Filter section
 *   [long entryCount  ]  8 bytes — total number of entries
 *   [long blockSize   ]  8 bytes — data block size in bytes
 *   [long magic       ]  8 bytes = MAGIC_V3 (0x4A4C534D53535403)
 * </pre>
 *
 * @see CompressionMap
 */
// @spec sstable.format-v2.R1 — v2 layout: data blocks, compression map, key index, bloom, footer
// @spec sstable.format-v2.R2 — 64-byte footer with 8 big-endian long fields
// @spec sstable.format-v2.R5 — v1 magic 0x4A4C534D53535401, v2 magic ...02
// @spec sstable.v3-format-upgrade.R13,R14,R17 — v3 magic 0x...03, 72-byte footer, named block-size constants
// @spec compression.zstd-dictionary.R19 — v4 magic 0x...04, 88-byte footer with dictOffset/dictLength fields
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

    /** Magic number v3: ASCII "JLSMSST\x03" — SSTable with per-block checksums. */
    public static final long MAGIC_V3 = 0x4A4C534D53535403L;

    /** Size of the v3 footer section in bytes (adds blockSize field over v2). */
    public static final int FOOTER_SIZE_V3 = 72;

    /**
     * Size of a single v3 compression map entry in bytes (adds 4-byte CRC32C checksum over v2's 17
     * bytes).
     */
    public static final int COMPRESSION_MAP_ENTRY_SIZE_V3 = 21;

    /** Magic number v4: ASCII "JLSMSST\x04" — SSTable with dictionary meta-block. */
    public static final long MAGIC_V4 = 0x4A4C534D53535404L;

    /**
     * Size of the v4 footer section in bytes (adds dictOffset + dictLength over v3's 72 bytes).
     *
     * <p>
     * v4 footer layout (big-endian, 88 bytes):
     *
     * <pre>
     *   [long mapOffset     ]  offset 0
     *   [long mapLength     ]  offset 8
     *   [long dictOffset    ]  offset 16
     *   [long dictLength    ]  offset 24
     *   [long idxOffset     ]  offset 32
     *   [long idxLength     ]  offset 40
     *   [long fltOffset     ]  offset 48
     *   [long fltLength     ]  offset 56
     *   [long entryCount    ]  offset 64
     *   [long blockSize     ]  offset 72
     *   [long magic         ]  offset 80 = MAGIC_V4
     * </pre>
     */
    public static final int FOOTER_SIZE_V4 = 88;

    /** Block size optimised for huge-page TLB alignment (2 MiB). */
    public static final int HUGE_PAGE_BLOCK_SIZE = 2_097_152;

    /** Block size optimised for remote/object-storage backends (8 MiB). */
    public static final int REMOTE_BLOCK_SIZE = 8_388_608;

    /** Maximum allowed block size (32 MiB). */
    public static final int MAX_BLOCK_SIZE = 33_554_432;

    /** Minimum allowed block size (1 KiB). */
    public static final int MIN_BLOCK_SIZE = 1024;

    /**
     * Validates that a block size is within the allowed range and is a power of two.
     *
     * @param blockSize the block size to validate
     * @throws IllegalArgumentException if the block size is invalid
     */
    // @spec sstable.v3-format-upgrade.R11 — min 1024, max 33554432, power-of-two; IAE identifies violated constraint
    public static void validateBlockSize(int blockSize) {
        if (blockSize < MIN_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "blockSize %d is below minimum %d".formatted(blockSize, MIN_BLOCK_SIZE));
        }
        if (blockSize > MAX_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "blockSize %d exceeds maximum %d".formatted(blockSize, MAX_BLOCK_SIZE));
        }
        if ((blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "blockSize %d is not a power of two".formatted(blockSize));
        }
    }

    private SSTableFormat() {
    }
}
