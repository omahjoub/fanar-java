package qa.fanar.core.internal.transport;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;

import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Chain-of-responsibility runner for {@link Interceptor}s.
 *
 * <p>Constructed per-request. Walks through the interceptor list in registration order
 * (first-added is outermost, per ADR-012) and hits the {@link HttpTransport} at the tail.
 * Supports zero or more interceptors; with an empty list, {@link #proceed(HttpRequest)} calls
 * straight through to the transport.</p>
 *
 * <p>Internal (ADR-018). Implements {@link Interceptor.Chain}, which is the public SPI seen by
 * user-authored interceptors.</p>
 */
public final class InterceptorChainImpl implements Interceptor.Chain {

    private final List<Interceptor> interceptors;
    private final HttpTransport transport;
    private final ObservationHandle observation;
    private final int index;

    public InterceptorChainImpl(
            List<Interceptor> interceptors,
            HttpTransport transport,
            ObservationHandle observation) {
        this(interceptors, transport, observation, 0);
    }

    private InterceptorChainImpl(
            List<Interceptor> interceptors,
            HttpTransport transport,
            ObservationHandle observation,
            int index) {
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.index = index;
    }

    @Override
    public HttpResponse<InputStream> proceed(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (index >= interceptors.size()) {
            return transport.send(request);
        }
        Interceptor next = interceptors.get(index);
        InterceptorChainImpl advanced =
                new InterceptorChainImpl(interceptors, transport, observation, index + 1);
        return next.intercept(request, advanced);
    }

    @Override
    public ObservationHandle observation() {
        return observation;
    }
}
