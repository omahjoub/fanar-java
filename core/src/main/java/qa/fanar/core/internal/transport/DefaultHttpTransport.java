package qa.fanar.core.internal.transport;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import qa.fanar.core.FanarTransportException;

/**
 * Production {@link HttpTransport} backed by a JDK {@link HttpClient}.
 *
 * <p>Sends through {@code HttpClient.sendAsync} and waits for the response on the calling thread,
 * translating transport-layer failures ({@link java.io.IOException}, timeouts,
 * {@link InterruptedException}) into {@link FanarTransportException} so the rest of the SDK only
 * sees unchecked exceptions (ADR-006). Preserves the interrupt flag when the call is
 * interrupted.</p>
 *
 * <p><strong>The request timeout bounds the wait for response headers, on every JDK.</strong>
 * Once the status line and headers have arrived the call returns and the body — a JSON document
 * or a stream that may run for minutes — is read without a deadline; a stream is abandoned by
 * cancelling its publisher. The bound is applied here with a timed wait on the asynchronous
 * exchange rather than with {@code HttpRequest.Builder.timeout}, because the JDK's built-in
 * client changed what that timer covers: through JDK 25 it stops when the headers arrive, from
 * JDK 26 it runs until the response body has been consumed (documented in the
 * {@code @implNote} of {@code HttpRequest.Builder.timeout}), which would end any stream longer
 * than the timeout. When the timed wait expires the exchange is cancelled (ADR-007). A timeout an
 * interceptor set on the request itself is passed through to the JDK unchanged.</p>
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
        CompletableFuture<HttpResponse<InputStream>> inFlight =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            return requestTimeout == null
                    ? inFlight.get()
                    : inFlight.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            inFlight.cancel(true);
            HttpTimeoutException cause = new HttpTimeoutException(
                    "no response headers within " + requestTimeout);
            throw new FanarTransportException("HTTP request timed out: " + cause.getMessage(), cause);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new FanarTransportException("HTTP request failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            inFlight.cancel(true);
            Thread.currentThread().interrupt();
            throw new FanarTransportException("HTTP request interrupted", e);
        }
    }
}
