package jlsm.cluster;

import java.util.Objects;

/**
 * Identity and network address of a cluster node.
 *
 * <p>
 * Contract: Immutable value type uniquely identifying a node. The {@code nodeId} is the primary
 * identity; {@code host} and {@code port} describe the network endpoint. Two {@code NodeAddress}
 * instances are equal if and only if all three fields match.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 *
 * @param nodeId unique identifier for the node; must not be null or empty
 * @param host the hostname or IP address; must not be null or empty
 * @param port the port number; must be in range [1, 65535]
 *
 * @spec engine.clustering.R1 — node address = (nodeId, host, port); id/host non-null-non-empty;
 *       port [1,65535]
 * @spec transport.multiplexed-framing.R40 — wire form for the bidirectional handshake; nodeId
 *       length [1,256] bytes, well-formed UTF-8 per RFC 3629; host length [1,256] bytes; port in
 *       [1,65535]; equality is byte-equivalence on validated wire bytes (R23a tie-break comparand)
 */
public record NodeAddress(String nodeId, String host, int port) {

    public NodeAddress {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(host, "host must not be null");
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range [1, 65535], got: " + port);
        }
    }
}
