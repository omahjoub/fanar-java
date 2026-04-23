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

    /** Number of retry attempts made by the built-in retry interceptor. Zero on first success. */
    public static final String FANAR_RETRY_COUNT = "fanar.retry_count";

    /** For streaming operations, total number of {@code StreamEvent}s emitted. */
    public static final String FANAR_STREAM_CHUNKS = "fanar.stream.chunks";

    /**
     * For streaming operations, milliseconds between the request being sent and the first
     * {@code StreamEvent} arriving.
     */
    public static final String FANAR_STREAM_FIRST_CHUNK_MS = "fanar.stream.first_chunk_ms";

    private FanarObservationAttributes() {
        // not instantiable
    }
}
