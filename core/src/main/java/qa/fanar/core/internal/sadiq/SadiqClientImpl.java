package qa.fanar.core.internal.sadiq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.dispatch.Dispatcher;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.sadiq.SadiqClient;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Production implementation of {@link SadiqClient}.
 *
 * <p>Same plumbing as the other domain clients: retry &rarr; bearer-token &rarr; user interceptors
 * &rarr; transport. POSTs the {@link SadiqValidationRequest} to {@code /v1/sadiq/validate} and
 * decodes the {@link SadiqValidationResponse}; 4xx/5xx become typed exceptions inside the chain
 * (retry interceptor) before reaching this class.</p>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 *
 * <p>Request plumbing — chain assembly (retry &rarr; bearer token &rarr; user interceptors &rarr;
 * transport), the {@code http.method} / {@code http.url} / {@code fanar.model} attributes and the
 * trip to the transport — lives in {@link Dispatcher}; this class owns the endpoint, the wire
 * format and the decoding.</p>
 *
 * @author Oussama Mahjoub
 */
public final class SadiqClientImpl implements SadiqClient {

    private static final String ENDPOINT = "/v1/sadiq/validate";
    private static final String OP_NAME = "fanar.sadiq.validate";

    private final URI endpoint;
    private final FanarJsonCodec jsonCodec;
    private final Dispatcher dispatcher;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public SadiqClientImpl(
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
        // Retry → bearer token → user interceptors → transport: assembled by the Dispatcher.
        this.dispatcher = new Dispatcher(transport, retryPolicy, apiKeySupplier, userInterceptors);
        this.observability = Objects.requireNonNull(observability, "observability");
        this.defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
        this.userAgent = userAgent;
    }

    @Override
    public SadiqValidationResponse validate(SadiqValidationRequest request) {
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
    public CompletableFuture<SadiqValidationResponse> validateAsync(SadiqValidationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<SadiqValidationResponse> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-sadiq-async-", 0).start(() -> {
            try {
                future.complete(validate(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private HttpResponse<InputStream> dispatch(SadiqValidationRequest request, ObservationHandle obs) {
        return dispatcher.dispatch(buildHttpRequest(request, obs), obs, request.model().wireValue());
    }

    private HttpRequest buildHttpRequest(SadiqValidationRequest request, ObservationHandle obs) {
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

    private byte[] encodeBody(SadiqValidationRequest request) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, request);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode SadiqValidationRequest", e);
        }
        return buf.toByteArray();
    }

    private SadiqValidationResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, SadiqValidationResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode SadiqValidationResponse", e);
        }
    }
}
