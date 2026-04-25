package qa.fanar.core.internal.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.audio.AudioClient;
import qa.fanar.core.audio.CreateVoiceRequest;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TranscriptionRequest;
import qa.fanar.core.audio.VoiceResponse;
import qa.fanar.core.internal.retry.RetryInterceptor;
import qa.fanar.core.internal.transport.BearerTokenInterceptor;
import qa.fanar.core.internal.transport.ExceptionMapper;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.InterceptorChainImpl;
import qa.fanar.core.internal.transport.MultipartBuilder;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Production implementation of {@link AudioClient}. Same interceptor chain as the other domain
 * clients (retry → bearer-token → user → transport); each method picks the right HTTP shape:
 *
 * <ul>
 *   <li>{@link #listVoices} / {@link #deleteVoice} — JSON in/out, simple {@code GET}/{@code DELETE}.</li>
 *   <li>{@link #createVoice} / {@link #transcribe} — {@code multipart/form-data} body via {@link MultipartBuilder}.</li>
 *   <li>{@link #speech} — JSON request, binary audio response (drain raw bytes, no JSON decode).</li>
 *   <li>{@link #transcribe} — multipart audio upload, sealed JSON response (text / srt / json variant).</li>
 * </ul>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 */
public final class AudioClientImpl implements AudioClient {

    private static final String VOICES_PATH = "/v1/audio/voices";
    private static final String SPEECH_PATH = "/v1/audio/speech";
    private static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";
    private static final String OP_LIST = "fanar.audio.voices.list";
    private static final String OP_CREATE = "fanar.audio.voices.create";
    private static final String OP_DELETE = "fanar.audio.voices.delete";
    private static final String OP_SPEECH = "fanar.audio.speech";
    private static final String OP_TRANSCRIBE = "fanar.audio.transcribe";

    private final URI baseUrl;
    private final URI voicesEndpoint;
    private final URI speechEndpoint;
    private final URI transcriptionsEndpoint;
    private final FanarJsonCodec jsonCodec;
    private final List<Interceptor> interceptors;
    private final HttpTransport transport;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public AudioClientImpl(
            URI baseUrl,
            FanarJsonCodec jsonCodec,
            Supplier<String> apiKeySupplier,
            List<Interceptor> userInterceptors,
            HttpTransport transport,
            ObservabilityPlugin observability,
            RetryPolicy retryPolicy,
            Map<String, String> defaultHeaders,
            String userAgent) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.voicesEndpoint = baseUrl.resolve(VOICES_PATH);
        this.speechEndpoint = baseUrl.resolve(SPEECH_PATH);
        this.transcriptionsEndpoint = baseUrl.resolve(TRANSCRIPTIONS_PATH);
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

    // --- listVoices ------------------------------------------------------------------------

    @Override
    public VoiceResponse listVoices() {
        try (ObservationHandle obs = observability.start(OP_LIST)) {
            try {
                HttpResponse<InputStream> response = dispatch(
                        buildGet(voicesEndpoint, obs), voicesEndpoint, "GET", obs);
                return decodeJson(response, VoiceResponse.class, "VoiceResponse");
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<VoiceResponse> listVoicesAsync() {
        return supplyAsync("fanar-audio-voices-list-async-", this::listVoices);
    }

    // --- createVoice -----------------------------------------------------------------------

    @Override
    public void createVoice(CreateVoiceRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_CREATE)) {
            try {
                MultipartBuilder mb = new MultipartBuilder();
                mb.addField("name", request.name());
                mb.addFile("audio", request.name() + ".wav", "audio/wav", request.audio());
                mb.addField("transcript", request.transcript());
                byte[] body = mb.build();

                HttpResponse<InputStream> response = dispatch(
                        buildPost(voicesEndpoint, obs, mb.contentType(), body),
                        voicesEndpoint, "POST", obs);
                drain(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Void> createVoiceAsync(CreateVoiceRequest request) {
        Objects.requireNonNull(request, "request");
        return runAsync("fanar-audio-voices-create-async-", () -> createVoice(request));
    }

    // --- deleteVoice -----------------------------------------------------------------------

    @Override
    public void deleteVoice(String name) {
        Objects.requireNonNull(name, "name");
        try (ObservationHandle obs = observability.start(OP_DELETE)) {
            try {
                URI endpoint = baseUrl.resolve(
                        VOICES_PATH + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
                HttpResponse<InputStream> response = dispatch(
                        buildDelete(endpoint, obs), endpoint, "DELETE", obs);
                drain(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Void> deleteVoiceAsync(String name) {
        Objects.requireNonNull(name, "name");
        return runAsync("fanar-audio-voices-delete-async-", () -> deleteVoice(name));
    }

    // --- speech (TTS) ----------------------------------------------------------------------

    @Override
    public byte[] speech(TextToSpeechRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_SPEECH)) {
            try {
                obs.attribute(FanarObservationAttributes.FANAR_MODEL, request.model().wireValue());
                byte[] body = encodeJsonBody(request, "TextToSpeechRequest");
                HttpRequest httpReq = applyCommonHeaders(HttpRequest.newBuilder(speechEndpoint), obs)
                        .header("Content-Type", "application/json")
                        // Server picks audio/mpeg or audio/wav based on response_format in body.
                        .header("Accept", "audio/*")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<InputStream> response = dispatch(httpReq, speechEndpoint, "POST", obs);
                return readBinary(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<byte[]> speechAsync(TextToSpeechRequest request) {
        Objects.requireNonNull(request, "request");
        return supplyAsync("fanar-audio-speech-async-", () -> speech(request));
    }

    // --- transcribe (STT) ------------------------------------------------------------------

    @Override
    public SpeechToTextResponse transcribe(TranscriptionRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_TRANSCRIBE)) {
            try {
                obs.attribute(FanarObservationAttributes.FANAR_MODEL, request.model().wireValue());

                MultipartBuilder mb = new MultipartBuilder();
                mb.addFile("file", request.filename(), request.contentType(), request.file());
                mb.addField("model", request.model().wireValue());
                if (request.format() != null) {
                    mb.addField("format", request.format().wireValue());
                }
                byte[] body = mb.build();

                HttpRequest httpReq = applyCommonHeaders(
                        HttpRequest.newBuilder(transcriptionsEndpoint), obs)
                        .header("Content-Type", mb.contentType())
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<InputStream> response = dispatch(
                        httpReq, transcriptionsEndpoint, "POST", obs);
                return decodeJson(response, SpeechToTextResponse.class, "SpeechToTextResponse");
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<SpeechToTextResponse> transcribeAsync(TranscriptionRequest request) {
        Objects.requireNonNull(request, "request");
        return supplyAsync("fanar-audio-transcribe-async-", () -> transcribe(request));
    }

    // --- shared dispatch -------------------------------------------------------------------

    private HttpResponse<InputStream> dispatch(
            HttpRequest httpReq, URI endpoint, String method, ObservationHandle obs) {
        obs.attribute(FanarObservationAttributes.HTTP_METHOD, method);
        obs.attribute(FanarObservationAttributes.HTTP_URL, endpoint.toString());

        InterceptorChainImpl chain = new InterceptorChainImpl(interceptors, transport, obs);
        HttpResponse<InputStream> response = chain.proceed(httpReq);

        obs.attribute(FanarObservationAttributes.HTTP_STATUS_CODE, response.statusCode());

        if (response.statusCode() >= 400) {
            throw ExceptionMapper.map(response);
        }
        return response;
    }

    private HttpRequest buildGet(URI endpoint, ObservationHandle obs) {
        return applyCommonHeaders(HttpRequest.newBuilder(endpoint), obs)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private HttpRequest buildDelete(URI endpoint, ObservationHandle obs) {
        return applyCommonHeaders(HttpRequest.newBuilder(endpoint), obs)
                .header("Accept", "application/json")
                .DELETE()
                .build();
    }

    private HttpRequest buildPost(URI endpoint, ObservationHandle obs, String contentType, byte[] body) {
        return applyCommonHeaders(HttpRequest.newBuilder(endpoint), obs)
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private HttpRequest.Builder applyCommonHeaders(HttpRequest.Builder rb, ObservationHandle obs) {
        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb;
    }

    private <T> T decodeJson(HttpResponse<InputStream> response, Class<T> type, String label) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, type);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode " + label, e);
        }
    }

    private byte[] encodeJsonBody(Object value, String label) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, value);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode " + label, e);
        }
        return buf.toByteArray();
    }

    private static byte[] readBinary(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new FanarTransportException("Failed to read binary response body", e);
        }
    }

    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new FanarTransportException("Failed to read response body", e);
        }
    }

    private <T> CompletableFuture<T> supplyAsync(String threadName, Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().name(threadName, 0).start(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private CompletableFuture<Void> runAsync(String threadName, Runnable action) {
        return supplyAsync(threadName, () -> {
            action.run();
            return null;
        });
    }
}
