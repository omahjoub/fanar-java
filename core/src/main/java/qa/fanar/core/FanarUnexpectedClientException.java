package qa.fanar.core;

/**
 * A 4xx status this SDK version does not model.
 *
 * <p>Every status the Fanar wire contract declares has its own subtype. This one carries the
 * remainder: a 4xx that reached the caller from outside that contract — a proxy answering
 * {@code 407}, a gateway answering {@code 405}, a future Fanar status this build predates.</p>
 *
 * <p>{@link #code()} is {@code null} because no Fanar {@link ErrorCode} describes the response;
 * {@link #httpStatus()} is the status as received, never a substitute. Like every
 * {@link FanarClientException} it is <em>not</em> retried by default — retrying a request the
 * other side rejected changes nothing.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarUnexpectedClientException extends FanarClientException {

    /**
     * @param message    human-readable message; must not be {@code null}
     * @param httpStatus the 4xx status as received
     */
    public FanarUnexpectedClientException(String message, int httpStatus) {
        super(message, null, httpStatus);
    }

    /**
     * @param message    human-readable message; must not be {@code null}
     * @param httpStatus the 4xx status as received
     * @param cause      the underlying failure
     */
    public FanarUnexpectedClientException(String message, int httpStatus, Throwable cause) {
        super(message, null, httpStatus, cause);
    }
}
