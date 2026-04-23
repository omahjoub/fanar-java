package qa.fanar.core;

/**
 * Upstream timeout on the Fanar side. Maps to {@link ErrorCode#TIMEOUT} and HTTP 504.
 *
 * <p>Transient — the built-in retry interceptor handles this automatically with backoff. Not to
 * be confused with client-side socket timeouts, which surface as a {@link FanarTransportException}
 * wrapping an {@link java.net.http.HttpTimeoutException}.</p>
 */
public final class FanarTimeoutException extends FanarServerException {

    public FanarTimeoutException(String message) {
        super(message, ErrorCode.TIMEOUT, 504);
    }

    public FanarTimeoutException(String message, Throwable cause) {
        super(message, ErrorCode.TIMEOUT, 504, cause);
    }
}
