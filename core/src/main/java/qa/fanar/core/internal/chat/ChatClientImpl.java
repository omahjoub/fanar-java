package qa.fanar.core.internal.chat;

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
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.internal.transport.BearerTokenInterceptor;
import qa.fanar.core.internal.transport.ExceptionMapper;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.InterceptorChainImpl;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Production implementation of {@link ChatClient}.
 *
 * <p>Flow for {@link #send}:</p>
 * <ol>
 *   <li>Open an {@link ObservationHandle} named {@code fanar.chat}.</li>
 *   <li>Encode the {@link ChatRequest} to JSON via the configured {@link FanarJsonCodec}.</li>
 *   <li>Build an {@link HttpRequest} with default and observation-supplied headers, plus
 *       {@code User-Agent} when configured.</li>
 *   <li>Run the interceptor chain ({@link BearerTokenInterceptor} first, then any user
 *       interceptors) terminating at the {@link HttpTransport}.</li>
 *   <li>On a 4xx/5xx response, map to a {@link FanarException} via {@link ExceptionMapper};
 *       otherwise decode the response body into {@link ChatResponse}.</li>
 *   <li>Attach {@link FanarObservationAttributes} on the observation; on any exception call
 *       {@link ObservationHandle#error} before rethrowing.</li>
 * </ol>
 *
 * <p>{@link #sendAsync} spawns one virtual thread per call — no executor lifecycle to manage.</p>
 *
 * <p>{@link #stream} is not yet implemented; the SSE parser lands in a follow-up PR.</p>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 */
public final class ChatClientImpl implements ChatClient {

    private static final String ENDPOINT = "/v1/chat/completions";
    private static final String OP_NAME = "fanar.chat";
    private static final String STREAM_NOT_YET =
            "chat().stream() is not yet implemented. The SSE parser lands in a follow-up PR.";

    private final URI endpoint;
    private final FanarJsonCodec jsonCodec;
    private final List<Interceptor> interceptors;
    private final HttpTransport transport;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public ChatClientImpl(
            URI baseUrl,
            FanarJsonCodec jsonCodec,
            Supplier<String> apiKeySupplier,
            List<Interceptor> userInterceptors,
            HttpTransport transport,
            ObservabilityPlugin observability,
            Map<String, String> defaultHeaders,
            String userAgent) {
        this.endpoint = Objects.requireNonNull(baseUrl, "baseUrl").resolve(ENDPOINT);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        Objects.requireNonNull(userInterceptors, "userInterceptors");
        List<Interceptor> chain = new ArrayList<>(userInterceptors.size() + 1);
        chain.add(new BearerTokenInterceptor(apiKeySupplier));
        chain.addAll(userInterceptors);
        this.interceptors = List.copyOf(chain);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
        this.userAgent = userAgent;
    }

    @Override
    public ChatResponse send(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_NAME)) {
            try {
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
                return decodeResponse(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<ChatResponse> sendAsync(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-chat-async-", 0).start(() -> {
            try {
                future.complete(send(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        throw new UnsupportedOperationException(STREAM_NOT_YET);
    }

    private HttpRequest buildHttpRequest(ChatRequest request, ObservationHandle obs) {
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

    private byte[] encodeBody(ChatRequest request) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, request);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode ChatRequest", e);
        }
        return buf.toByteArray();
    }

    private ChatResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, ChatResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode ChatResponse", e);
        }
    }
}
