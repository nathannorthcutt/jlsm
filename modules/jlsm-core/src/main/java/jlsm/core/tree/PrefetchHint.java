package jlsm.core.tree;

import java.lang.foreign.MemorySegment;
import java.util.Collection;

/**
 * Optional advisory interface that an {@link LsmTree} implementation may expose to allow callers
 * to warm its read cache before issuing a batch of point lookups.
 *
 * <p>Prefetching is particularly valuable on network-backed storage (NFS, distributed block
 * devices) where each uncached {@link LsmTree#get} can incur millisecond-scale round trips.
 * Callers — such as an HNSW traversal that knows which neighbor nodes it will visit next — can
 * issue a single non-blocking prefetch request to pipeline the I/O, then proceed to fetch the
 * values sequentially with most of them already cached.
 *
 * <p><strong>Contract:</strong> Implementations of this method must be non-blocking; the actual
 * prefetch may happen asynchronously or be silently ignored. Callers must never depend on
 * prefetching for correctness; it is purely a performance hint.
 *
 * <p>To use: check {@code lsmTree instanceof PrefetchHint ph} at the call site; if the tree
 * implements this interface, call {@link #prefetch} with the keys expected to be needed soon.
 */
public interface PrefetchHint {

    /**
     * Advises the implementation to warm its read cache for the given keys. The call is
     * non-blocking; actual prefetching may happen asynchronously or be ignored entirely.
     *
     * @param keys the keys whose values may be needed in the near future; must not be null
     */
    void prefetch(Collection<MemorySegment> keys);
}
