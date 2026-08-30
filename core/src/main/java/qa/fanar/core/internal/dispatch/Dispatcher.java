package qa.fanar.core.internal.dispatch;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.retry.RetryInterceptor;
import qa.fanar.core.internal.transport.BearerTokenInterceptor;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.InterceptorChainImpl;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

/**
 * The request plumbing every domain facade shares: chain assembly, the per-call transport
 * attributes, and the trip through the chain to the transport.
 *
 * <p>Chain order, outermost to innermost (ADR-012, ADR-014): {@link RetryInterceptor} — the SDK's
 * error boundary, re-running everything below it on a retryable failure — then
 * {@link BearerTokenInterceptor}, re-signing every attempt, then the user's interceptors in
 * registration order, then the transport. The chain is assembled once per dispatcher; each
 * {@link #dispatch} runs it through a fresh {@link InterceptorChainImpl} bound to that call's
 * observation.</p>
 *
 * <p>Before the chain runs, the call's {@link FanarObservationAttributes#FANAR_MODEL} (when the
 * call addresses a model), {@link FanarObservationAttributes#HTTP_METHOD} and
 * {@link FanarObservationAttributes#HTTP_URL} are recorded on the observation; the retry boundary
 * adds the per-attempt status, retry count and rate-limit window (ADR-026).</p>
 *
 * <p>Internal (ADR-018). Thread-safe: no per-call state outside the chain instance.</p>
 *
 * @author Oussama Mahjoub
 */
public final class Dispatcher {

    private final List<Interceptor> chain;
    private final HttpTransport transport;

    /**
     * @param transport        the terminal transport
     * @param retryPolicy      the policy behind the built-in retry interceptor
     * @param apiKeySupplier   the bearer token source, consulted on every attempt
     * @param userInterceptors the caller's interceptors, in registration order
     */
    public Dispatcher(
            HttpTransport transport,
            RetryPolicy retryPolicy,
            Supplier<String> apiKeySupplier,
            List<Interceptor> userInterceptors) {
        Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        Objects.requireNonNull(userInterceptors, "userInterceptors");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        List<Interceptor> assembled = new ArrayList<>(userInterceptors.size() + 2);
        assembled.add(new RetryInterceptor(retryPolicy));
        assembled.add(new BearerTokenInterceptor(apiKeySupplier));
        assembled.addAll(userInterceptors);
        this.chain = List.copyOf(assembled);
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Record the call's transport attributes and run the request through the chain.
     *
     * @param request the fully built outbound request
     * @param obs     the call's observation
     * @param model   the model the call addresses, or {@code null} for calls without one
     *                (the model listing, the voice catalogue)
     * @return the response as the chain returns it — a success; error responses became typed
     *         exceptions at the retry boundary
     */
    public HttpResponse<InputStream> dispatch(HttpRequest request, ObservationHandle obs, String model) {
        if (model != null) {
            obs.attribute(FanarObservationAttributes.FANAR_MODEL, model);
        }
        obs.attribute(FanarObservationAttributes.HTTP_METHOD, request.method());
        obs.attribute(FanarObservationAttributes.HTTP_URL, request.uri().toString());
        return new InterceptorChainImpl(chain, transport, obs).proceed(request);
    }
}
