package qa.fanar.core.spi;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Cross-cutting concern applied to every HTTP exchange between the SDK and the Fanar API.
 *
 * <p>Typical implementations: authentication header injection, retry loops, rate limiting, request
 * / response logging, response caching, custom header injection. Observability (metrics, tracing)
 * is a separate SPI — see {@link ObservabilityPlugin}.</p>
 *
 * <p>Interceptors are registered in order on {@code FanarClient.Builder.addInterceptor(...)}.
 * The first-registered interceptor is the <em>outermost</em> — it wraps every later interceptor
 * and the transport. A common consequence: when an auth interceptor is registered first and a
 * retry interceptor second, retries re-run the auth-wrapped chain, so each attempt has a fresh
 * token.</p>
 *
 * <p>Interceptors run <strong>synchronously on the caller's thread</strong>. On Java 21 virtual
 * threads this does not block carriers; on platform threads, interceptors should not introduce
 * long-blocking I/O beyond what the underlying HTTP call already does. Interceptors must not
 * spawn their own threads or stash {@code ThreadLocal} state in ways that break virtual-thread
 * assumptions.</p>
 *
 * <p>Interceptors apply to the <em>initial HTTP exchange</em>. For streaming calls, they see the
 * connection handshake; they do not intercept individual SSE chunks once the stream is flowing.
 * Per-event logic composes at the {@code Flow.Publisher} layer.</p>
 *
 * @author Oussama Mahjoub
 */
@FunctionalInterface
public interface Interceptor {

    /**
     * Intercept an outbound HTTP request.
     *
     * <p>An implementation typically inspects or modifies {@code request}, invokes
     * {@link Chain#proceed(HttpRequest)} to run the remainder of the chain (possibly multiple
     * times, for retries), and returns a response.</p>
     *
     * <p>The method does not declare checked exceptions. Transport-level I/O and interrupt
     * failures are wrapped into {@code qa.fanar.core.FanarTransportException} by the SDK's
     * transport layer before they reach interceptors.</p>
     *
     * @param request the outbound HTTP request, possibly modified by earlier interceptors
     * @param chain   the remainder of the interceptor chain, terminating in the HTTP transport
     * @return the HTTP response seen by the caller of this interceptor
     */
    HttpResponse<InputStream> intercept(HttpRequest request, Chain chain);

    /**
     * The remainder of the interceptor chain, from the perspective of the current interceptor.
     *
     * <p>Calling {@link #proceed(HttpRequest)} advances the chain by one step. Calling it zero
     * times short-circuits (the interceptor returns a response itself, e.g., from a cache).
     * Calling it multiple times implements retry semantics.</p>
     */
    interface Chain {

        /**
         * Pass {@code request} to the next interceptor or, at the tail of the chain, to the HTTP
         * transport. The returned response may have been modified by nested interceptors on its
         * way back.
         *
         * @param request the (possibly-modified) request to pass on
         * @return the response produced by the remainder of the chain
         */
        HttpResponse<InputStream> proceed(HttpRequest request);

        /**
         * The observation handle for the current request. Always non-{@code null} — if no
         * {@link ObservabilityPlugin} is configured, this returns a no-op handle whose methods
         * silently ignore every call.
         *
         * <p>Interceptors that want to record cross-cutting events (retry attempts, cache hits,
         * auth-token refreshes) attach them to this handle via {@link ObservationHandle#event}
         * or {@link ObservationHandle#attribute}.</p>
         *
         * @return the current request's observation handle
         */
        ObservationHandle observation();
    }
}
