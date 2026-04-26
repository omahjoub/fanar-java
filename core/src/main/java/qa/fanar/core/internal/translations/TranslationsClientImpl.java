package qa.fanar.core.internal.translations;

import java.io.ByteArrayOutputStream;
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
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;
import qa.fanar.core.translations.TranslationsClient;

/**
 * Production implementation of {@link TranslationsClient}. Same plumbing as the other domain
 * clients: retry → bearer-token → user interceptors → transport. POSTs the
 * {@link TranslationRequest} to {@code /v1/translations}, decodes the {@link TranslationResponse}
 * on success, maps 4xx/5xx through {@link ExceptionMapper}.
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 *
 * @author Oussama Mahjoub
 */
public final class TranslationsClientImpl implements TranslationsClient {

    private static final String ENDPOINT = "/v1/translations";
    private static final String OP_NAME = "fanar.translations.translate";

    private final URI endpoint;
    private final FanarJsonCodec jsonCodec;
    private final List<Interceptor> interceptors;
    private final HttpTransport transport;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public TranslationsClientImpl(
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
    public TranslationResponse translate(TranslationRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_NAME)) {
            try {
                HttpResponse<InputStream> response = dispatch(request, obs);
                return decodeResponse(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<TranslationResponse> translateAsync(TranslationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<TranslationResponse> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-translations-async-", 0).start(() -> {
            try {
                future.complete(translate(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private HttpResponse<InputStream> dispatch(TranslationRequest request, ObservationHandle obs) {
        obs.attribute(FanarObservationAttributes.FANAR_MODEL, request.model().wireValue());
        obs.attribute(FanarObservationAttributes.HTTP_METHOD, "POST");
        obs.attribute(FanarObservationAttributes.HTTP_URL, endpoint.toString());

        HttpRequest httpReq = buildHttpRequest(request, obs);
        InterceptorChainImpl chain = new InterceptorChainImpl(interceptors, transport, obs);
        HttpResponse<InputStream> response = chain.proceed(httpReq);

        obs.attribute(FanarObservationAttributes.HTTP_STATUS_CODE, response.statusCode());

        if (response.statusCode() >= 400) {
            throw ExceptionMapper.map(response);
        }
        return response;
    }

    private HttpRequest buildHttpRequest(TranslationRequest request, ObservationHandle obs) {
        byte[] body = encodeBody(request);
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb.build();
    }

    private byte[] encodeBody(TranslationRequest request) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, request);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode TranslationRequest", e);
        }
        return buf.toByteArray();
    }

    private TranslationResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, TranslationResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode TranslationResponse", e);
        }
    }
}
