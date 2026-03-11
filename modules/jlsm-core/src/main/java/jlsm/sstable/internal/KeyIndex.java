package jlsm.sstable.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An in-memory trie (prefix tree) mapping keys to absolute file offsets. Built from a sorted flat
 * list of (key, offset) pairs and provides O(|key|) point lookup and in-order iteration.
 *
 * <p>
 * The trie uses sparse children (sorted {@code edges[]} + parallel {@code children[]}) for space
 * efficiency with binary search on edges at each node.
 */
public final class KeyIndex {

    /** A key-offset pair produced during iteration. */
    public record Entry(MemorySegment key, long fileOffset) {
    }

    // --- Trie node types ---

    private sealed interface TrieNode permits Inner, Leaf {
    }

    private record Leaf(long fileOffset) implements TrieNode {
    }

    private static final class Inner implements TrieNode {
        byte[] edges; // sorted (unsigned) byte values of outgoing edges
        TrieNode[] children; // parallel with edges
        long fileOffset; // -1L if this node is not terminal

        Inner() {
            this.edges = new byte[0];
            this.children = new TrieNode[0];
            this.fileOffset = -1L;
        }

        TrieNode get(byte b) {
            int idx = binarySearch(b);
            return idx >= 0 ? children[idx] : null;
        }

        void put(byte b, TrieNode child) {
            int idx = binarySearch(b);
            if (idx >= 0) {
                children[idx] = child;
            } else {
                int ins = -(idx + 1);
                byte[] newEdges = new byte[edges.length + 1];
                TrieNode[] newChildren = new TrieNode[children.length + 1];
                System.arraycopy(edges, 0, newEdges, 0, ins);
                System.arraycopy(children, 0, newChildren, 0, ins);
                newEdges[ins] = b;
                newChildren[ins] = child;
                System.arraycopy(edges, ins, newEdges, ins + 1, edges.length - ins);
                System.arraycopy(children, ins, newChildren, ins + 1, children.length - ins);
                edges = newEdges;
                children = newChildren;
            }
        }

