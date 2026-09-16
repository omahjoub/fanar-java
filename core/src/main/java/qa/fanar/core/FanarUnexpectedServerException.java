package qa.fanar.core;

/**
 * A 5xx — or otherwise unexpected — status this SDK version does not model.
 *
 * <p>The server-side counterpart to {@link FanarUnexpectedClientException}: a {@code 502} from a
 * gateway, a {@code 507}, or any status outside the 4xx range that the Fanar wire contract does
 * not declare.</p>
 *
 * <p>{@link #code()} is {@code null} because no Fanar {@link ErrorCode} describes the response;
 * {@link #httpStatus()} is the status as received. Like every {@link FanarServerException} it is
 * retried by default — the request may well succeed on the next attempt.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarUnexpectedServerException extends FanarServerException {

    /**
     * @param message    human-readable message; must not be {@code null}
     * @param httpStatus the status as received
     */
    public FanarUnexpectedServerException(String message, int httpStatus) {
        super(message, null, httpStatus);
    }

    /**
     * @param message    human-readable message; must not be {@code null}
     * @param httpStatus the status as received
     * @param cause      the underlying failure
     */
    public FanarUnexpectedServerException(String message, int httpStatus, Throwable cause) {
        super(message, null, httpStatus, cause);
    }
}
