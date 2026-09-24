package qa.fanar.core.internal.transport;

import java.io.IOException;
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
import java.util.function.Function;

import qa.fanar.core.FanarTransportException;

/**
 * Production {@link HttpTransport} backed by a JDK {@link HttpClient}.
 *
 * <p>Sends through {@code HttpClient.sendAsync} and waits for the response on the calling thread,
 * translating transport-layer failures ({@link java.io.IOException}, timeouts,
 * {@link InterruptedException}) into {@link FanarTransportException} so the rest of the SDK only
 * sees unchecked exceptions (ADR-006). Preserves the interrupt flag when the call is
 * interrupted. A {@link RuntimeException} or {@link Error} the exchange fails with is rethrown
 * as it is — the same surface {@code HttpClient.send} presents, so a programming error never
 * becomes a retryable transport failure.</p>
 *
 * <p><strong>The request timeout bounds the wait for response headers, on every JDK.</strong>
 * Once the status line and headers have arrived the call returns and the body — a JSON document
 * or a stream that may run for minutes — is read without a deadline; a stream is abandoned by
 * cancelling its publisher. The bound is applied here with a timed wait on the asynchronous
 * exchange rather than with {@code HttpRequest.Builder.timeout}, because the JDK's built-in
 * client changed what that timer covers: through JDK 25 it stops when the headers arrive, from
 * JDK 26 it runs until the response body has been consumed (documented in the
 * {@code @implNote} of {@code HttpRequest.Builder.timeout}), which would end any stream longer
 * than the timeout. When the timed wait expires the exchange is cancelled; if the headers landed
 * in the same instant, the response that can no longer be returned is closed so its connection
 * is released (ADR-007). A timeout an interceptor set on the request itself is passed through to
 * the JDK unchanged.</p>
 *
 * @author Oussama Mahjoub
 */
public final class DefaultHttpTransport implements HttpTransport {

    private final Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> sender;
    private final Duration requestTimeout;

    public DefaultHttpTransport(HttpClient httpClient, Duration requestTimeout) {
        this(sender(Objects.requireNonNull(httpClient, "httpClient")), requestTimeout);
    }

    /**
     * The seam the tests use: {@code sender} stands in for {@code HttpClient.sendAsync}, so the
     * outcome of the exchange — and its timing against the wait — can be scripted.
     */
    DefaultHttpTransport(Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> sender,
                         Duration requestTimeout) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.requestTimeout = requestTimeout;
    }

    private static Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> sender(HttpClient client) {
        return request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    @Override
    public HttpResponse<InputStream> send(HttpRequest request) {
        CompletableFuture<HttpResponse<InputStream>> inFlight = sender.apply(request);
        try {
            return requestTimeout == null
                    ? inFlight.get()
                    : inFlight.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon(inFlight);
            HttpTimeoutException cause = new HttpTimeoutException(
                    "no response headers within " + requestTimeout);
            throw new FanarTransportException("HTTP request timed out: " + cause.getMessage(), cause);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new FanarTransportException("HTTP request failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            abandon(inFlight);
            Thread.currentThread().interrupt();
            throw new FanarTransportException("HTTP request interrupted", e);
        }
    }

    /**
     * Give up on an exchange: cancel it, and if it had already completed with a response by the
     * time we gave up, close that response's body so the connection is not left busy.
     */
    private static void abandon(CompletableFuture<HttpResponse<InputStream>> inFlight) {
        if (!inFlight.cancel(true) && !inFlight.isCompletedExceptionally()) {
            closeQuietly(inFlight.getNow(null).body());
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The exchange is being abandoned either way.
        }
    }
}
