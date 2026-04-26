package qa.fanar.core;

/**
 * Fanar backend is temporarily overloaded. Maps to {@link ErrorCode#OVERLOADED} and HTTP 503.
 *
 * <p>Transient — the built-in retry interceptor handles this automatically with backoff.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarOverloadedException extends FanarServerException {

    public FanarOverloadedException(String message) {
        super(message, ErrorCode.OVERLOADED, 503);
    }

    public FanarOverloadedException(String message, Throwable cause) {
        super(message, ErrorCode.OVERLOADED, 503, cause);
    }
}
