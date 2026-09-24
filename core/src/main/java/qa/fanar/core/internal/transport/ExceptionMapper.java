package qa.fanar.core.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import qa.fanar.core.ContentFilterType;
import qa.fanar.core.ErrorCode;
import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarClientClosedRequestException;
import qa.fanar.core.FanarConflictException;
import qa.fanar.core.FanarContentFilterException;
import qa.fanar.core.FanarException;
import qa.fanar.core.FanarGoneException;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarQuotaExceededException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTimeoutException;
import qa.fanar.core.FanarTooLargeException;
import qa.fanar.core.FanarUnexpectedClientException;
import qa.fanar.core.FanarUnexpectedServerException;
import qa.fanar.core.FanarUnprocessableException;
import qa.fanar.core.RateLimitInfo;

/**
 * Maps an error {@link HttpResponse} (status code ≥ 400) to the matching
 * {@link FanarException} subtype per ADR-006 and the Fanar OpenAPI spec.
 *
 * <p>Routing is two-stage. When the body is a well-formed Fanar error envelope
 * ({@code {"error":{"code":…,"message":…,"status":…}}}), the typed {@link ErrorCode} decides the
 * subtype — this is what distinguishes {@link FanarQuotaExceededException} (permanent) from
 * {@link FanarRateLimitException} (transient), both HTTP 429, and keeps a non-filter 400 from
 * masquerading as a {@link FanarContentFilterException}. When the body is anything else (blank,
 * HTML from an intermediary, truncated JSON) or carries an unknown code, the HTTP status decides.</p>
 *
 * <p>The envelope's {@code type} member becomes the {@link ContentFilterType} on
 * {@link FanarContentFilterException}, on both routes that build one — the typed
 * {@code content_filter} code and the HTTP-400 fallback. Mapping is permissive (ADR-015): a value
 * this SDK ships no constant for decodes into a {@code ContentFilterType} carrying the new wire
 * string. Absent, JSON-{@code null} and blank all mean "the server provided none" and yield
 * {@code null} (ADR-006).</p>
 *
 * <p>The two routes treat {@code type} differently on purpose. {@link #byCode} drops it for every
 * non-filter code, because a code this SDK recognises is a <em>better</em> signal than the status
 * and it says the error is not a content filter. {@link #byStatus} has no such signal — an
 * unrecognised code leaves only HTTP 400, which this ADR maps to content filtering — so the
 * envelope's {@code type} is the best information available and is carried, at exactly the trust
 * level the same route already extends to the envelope's {@code message}. The cost is that a future
 * 400-level code this SDK has not learned yet, arriving with a {@code type}, surfaces as a
 * {@code FanarContentFilterException} reporting that subtype; the coarse 400 → content-filter
 * mapping is the older half of that, and is ADR-006's to revisit.</p>
 *
 * <p>Reads and closes the response body. The exception message is the envelope's {@code message}
 * when present, the raw body text otherwise, falling back to a canonical status description when
 * both are blank. The {@code Retry-After} header is carried on both HTTP 429 subtypes
 * ({@link FanarRateLimitException}, {@link FanarQuotaExceededException}) after the
 * normalisation described on {@link #parseRetryAfterValue}, together with the rate-limit window
 * the response reports ({@link RateLimitHeaders}, ADR-026) — {@code null} when the headers are
 * absent.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class ExceptionMapper {

    private ExceptionMapper() {
        // not instantiable
    }

    public static FanarException map(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String body = readBody(response);
        ErrorEnvelope envelope = ErrorEnvelope.tryParse(body);
        String detail = detail(envelope, body, status);

        ErrorCode code = envelope == null ? null : tryFromWireValue(envelope.code());
        ContentFilterType filterType = filterType(envelope);
        Duration retryAfter = parseRetryAfter(response);
        RateLimitInfo rateLimit = rateLimit(response);
        return code != null
                ? byCode(code, detail, filterType, retryAfter, rateLimit)
                : byStatus(status, detail, filterType, retryAfter, rateLimit);
    }

    /**
     * Map an error envelope that arrived <em>inside</em> a stream — an SSE frame whose top-level
     * {@code error} the server sends once a run has failed after the 200 headers went out
     * (ADR-031). There is no HTTP status and there are no headers: the envelope's {@code code}
     * routes as on the HTTP path, an unknown or absent code falls back to the status the envelope
     * itself names, and a frame naming neither is an unexpected server failure carrying the raw
     * frame as its detail — never a decode failure, so the server's message survives.
     *
     * @param frame the raw {@code data:} payload
     * @return the typed exception for the subscriber's {@code onError}
     */
    public static FanarException mapEnvelope(String frame) {
        ErrorEnvelope envelope = ErrorEnvelope.tryParse(frame);
        ErrorCode code = envelope == null ? null : tryFromWireValue(envelope.code());
        Integer status = envelope == null ? null : envelope.status();
        String detail = detail(envelope, frame, status == null ? 500 : status);
        ContentFilterType filterType = filterType(envelope);
        if (code != null) {
            return byCode(code, detail, filterType, null, null);
        }
        if (status != null) {
            return byStatus(status, detail, filterType, null, null);
        }
        return new FanarUnexpectedServerException(detail, 500);
    }

    /** One subtype per {@link ErrorCode} (ADR-006); the server's typed code is authoritative. */
    private static FanarException byCode(ErrorCode code, String detail, ContentFilterType filterType,
                                         Duration retryAfter, RateLimitInfo rateLimit) {
        return switch (code) {
            case CONTENT_FILTER         -> new FanarContentFilterException(detail, filterType);
            case INVALID_AUTHENTICATION -> new FanarAuthenticationException(detail);
            case INVALID_AUTHORIZATION  -> new FanarAuthorizationException(detail);
            case RATE_LIMIT_REACHED     -> new FanarRateLimitException(detail, retryAfter, rateLimit);
            case EXCEEDED_QUOTA         -> new FanarQuotaExceededException(detail, retryAfter, rateLimit);
            case INTERNAL_SERVER_ERROR  -> new FanarInternalServerException(detail);
            case OVERLOADED             -> new FanarOverloadedException(detail);
            case TIMEOUT                -> new FanarTimeoutException(detail);
            case TOO_LARGE              -> new FanarTooLargeException(detail);
            case UNPROCESSABLE          -> new FanarUnprocessableException(detail);
            case CONFLICT               -> new FanarConflictException(detail);
            case NOT_FOUND              -> new FanarNotFoundException(detail);
            case NO_LONGER_SUPPORTED    -> new FanarGoneException(detail);
            case CLIENT_CLOSED_REQUEST  -> new FanarClientClosedRequestException(detail);
        };
    }

    private static FanarException byStatus(int status, String detail, ContentFilterType filterType,
                                           Duration retryAfter, RateLimitInfo rateLimit) {
        return switch (status) {
            case 400 -> new FanarContentFilterException(detail, filterType);
            case 401 -> new FanarAuthenticationException(detail);
            case 403 -> new FanarAuthorizationException(detail);
            case 404 -> new FanarNotFoundException(detail);
            case 409 -> new FanarConflictException(detail);
            case 410 -> new FanarGoneException(detail);
            case 413 -> new FanarTooLargeException(detail);
            case 422 -> new FanarUnprocessableException(detail);
            case 429 -> new FanarRateLimitException(detail, retryAfter, rateLimit);
            case 499 -> new FanarClientClosedRequestException(detail);
            case 500 -> new FanarInternalServerException(detail);
            case 503 -> new FanarOverloadedException(detail);
            case 504 -> new FanarTimeoutException(detail);
            // A status the Fanar wire contract does not declare. Route it by range so the 4xx/5xx
            // branch invariant holds (ADR-006): a 4xx is the caller's to fix and must not be
            // retried, a 5xx may succeed on a later attempt. Both carry the status as received and
            // a null code — no ErrorCode describes a response we have not modelled. The caller
            // only maps error responses (RetryInterceptor guards on status >= 400), so the lower
            // bound needs no second guard here.
            default -> status < 500
                    ? new FanarUnexpectedClientException(detail, status)
                    : new FanarUnexpectedServerException(detail, status);
        };
    }

    private static String detail(ErrorEnvelope envelope, String body, int status) {
        if (envelope != null && envelope.message() != null && !envelope.message().isBlank()) {
            return envelope.message();
        }
        return body.isBlank() ? defaultReason(status) : body;
    }

    /**
     * The envelope's content-filter subtype, or {@code null} when the server provided none —
     * no envelope, no {@code type} member, a JSON {@code null}, or a blank string. The blank case
     * follows the same rule {@link #detail} already applies to {@code message}: a blank member
     * carries no information, so it reads as absent rather than as a {@code ContentFilterType("")}.
     */
    private static ContentFilterType filterType(ErrorEnvelope envelope) {
        if (envelope == null || envelope.type() == null || envelope.type().isBlank()) {
            return null;
        }
        return ContentFilterType.of(envelope.type());
    }

    private static ErrorCode tryFromWireValue(String wireValue) {
        try {
            return ErrorCode.fromWireValue(wireValue);
        } catch (IllegalArgumentException e) {
            // A code this SDK version doesn't know (newer server) — fall back to status routing.
            return null;
        }
    }

    private static String readBody(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static RateLimitInfo rateLimit(HttpResponse<InputStream> response) {
        return RateLimitHeaders.parse(response.headers());
    }

    private static Duration parseRetryAfter(HttpResponse<InputStream> response) {
        return response.headers().firstValue("Retry-After")
                .map(ExceptionMapper::parseRetryAfterValue)
                .orElse(null);
    }

    /**
     * Normalise a {@code Retry-After} value (RFC 9110 §10.2.3: {@code delay-seconds} or an
     * HTTP-date) into a wait duration. A non-positive delay, a date already past, or anything
     * unparseable carries no scheduling information and yields {@code null} — the retry loop
     * then falls back to its computed backoff instead of re-requesting immediately (ADR-025).
     *
     * @param value the raw header value
     * @return the positive wait the server asked for, or {@code null}
     */
    static Duration parseRetryAfterValue(String value) {
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            return parseHttpDate(trimmed);
        }
    }

    private static Duration parseHttpDate(String value) {
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration until = Duration.between(Instant.now(), at);
            return until.isPositive() ? until : null;
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private static String defaultReason(int status) {
        return switch (status) {
            case 400 -> "Content filtered";
            case 401 -> "Invalid authentication";
            case 403 -> "Invalid authorization";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 410 -> "No longer supported";
            case 413 -> "Request entity too large";
            case 422 -> "Unprocessable entity";
            case 429 -> "Rate limit reached";
            case 499 -> "Client closed request";
            case 500 -> "Internal server error";
            case 503 -> "Service overloaded";
            case 504 -> "Upstream timeout";
            default -> "HTTP " + status;
        };
    }
}
