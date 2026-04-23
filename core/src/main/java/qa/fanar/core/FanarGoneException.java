package qa.fanar.core;

/**
 * Feature, model, or endpoint is no longer supported. Maps to
 * {@link ErrorCode#NO_LONGER_SUPPORTED} and HTTP 410.
 *
 * <p>Permanent — retry will not help. Typical cause: using a deprecated model identifier that
 * has since been removed. Consult the Fanar release notes or {@code /v1/models} for current
 * alternatives.</p>
 */
public final class FanarGoneException extends FanarClientException {

    public FanarGoneException(String message) {
        super(message, ErrorCode.NO_LONGER_SUPPORTED, 410);
    }

    public FanarGoneException(String message, Throwable cause) {
        super(message, ErrorCode.NO_LONGER_SUPPORTED, 410, cause);
    }
}
