package qa.fanar.core;

/**
 * Fanar refused the request or response because the content-filter (moderation) layer flagged it.
 *
 * <p>Maps to wire error code {@link ErrorCode#CONTENT_FILTER} and HTTP status {@code 400}. When
 * available, the server-provided {@link ContentFilterType} is exposed via {@link #filterType()}.
 * This is <em>not</em> retryable — the filter decision is deterministic for a given input.</p>
 *
 * <p>Deliberately a direct child of {@link FanarException} (not of
 * {@link FanarClientException}), because content filtering is semantically distinct from other
 * 4xx errors: the request reached Fanar and was understood, but the response was withheld.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarContentFilterException extends FanarException {

    /** Nullable — server may omit the filter-type discriminator. */
    private final ContentFilterType filterType;

    public FanarContentFilterException(String message) {
        this(message, null);
    }

    public FanarContentFilterException(String message, ContentFilterType filterType) {
        super(message, ErrorCode.CONTENT_FILTER, 400);
        this.filterType = filterType;
    }

    public FanarContentFilterException(String message, ContentFilterType filterType, Throwable cause) {
        super(message, ErrorCode.CONTENT_FILTER, 400, cause);
        this.filterType = filterType;
    }

    /**
     * @return the server-reported filter subtype, or {@code null} if the server did not provide
     *         one
     */
    public ContentFilterType filterType() {
        return filterType;
    }
}
