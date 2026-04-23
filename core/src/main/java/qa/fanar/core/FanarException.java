package qa.fanar.core;

import java.util.Objects;

/**
 * Root of the Fanar exception hierarchy.
 *
 * <p>Every error raised by the SDK — whether it originates from the Fanar API or from the HTTP
 * transport beneath it — is an unchecked exception rooted at {@code FanarException}. Callers
 * handle errors by catching the most specific subtype they care about, or {@code FanarException}
 * itself at the outer boundary.</p>
 *
 * <p>The hierarchy is {@code sealed}, so a {@code switch} over {@code FanarException} subtypes is
 * exhaustively verified by the compiler:</p>
 *
 * <pre>{@code
 * switch (e) {
 *     case FanarTransportException t      -> retryTransport(t);
 *     case FanarContentFilterException cf -> showRefusalUi(cf.filterType());
 *     case FanarClientException c         -> log.warn("client error", c);
 *     case FanarServerException s         -> backoffAndRetry(s);
 * }
 * }</pre>
 *
 * <p>Each exception carries a typed {@link ErrorCode} (except transport-level failures, where it
 * is {@code null}) and the HTTP status code it maps to.</p>
 */
public abstract sealed class FanarException extends RuntimeException
        permits FanarClientException, FanarServerException,
                FanarTransportException, FanarContentFilterException {

    /** Nullable for transport-level failures where no server response was observed. */
    private final ErrorCode code;
    private final int httpStatus;

    /**
     * Construct a Fanar exception.
     *
     * @param message    human-readable message; must not be {@code null}
     * @param code       typed Fanar error code; {@code null} only for transport-level failures
     * @param httpStatus HTTP status code, or {@code -1} if not applicable (transport failures)
     */
    protected FanarException(String message, ErrorCode code, int httpStatus) {
        super(Objects.requireNonNull(message, "message"));
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** Variant that also carries a {@code cause}. */
    protected FanarException(String message, ErrorCode code, int httpStatus, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * The typed Fanar error code for this exception.
     *
     * @return the error code, or {@code null} for transport-level exceptions where no response
     *         was observed
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * HTTP status code that produced this exception.
     *
     * @return the status, or {@code -1} if no response was observed (for example, a
     *         {@link FanarTransportException} raised before any HTTP response was read)
     */
    public int httpStatus() {
        return httpStatus;
    }
}
