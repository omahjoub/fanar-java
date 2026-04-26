package qa.fanar.core;

import java.util.Objects;

/**
 * Typed error codes returned by the Fanar API.
 *
 * <p>Mirrors the {@code ErrorCode} enum in the Fanar OpenAPI specification. Wire values are the
 * strings Fanar transmits on the wire; prefer the typed enum in code and convert at the
 * serialization boundary via {@link #wireValue()} and {@link #fromWireValue(String)}.</p>
 *
 * @author Oussama Mahjoub
 */
public enum ErrorCode {

    /** Content was blocked by the Fanar safety or moderation layer. */
    CONTENT_FILTER("content_filter"),

    /** API key missing or invalid. */
    INVALID_AUTHENTICATION("invalid_authentication"),

    /** API key valid but not authorized for the requested operation or model. */
    INVALID_AUTHORIZATION("invalid_authorization"),

    /** Rate limit hit. Transient — retry with backoff. */
    RATE_LIMIT_REACHED("rate_limit_reached"),

    /** Quota exhausted. Permanent until reset — do not retry automatically. */
    EXCEEDED_QUOTA("exceeded_quota"),

    /** Fanar-side internal failure. May be transient. */
    INTERNAL_SERVER_ERROR("internal_server_error"),

    /** Backend overloaded. Transient — retry with backoff. */
    OVERLOADED("overloaded"),

    /** Upstream timeout. Transient — retry with backoff. */
    TIMEOUT("timeout"),

    /** Request body exceeded size limits. */
    TOO_LARGE("too_large"),

    /** Request syntactically valid but semantically unprocessable. */
    UNPROCESSABLE("unprocessable"),

    /** Resource state conflict (for example, duplicate personalized-voice name). */
    CONFLICT("conflict"),

    /**
     * Resource not found.
     *
     * <p>The wire value uses a capital {@code N} and a space (exactly {@code "Not found"}),
     * matching the Fanar OpenAPI spec.</p>
     */
    NOT_FOUND("Not found"),

    /** Feature, model, or endpoint no longer supported. */
    NO_LONGER_SUPPORTED("no_longer_supported");

    private final String wireValue;

    ErrorCode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar uses on the wire for this error code. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Look up an enum value by its wire format.
     *
     * @param value the wire-format string; must not be {@code null}
     * @return the matching enum value
     * @throws IllegalArgumentException if no enum value matches
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    public static ErrorCode fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (ErrorCode code : values()) {
            if (code.wireValue.equals(value)) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unknown ErrorCode wire value: " + value);
    }
}
