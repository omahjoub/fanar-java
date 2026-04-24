package qa.fanar.core.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarConflictException;
import qa.fanar.core.FanarContentFilterException;
import qa.fanar.core.FanarException;
import qa.fanar.core.FanarGoneException;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTimeoutException;
import qa.fanar.core.FanarTooLargeException;
import qa.fanar.core.FanarUnprocessableException;

/**
 * Maps an error {@link HttpResponse} (status code ≥ 400) to the matching
 * {@link FanarException} subtype per ADR-006 and the Fanar OpenAPI spec.
 *
 * <p>This first pass distinguishes exceptions by HTTP status only. A later PR will parse the
 * typed {@code ErrorCode} from the response body so we can, for example, distinguish
 * {@link FanarRateLimitException} (transient) from {@code FanarQuotaExceededException}
 * (permanent) — both HTTP 429. For now HTTP 429 always maps to rate-limit, which is the safe
 * default (the retry interceptor will give up after the configured attempt count regardless).</p>
 *
 * <p>Reads and closes the response body. The error message is the body text when non-blank,
 * falling back to a canonical status description otherwise. The {@code Retry-After} header is
 * honoured for HTTP 429.</p>
 *
 * <p>Internal (ADR-018).</p>
 */
public final class ExceptionMapper {

    private ExceptionMapper() {
        // not instantiable
    }

    public static FanarException map(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String body = readBody(response);
        String detail = body.isBlank() ? defaultReason(status) : body;

        return switch (status) {
            case 400 -> new FanarContentFilterException(detail);
            case 401 -> new FanarAuthenticationException(detail);
            case 403 -> new FanarAuthorizationException(detail);
            case 404 -> new FanarNotFoundException(detail);
            case 409 -> new FanarConflictException(detail);
            case 410 -> new FanarGoneException(detail);
            case 413 -> new FanarTooLargeException(detail);
            case 422 -> new FanarUnprocessableException(detail);
            case 429 -> new FanarRateLimitException(detail, parseRetryAfter(response));
            case 500 -> new FanarInternalServerException(detail);
            case 503 -> new FanarOverloadedException(detail);
            case 504 -> new FanarTimeoutException(detail);
            default -> new FanarInternalServerException("HTTP " + status + ": " + detail);
        };
    }

    private static String readBody(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Duration parseRetryAfter(HttpResponse<InputStream> response) {
        return response.headers().firstValue("Retry-After")
                .map(ExceptionMapper::tryParseSeconds)
                .orElse(null);
    }

    private static Duration tryParseSeconds(String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            // The HTTP spec also permits an HTTP-date here; unsupported for now — fall through.
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
            case 500 -> "Internal server error";
            case 503 -> "Service overloaded";
            case 504 -> "Upstream timeout";
            default -> "HTTP " + status;
        };
    }
}
