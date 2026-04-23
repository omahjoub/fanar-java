package qa.fanar.core;

/**
 * Request is syntactically valid but semantically unprocessable. Maps to
 * {@link ErrorCode#UNPROCESSABLE} and HTTP 422.
 *
 * <p>Typical cause: a combination of parameters that passes wire-format validation but violates
 * model-specific constraints. Not retryable without changing the request.</p>
 */
public final class FanarUnprocessableException extends FanarClientException {

    public FanarUnprocessableException(String message) {
        super(message, ErrorCode.UNPROCESSABLE, 422);
    }

    public FanarUnprocessableException(String message, Throwable cause) {
        super(message, ErrorCode.UNPROCESSABLE, 422, cause);
    }
}
