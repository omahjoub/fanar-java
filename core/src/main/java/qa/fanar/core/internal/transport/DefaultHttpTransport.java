package qa.fanar.core.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import qa.fanar.core.FanarTransportException;

/**
 * Production {@link HttpTransport} backed by a JDK {@link HttpClient}.
 *
 * <p>Wraps {@code HttpClient.send} and translates transport-layer failures
 * ({@link IOException}, {@link InterruptedException}) into
 * {@link FanarTransportException} so the rest of the SDK only sees unchecked exceptions
 * (ADR-006). Preserves the interrupt flag when the call is interrupted.</p>
 *
 * <p>Applies the per-request timeout by cloning the inbound request with {@code .timeout(...)}
 * when the builder-configured timeout is non-null.</p>
 *
 * @author Oussama Mahjoub
 */
public final class DefaultHttpTransport implements HttpTransport {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public DefaultHttpTransport(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout;
    }

    @Override
    public HttpResponse<InputStream> send(HttpRequest request) {
        HttpRequest effective = requestTimeout == null
                ? request
                : HttpRequest.newBuilder(request, (n, v) -> true).timeout(requestTimeout).build();
        try {
            return httpClient.send(effective, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new FanarTransportException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FanarTransportException("HTTP request interrupted", e);
        }
    }
}
