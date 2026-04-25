package qa.fanar.core.internal.models;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.retry.RetryInterceptor;
import qa.fanar.core.internal.transport.BearerTokenInterceptor;
import qa.fanar.core.internal.transport.ExceptionMapper;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.InterceptorChainImpl;
import qa.fanar.core.models.ModelsClient;
import qa.fanar.core.models.ModelsResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Production implementation of {@link ModelsClient}.
 *
 * <p>Flow for {@link #list}:</p>
 * <ol>
 *   <li>Open an {@link ObservationHandle} named {@code fanar.models.list}.</li>
 *   <li>Build a {@code GET /v1/models} request with {@code Accept: application/json}, default
 *       and observation-supplied headers, plus {@code User-Agent} when configured.</li>
 *   <li>Run the interceptor chain (retry first, bearer-token second, then user interceptors)
 *       terminating at the {@link HttpTransport}.</li>
 *   <li>On 4xx/5xx, map to a {@code FanarException} via {@link ExceptionMapper}; otherwise
 *       decode into a {@link ModelsResponse}.</li>
 * </ol>
 *
 * <p>{@link #listAsync} spawns one virtual thread per call.</p>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 */
public final class ModelsClientImpl implements ModelsClient {

    private static final String ENDPOINT = "/v1/models";
    private static final String OP_NAME = "fanar.models.list";

    private final URI endpoint;
    private final FanarJsonCodec jsonCodec;
    private final List<Interceptor> interceptors;
    private final HttpTransport transport;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public ModelsClientImpl(
            URI baseUrl,
            FanarJsonCodec jsonCodec,
            Supplier<String> apiKeySupplier,
            List<Interceptor> userInterceptors,
            HttpTransport transport,
            ObservabilityPlugin observability,
            RetryPolicy retryPolicy,
            Map<String, String> defaultHeaders,
            String userAgent) {
        this.endpoint = Objects.requireNonNull(baseUrl, "baseUrl").resolve(ENDPOINT);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        Objects.requireNonNull(userInterceptors, "userInterceptors");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        // Same chain order as ChatClientImpl: retry → bearer-token → user → transport.
        List<Interceptor> chain = new ArrayList<>(userInterceptors.size() + 2);
        chain.add(new RetryInterceptor(retryPolicy));
        chain.add(new BearerTokenInterceptor(apiKeySupplier));
        chain.addAll(userInterceptors);
        this.interceptors = List.copyOf(chain);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
        this.userAgent = userAgent;
    }

    @Override
    public ModelsResponse list() {
        try (ObservationHandle obs = observability.start(OP_NAME)) {
            try {
                HttpResponse<InputStream> response = dispatch(obs);
                return decodeResponse(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<ModelsResponse> listAsync() {
        CompletableFuture<ModelsResponse> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-models-async-", 0).start(() -> {
            try {
                future.complete(list());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private HttpResponse<InputStream> dispatch(ObservationHandle obs) {
        obs.attribute(FanarObservationAttributes.HTTP_METHOD, "GET");
        obs.attribute(FanarObservationAttributes.HTTP_URL, endpoint.toString());

        HttpRequest httpReq = buildHttpRequest(obs);
        InterceptorChainImpl chain = new InterceptorChainImpl(interceptors, transport, obs);
        HttpResponse<InputStream> response = chain.proceed(httpReq);

        obs.attribute(FanarObservationAttributes.HTTP_STATUS_CODE, response.statusCode());

        if (response.statusCode() >= 400) {
            throw ExceptionMapper.map(response);
        }
        return response;
    }

    private HttpRequest buildHttpRequest(ObservationHandle obs) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .header("Accept", "application/json")
                .GET();
        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb.build();
    }

    private ModelsResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, ModelsResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode ModelsResponse", e);
        }
    }
}