        private int binarySearch(byte b) {
            int unsignedB = b & 0xFF;
            int lo = 0, hi = edges.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int midVal = edges[mid] & 0xFF;
                if (midVal == unsignedB)
                    return mid;
                if (midVal < unsignedB)
                    lo = mid + 1;
                else
                    hi = mid - 1;
            }
            return -(lo + 1);
        }
    }

    private final Inner root;

    /**
     * Constructs a {@code KeyIndex} from parallel sorted lists of keys and file offsets.
     *
     * @param keys sorted list of unique keys; must not be null
     * @param offsets parallel list of file offsets; same size as keys
     */
    public KeyIndex(List<MemorySegment> keys, List<Long> offsets) {
        Objects.requireNonNull(keys, "keys must not be null");
        Objects.requireNonNull(offsets, "offsets must not be null");
        assert keys.size() == offsets.size() : "keys and offsets must have the same size";
        this.root = new Inner();
        for (int i = 0; i < keys.size(); i++) {
            insert(keys.get(i), offsets.get(i));
        }
    }

    private void insert(MemorySegment key, long offset) {
        assert key != null : "key must not be null";
        byte[] bytes = key.toArray(ValueLayout.JAVA_BYTE);

        if (bytes.length == 0) {
            root.fileOffset = offset;
            return;
        }

        Inner current = root;
        for (int d = 0; d < bytes.length - 1; d++) {
            byte b = bytes[d];
            TrieNode child = current.get(b);
            if (child == null) {
                Inner next = new Inner();
                current.put(b, next);
                current = next;
            } else if (child instanceof Inner inner) {
                current = inner;
            } else {
                // Leaf at intermediate position — promote to Inner
                Leaf leaf = (Leaf) child;
                Inner next = new Inner();
                next.fileOffset = leaf.fileOffset();
                current.put(b, next);
                current = next;
            }
        }

        // Last byte
        byte lastByte = bytes[bytes.length - 1];
        TrieNode existing = current.get(lastByte);
        if (existing == null) {
            current.put(lastByte, new Leaf(offset));
        } else if (existing instanceof Inner inner) {
            inner.fileOffset = offset;
        } else {
            // Replace leaf
            current.put(lastByte, new Leaf(offset));
        }
    }

    /**
     * Looks up the file offset for an exact key match.
     *
     * @param key the key to search for; must not be null
     * @return the file offset, or empty if not found
     */
    public Optional<Long> lookup(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");
        byte[] bytes = key.toArray(ValueLayout.JAVA_BYTE);

        if (bytes.length == 0) {
            return root.fileOffset >= 0 ? Optional.of(root.fileOffset) : Optional.empty();
        }

        Inner current = root;
        for (int d = 0; d < bytes.length - 1; d++) {
            TrieNode child = current.get(bytes[d]);
            if (child == null)
                return Optional.empty();
            switch (child) {
                case Leaf _ -> {
                    return Optional.empty();
                }
                case Inner inner -> current = inner;
            }
        }

        TrieNode last = current.get(bytes[bytes.length - 1]);
        if (last == null)
            return Optional.empty();
        return switch (last) {
            case Leaf leaf -> Optional.of(leaf.fileOffset());
            case Inner inner ->
                inner.fileOffset >= 0 ? Optional.of(inner.fileOffset) : Optional.empty();
        };
    }

    /** Returns an in-order iterator over all (key, offset) pairs. */
    public Iterator<Entry> iterator() {
        List<Entry> result = new ArrayList<>();
        dfs(root, new byte[0], result, null, null);
        return result.iterator();
    }

    /**
     * Returns an iterator over entries whose keys fall in {@code [from, to)}.
     *
     * @param from inclusive lower bound; must not be null
     * @param to exclusive upper bound; must not be null
     */
    public Iterator<Entry> rangeIterator(MemorySegment from, MemorySegment to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        byte[] fromBytes = from.toArray(ValueLayout.JAVA_BYTE);
        byte[] toBytes = to.toArray(ValueLayout.JAVA_BYTE);
        List<Entry> result = new ArrayList<>();
        dfs(root, new byte[0], result, fromBytes, toBytes);
        return result.iterator();
    }

    /** Typed stack frame for the iterative DFS. */
    private record Frame(TrieNode node, byte[] prefix) {
    }

    /**
     * DFS in sorted edge order, collecting entries in [fromKey, toKey). Iterative implementation.
     */
    private static void dfs(TrieNode startNode, byte[] startPrefix, List<Entry> result,
            byte[] fromKey, byte[] toKey) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(startNode, startPrefix));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            TrieNode node = frame.node();
            byte[] prefix = frame.prefix();

            switch (node) {
                case Leaf leaf -> {
                    if (inRange(prefix, fromKey, toKey)) {
                        result.add(new Entry(MemorySegment.ofArray(prefix), leaf.fileOffset()));
                    }
                }
                case Inner inner -> {
                    // Check terminal
                    if (inner.fileOffset >= 0 && inRange(prefix, fromKey, toKey)) {
                        result.add(new Entry(MemorySegment.ofArray(prefix), inner.fileOffset));
                    }
                    // Push children in REVERSE order so leftmost child is processed first (LIFO).
                    // Determine the last index to push: find the rightmost edge where childPrefix
                    // is still < toKey (edges are sorted ascending, so prune from the right).
                    int lastValid = inner.edges.length - 1;
                    if (toKey != null) {
                        while (lastValid >= 0) {
                            byte[] childPrefix = Arrays.copyOf(prefix, prefix.length + 1);
                            childPrefix[prefix.length] = inner.edges[lastValid];
                            if (compareUnsigned(childPrefix, toKey) < 0)
                                break;
                            lastValid--;
                        }
                    }
                    for (int i = lastValid; i >= 0; i--) {
                        byte[] childPrefix = Arrays.copyOf(prefix, prefix.length + 1);
                        childPrefix[prefix.length] = inner.edges[i];
                        stack.push(new Frame(inner.children[i], childPrefix));
                    }
                }
            }
        }
    }

    private static boolean inRange(byte[] key, byte[] fromKey, byte[] toKey) {
        if (fromKey != null && compareUnsigned(key, fromKey) < 0)
            return false;
        if (toKey != null && compareUnsigned(key, toKey) >= 0)
            return false;
        return true;
    }

    static int compareUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0)
                return cmp;
        }
        return Integer.compare(a.length, b.length);
    }
}
