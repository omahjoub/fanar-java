package qa.fanar.core;

/**
 * A failure in the HTTP transport layer before any Fanar response was successfully read.
 *
 * <p>Wraps transport-level failures such as {@link java.io.IOException} or
 * {@link InterruptedException} raised by the underlying HTTP client. No Fanar response was
 * observed, so {@link #code()} returns {@code null} and {@link #httpStatus()} returns
 * {@code -1}.</p>
 *
 * <p>For {@link InterruptedException} specifically, the transport layer also restores the
 * current thread's interrupt flag before throwing this exception.</p>
 */
public final class FanarTransportException extends FanarException {

    public FanarTransportException(String message) {
        super(message, null, -1);
    }

    public FanarTransportException(String message, Throwable cause) {
        super(message, null, -1, cause);
    }
}
