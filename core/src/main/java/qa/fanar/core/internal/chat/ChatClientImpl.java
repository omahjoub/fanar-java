package qa.fanar.core.internal.chat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.internal.retry.RetryInterceptor;
import qa.fanar.core.internal.sse.SseStreamPublisher;
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
 * <p>{@link #stream} follows the same interceptor pipeline as {@link #send}, but with
 * {@code Accept: text/event-stream} and {@code "stream": true} injected into the request body.
 * The successful {@code HttpResponse} body is handed to an {@link SseStreamPublisher}, which
 * parses frames on a virtual thread and emits {@link StreamEvent}s to the subscriber.</p>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 *
 * @author Oussama Mahjoub
 */
public final class ChatClientImpl implements ChatClient {

    private static final String ENDPOINT = "/v1/chat/completions";
    private static final String OP_NAME = "fanar.chat";

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
            RetryPolicy retryPolicy,
            Map<String, String> defaultHeaders,
            String userAgent) {
        this.endpoint = Objects.requireNonNull(baseUrl, "baseUrl").resolve(ENDPOINT);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        Objects.requireNonNull(userInterceptors, "userInterceptors");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        // Chain order (outermost to innermost):
        //   RetryInterceptor  — wraps everything else, re-runs the chain on retryable failure
        //   BearerTokenInterceptor  — re-signs each retry attempt
        //   <user interceptors>     — in registration order
        //   transport               — terminal
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
    public ChatResponse send(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_NAME)) {
            try {
                HttpResponse<InputStream> response = dispatch(request, obs, false);
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
        try (ObservationHandle obs = observability.start(OP_NAME)) {
            try {
                HttpResponse<InputStream> response = dispatch(request, obs, true);
                return new SseStreamPublisher(response.body(), jsonCodec);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    private HttpResponse<InputStream> dispatch(
            ChatRequest request, ObservationHandle obs, boolean streaming) {
        obs.attribute(FanarObservationAttributes.FANAR_MODEL, request.model().wireValue());
        obs.attribute(FanarObservationAttributes.HTTP_METHOD, "POST");
        obs.attribute(FanarObservationAttributes.HTTP_URL, endpoint.toString());

        HttpRequest httpReq = buildHttpRequest(request, obs, streaming);
        InterceptorChainImpl chain = new InterceptorChainImpl(interceptors, transport, obs);
        HttpResponse<InputStream> response = chain.proceed(httpReq);

        obs.attribute(FanarObservationAttributes.HTTP_STATUS_CODE, response.statusCode());

        if (response.statusCode() >= 400) {
            throw ExceptionMapper.map(response);
        }
        return response;
    }

    private HttpRequest buildHttpRequest(ChatRequest request, ObservationHandle obs, boolean streaming) {
        byte[] body = encodeBody(request, streaming);
        String accept = streaming ? "text/event-stream" : "application/json";

        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb.build();
    }

    private byte[] encodeBody(ChatRequest request, boolean streaming) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, request);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode ChatRequest", e);
        }
        byte[] serialized = buf.toByteArray();
        if (!streaming) {
            return serialized;
        }
        return injectStreamFlag(serialized);
    }

    /**
     * Inject {@code "stream":true} as the first property of the serialized {@link ChatRequest}
     * JSON object. Handles both {@code {}} (no comma needed) and {@code {"k":v,...}} (comma
     * between the injected flag and the existing first key).
     */
    private static byte[] injectStreamFlag(byte[] src) {
        if (src.length < 2 || src[0] != '{') {
            throw new FanarTransportException(
                    "JSON codec produced an unexpected body shape (non-object or empty)");
        }
        byte[] prefix = "{\"stream\":true".getBytes(StandardCharsets.UTF_8);
        boolean emptyObject = src.length == 2; // "{}"
        int rest = src.length - 1; // everything after the opening '{'
        int resultLen = prefix.length + (emptyObject ? 0 : 1) + rest;
        byte[] result = new byte[resultLen];
        int pos = 0;
        System.arraycopy(prefix, 0, result, pos, prefix.length);
        pos += prefix.length;
        if (!emptyObject) {
            result[pos++] = ',';
        }
        System.arraycopy(src, 1, result, pos, rest);
        return result;
    }

    private ChatResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, ChatResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode ChatResponse", e);
        }
    }
}
