package qa.fanar.core;

/**
 * Fanar refused the request or response because the content-filter (moderation) layer flagged it.
 *
 * <p>Maps to wire error code {@link ErrorCode#CONTENT_FILTER} and HTTP status {@code 400}. When
 * the error envelope carries a {@code type} member, it is exposed as a {@link ContentFilterType}
 * via {@link #filterType()}. This is <em>not</em> retryable — the filter decision is deterministic
 * for a given input.</p>
 *
 * <p><strong>This exception may never reach you, and {@code filterType()} may never be populated.</strong>
 * Through 0.4.0 the accessor was dead API — the envelope parser dropped the member before the
 * exception was built (fixed 2026-09-15, ADR-006 amendment). It is wired now, but a live probe the
 * same day found that Fanar's moderation refuses <em>inside a 200</em>, in ordinary assistant text:
 * prompts written to trip a safety layer returned a normal {@code TextContent} decline, with no
 * {@code content_filter} error, no {@link qa.fanar.core.chat.FinishReason#CONTENT_FILTER} and no
 * {@link qa.fanar.core.chat.RefusalPart}. Handle refusals in the response body; treat this exception
 * and a populated {@code filterType()} as a bonus, not a contract. See the wire-observations
 * ledger.</p>
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
     *         one — {@code null} on every error envelope observed to date (see the class javadoc)
     */
    public ContentFilterType filterType() {
        return filterType;
    }
}
