package jlsm.encryption;

/**
 * AutoCloseable handle for a convergence-callback registration. Idempotent close in all
 * post-registration-end paths per R37b-3: explicit close, holder close, GC reaping (when the
 * weak-reference is cleared), and fired-and-delivered.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R37b, R37b-3).
 *
 * @spec encryption.primitives-lifecycle R37b-3
 */
public interface ConvergenceRegistration extends AutoCloseable {

    /**
     * The current convergence state of this registration. Returns the latest observation; may
     * change between calls until a terminal state is reached.
     */
    ConvergenceState currentState();

    /**
     * Idempotent. Subsequent calls are no-ops; never throws checked exceptions per the narrowed
     * AutoCloseable contract.
     */
    @Override
    void close();
}
