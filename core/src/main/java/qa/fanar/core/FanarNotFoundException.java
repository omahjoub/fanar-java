package qa.fanar.core;

/**
 * Requested resource does not exist. Maps to {@link ErrorCode#NOT_FOUND} and HTTP 404.
 *
 * <p>Typical causes: unknown model identifier, unknown personalized voice name, misspelled
 * endpoint. Not retryable.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarNotFoundException extends FanarClientException {

    public FanarNotFoundException(String message) {
        super(message, ErrorCode.NOT_FOUND, 404);
    }

    public FanarNotFoundException(String message, Throwable cause) {
        super(message, ErrorCode.NOT_FOUND, 404, cause);
    }
}
