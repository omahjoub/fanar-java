package qa.fanar.core.spi;

/**
 * Canonical attribute names the SDK records on {@link ObservationHandle#attribute}.
 *
 * <p>Adapter authors map these names to their backend's conventions. Names follow the
 * OpenTelemetry semantic-convention style: lowercase, dot-separated, namespace-prefixed. The
 * {@code http.*} attributes match
 * <a href="https://opentelemetry.io/docs/specs/semconv/http/">OpenTelemetry HTTP semantic
 * conventions</a>. The {@code fanar.*} attributes are SDK-specific.</p>
 *
 * <p>Adding a new canonical attribute is a minor-version change. Renaming or removing one is a
 * breaking change subject to the deprecation discipline in JLBP-7.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarObservationAttributes {

    /** HTTP method of the outbound request (for example {@code "POST"}). */
    public static final String HTTP_METHOD = "http.method";

    /** Full request URL, including the Fanar base URL and endpoint path. */
    public static final String HTTP_URL = "http.url";

    /** Numeric HTTP status code returned by Fanar. */
    public static final String HTTP_STATUS_CODE = "http.status_code";

    /** Fanar model identifier used for this operation (for example {@code "Fanar-C-2-27B"}). */
    public static final String FANAR_MODEL = "fanar.model";

    /**
     * Number of retries the built-in retry interceptor performed for this call. Recorded on every
     * completed call — {@code 0} when the first attempt succeeded or its failure was not retried.
     */
    public static final String FANAR_RETRY_COUNT = "fanar.retry_count";

    /** For streaming operations, total number of {@code StreamEvent}s emitted. */
    public static final String FANAR_STREAM_CHUNKS = "fanar.stream.chunks";

    /**
     * For streaming operations, milliseconds between the request being sent and the first
     * {@code StreamEvent} arriving.
     */
    public static final String FANAR_STREAM_FIRST_CHUNK_MS = "fanar.stream.first_chunk_ms";

    /**
     * Requests allowed in the rate-limit window of the model a call addressed
     * ({@code x-ratelimit-limit}). Recorded at the retry boundary whenever the server reports it —
     * on successes and 429s alike, the last attempt's value winning; absent on non-model calls, on
     * rejections before admission and for unlimited-quota keys. Added in 0.4.0 (ADR-026).
     */
    public static final String FANAR_RATELIMIT_LIMIT = "fanar.ratelimit.limit";

    /**
     * Requests still available in the window ({@code x-ratelimit-remaining}). Unbounded value
     * space — adapters that feed metric tags treat it as high-cardinality. Added in 0.4.0 (ADR-026).
     */
    public static final String FANAR_RATELIMIT_REMAINING = "fanar.ratelimit.remaining";

    /**
     * Seconds until a slot frees ({@code x-ratelimit-reset}) — until the oldest counted request
     * ages out of the sliding window, never a window boundary. Unbounded value space —
     * high-cardinality. Added in 0.4.0 (ADR-026).
     */
    public static final String FANAR_RATELIMIT_RESET = "fanar.ratelimit.reset";

    /**
     * The raw {@code ratelimit-policy} value, for example {@code "50;w=60"} — one value per model,
     * so bounded. Added in 0.4.0 (ADR-026).
     */
    public static final String FANAR_RATELIMIT_POLICY = "fanar.ratelimit.policy";

    private FanarObservationAttributes() {
        // not instantiable
    }
}
