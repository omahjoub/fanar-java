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
        return code != null ? byCode(code, detail, response) : byStatus(status, detail, response);
    }

    /** One subtype per {@link ErrorCode} (ADR-006); the server's typed code is authoritative. */
    private static FanarException byCode(ErrorCode code, String detail, HttpResponse<InputStream> response) {
        return switch (code) {
            case CONTENT_FILTER         -> new FanarContentFilterException(detail);
            case INVALID_AUTHENTICATION -> new FanarAuthenticationException(detail);
            case INVALID_AUTHORIZATION  -> new FanarAuthorizationException(detail);
            case RATE_LIMIT_REACHED     -> new FanarRateLimitException(detail, parseRetryAfter(response), rateLimit(response));
            case EXCEEDED_QUOTA         -> new FanarQuotaExceededException(detail, parseRetryAfter(response), rateLimit(response));
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

    private static FanarException byStatus(int status, String detail, HttpResponse<InputStream> response) {
        return switch (status) {
            case 400 -> new FanarContentFilterException(detail);
            case 401 -> new FanarAuthenticationException(detail);
            case 403 -> new FanarAuthorizationException(detail);
            case 404 -> new FanarNotFoundException(detail);
            case 409 -> new FanarConflictException(detail);
            case 410 -> new FanarGoneException(detail);
            case 413 -> new FanarTooLargeException(detail);
            case 422 -> new FanarUnprocessableException(detail);
            case 429 -> new FanarRateLimitException(detail, parseRetryAfter(response), rateLimit(response));
            case 499 -> new FanarClientClosedRequestException(detail);
            case 500 -> new FanarInternalServerException(detail);
            case 503 -> new FanarOverloadedException(detail);
            case 504 -> new FanarTimeoutException(detail);
            default -> new FanarInternalServerException("HTTP " + status + ": " + detail);
        };
    }

    private static String detail(ErrorEnvelope envelope, String body, int status) {
        if (envelope != null && envelope.message() != null && !envelope.message().isBlank()) {
            return envelope.message();
        }
        return body.isBlank() ? defaultReason(status) : body;
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
