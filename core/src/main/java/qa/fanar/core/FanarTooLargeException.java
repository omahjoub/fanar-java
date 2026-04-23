package qa.fanar.core;

/**
 * Request exceeded size limits. Maps to {@link ErrorCode#TOO_LARGE} and HTTP 413.
 *
 * <p>Typical causes: input token count over the model's context window, uploaded audio file
 * exceeds the STT size cap. Not retryable without shrinking the request.</p>
 */
public final class FanarTooLargeException extends FanarClientException {

    public FanarTooLargeException(String message) {
        super(message, ErrorCode.TOO_LARGE, 413);
    }

    public FanarTooLargeException(String message, Throwable cause) {
        super(message, ErrorCode.TOO_LARGE, 413, cause);
    }
}
