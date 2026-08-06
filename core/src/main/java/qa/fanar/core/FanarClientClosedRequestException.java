package qa.fanar.core;

/**
 * The client closed the connection before Fanar finished responding. Maps to
 * {@link ErrorCode#CLIENT_CLOSED_REQUEST} and HTTP 499.
 *
 * <p>Typical cause: the caller cancelled or timed out the request mid-flight (for example, a
 * subscriber cancelling a stream). The request was abandoned deliberately, so it is not
 * retryable.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarClientClosedRequestException extends FanarClientException {

    public FanarClientClosedRequestException(String message) {
        super(message, ErrorCode.CLIENT_CLOSED_REQUEST, 499);
    }

    public FanarClientClosedRequestException(String message, Throwable cause) {
        super(message, ErrorCode.CLIENT_CLOSED_REQUEST, 499, cause);
    }
}
