package qa.fanar.e2e.graalvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarException;
import qa.fanar.core.audio.CreateVoiceRequest;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.SttFormat;
import qa.fanar.core.audio.SttModel;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TranscriptionRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.audio.VoiceResponse;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.images.ImageGenerationRequest;
import qa.fanar.core.images.ImageGenerationResponse;
import qa.fanar.core.images.ImageModel;
import qa.fanar.core.models.ModelsResponse;
import qa.fanar.core.moderations.ModerationModel;
import qa.fanar.core.moderations.SafetyFilterRequest;
import qa.fanar.core.moderations.SafetyFilterResponse;
import qa.fanar.core.poems.PoemGenerationRequest;
import qa.fanar.core.poems.PoemGenerationResponse;
import qa.fanar.core.poems.PoemModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.core.tokens.TokenizationRequest;
import qa.fanar.core.tokens.TokenizationResponse;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;
import qa.fanar.interceptor.logging.WireLoggingInterceptor;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
import qa.fanar.obs.micrometer.MicrometerObservabilityPlugin;
import qa.fanar.obs.otel.OpenTelemetryObservabilityPlugin;
import qa.fanar.obs.slf4j.Slf4jObservabilityPlugin;

/**
 * Smoke entry point compiled to a GraalVM native binary by the {@code native} Maven profile.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><strong>{@code --self-test}</strong> (offline) — decode a canned response per Fanar
 *       domain through the Jackson 3 codec and instantiate every published observability
 *       plugin and the wire-logging interceptor. Exercises the full reflective surface the
 *       SDK exposes without touching the network. PR-time CI runs this to verify reachability
 *       metadata covers everything.</li>
 *   <li><strong>default (live)</strong> — read {@code FANAR_API_KEY} from the environment,
 *       send a minimal chat completion. Used during reachability-metadata bootstrap (run
 *       under the GraalVM tracing agent in JIT mode) and on scheduled CI when a key is
 *       available.</li>
 * </ul>
 *
 * <p>Exit codes: {@code 0} on success, {@code 2} if the live mode is requested but
 * {@code FANAR_API_KEY} is missing, non-zero on assertion / decode failures.</p>
 *
 * @author Oussama Mahjoub
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        live();
    }

    /**
     * Offline reachability smoke. Decodes a canned wire payload for every domain and
     * exercises every published observability plugin + the wire-logging interceptor. If a
     * record's component metadata, a deserializer's reflective constructor, or a plugin's
     * static initializer was dropped by the analyzer, this method fails before reaching
     * users.
     */
    private static void selfTest() throws IOException {
        Jackson3FanarJsonCodec codec = new Jackson3FanarJsonCodec();

        // Decode probes (incoming responses).
        decodeChat(codec);
        decodeModels(codec);
        decodeTokens(codec);
        decodeModerations(codec);
        decodeTranslations(codec);
        decodePoems(codec);
        decodeImages(codec);
        decodeAudioVoices(codec);
        decodeAudioStt(codec);

        // Encode probes (outgoing requests). The encode path uses Jackson 3's serializer
        // factory which independently introspects records — covering both directions.
        encodeChatRequest(codec);
        encodeTokenizationRequest(codec);
        encodeSafetyFilterRequest(codec);
        encodeTranslationRequest(codec);
        encodePoemGenerationRequest(codec);
        encodeImageGenerationRequest(codec);
        encodeTextToSpeechRequest(codec);
        encodeTranscriptionRequest(codec);
        encodeCreateVoiceRequest(codec);

        exerciseObservabilityPlugins();
        exerciseInterceptors();

        System.out.println("self-test OK: 9 decode probes + 9 encode probes, "
                + "4 obs plugins exercised, wire interceptor instantiated");
    }

    // --- domain decode probes ----------------------------------------------------------

    private static void decodeChat(FanarJsonCodec codec) throws IOException {
        String wire = "{\"id\":\"c_1\",\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"content\":\"pong\",\"role\":\"assistant\","
                + "\"references\":null,\"tool_calls\":[]}}],"
                + "\"created\":1700000000,\"model\":\"Fanar-S-1-7B\","
                + "\"usage\":{\"completion_tokens\":1,\"prompt_tokens\":1,\"total_tokens\":2}}";
        ChatResponse r = codec.decode(bytes(wire), ChatResponse.class);
        require("c_1".equals(r.id()), "chat id");
        require(r.choices().size() == 1, "chat choices");
    }

    private static void decodeModels(FanarJsonCodec codec) throws IOException {
        String wire = "{\"id\":\"req_1\",\"models\":["
                + "{\"id\":\"Fanar\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"fanar\"}"
                + "]}";
        ModelsResponse r = codec.decode(bytes(wire), ModelsResponse.class);
        require("req_1".equals(r.id()), "models id");
        require(r.models().size() == 1, "models size");
    }

    private static void decodeTokens(FanarJsonCodec codec) throws IOException {
        TokenizationResponse r = codec.decode(
                bytes("{\"id\":\"req_1\",\"tokens\":7,\"max_request_tokens\":4096}"),
                TokenizationResponse.class);
        require(r.tokens() == 7, "tokens count");
        require(r.maxRequestTokens() == 4096, "tokens max");
    }

    private static void decodeModerations(FanarJsonCodec codec) throws IOException {
        SafetyFilterResponse r = codec.decode(
                bytes("{\"safety\":0.95,\"cultural_awareness\":0.88,\"id\":\"req_1\"}"),
                SafetyFilterResponse.class);
        require(r.safety() == 0.95, "safety score");
        require(r.culturalAwareness() == 0.88, "cultural score");
    }

    private static void decodeTranslations(FanarJsonCodec codec) throws IOException {
        TranslationResponse r = codec.decode(
                bytes("{\"id\":\"req_1\",\"text\":\"\\u0645\\u0631\\u062d\\u0628\\u0627\"}"),
                TranslationResponse.class);
        require("req_1".equals(r.id()), "translation id");
        require(r.text() != null && !r.text().isEmpty(), "translation text");
    }

    private static void decodePoems(FanarJsonCodec codec) throws IOException {
        PoemGenerationResponse r = codec.decode(
                bytes("{\"id\":\"req_1\",\"poem\":\"the sea\"}"),
                PoemGenerationResponse.class);
        require("req_1".equals(r.id()), "poem id");
        require("the sea".equals(r.poem()), "poem text");
    }

    private static void decodeImages(FanarJsonCodec codec) throws IOException {
        String wire = "{\"id\":\"req_1\",\"created\":1700000000,"
                + "\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}";
        ImageGenerationResponse r = codec.decode(bytes(wire), ImageGenerationResponse.class);
        require(r.data().size() == 1, "image data size");
        require("aGVsbG8=".equals(r.data().getFirst().b64Json()), "image b64");
    }

    private static void decodeAudioVoices(FanarJsonCodec codec) throws IOException {
        VoiceResponse r = codec.decode(
                bytes("{\"voices\":[\"alice\",\"bob\"]}"),
                VoiceResponse.class);
        require(r.voices().size() == 2, "voices size");
    }

    private static void decodeAudioStt(FanarJsonCodec codec) throws IOException {
        // Sealed STT response — exercises the custom variant-discriminating deserializer.
        SpeechToTextResponse text = codec.decode(
                bytes("{\"id\":\"req_1\",\"text\":\"hello\"}"),
                SpeechToTextResponse.class);
        require(text instanceof SpeechToTextResponse.Text, "stt variant detected");
        SpeechToTextResponse json = codec.decode(
                bytes("{\"id\":\"req_2\",\"json\":{\"segments\":["
                        + "{\"speaker\":\"s\",\"start_time\":0.0,\"end_time\":1.0,"
                        + "\"duration\":1.0,\"text\":\"hi\"}]}}"),
                SpeechToTextResponse.class);
        require(json instanceof SpeechToTextResponse.Json
                && ((SpeechToTextResponse.Json) json).segments().size() == 1,
                "stt json variant");
    }

    // --- domain encode probes ----------------------------------------------------------

    private static void encodeChatRequest(FanarJsonCodec codec) throws IOException {
        ChatRequest req = ChatRequest.builder()
                .model(ChatModel.FANAR_S_1_7B)
                .addMessage(UserMessage.of("hi"))
                .maxTokens(8)
                .temperature(0.0)
                .build();
        byte[] body = encode(codec, req);
        require(body.length > 0, "chat encode");
    }

    private static void encodeTokenizationRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, TokenizationRequest.of("hi", ChatModel.FANAR_S_1_7B));
        require(body.length > 0, "tokens encode");
    }

    private static void encodeSafetyFilterRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, SafetyFilterRequest.of(
                ModerationModel.FANAR_GUARD_2, "ping", "pong"));
        require(body.length > 0, "moderations encode");
    }

    private static void encodeTranslationRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, TranslationRequest.of(
                TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.EN_AR));
        require(body.length > 0, "translations encode");
    }

    private static void encodePoemGenerationRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, PoemGenerationRequest.of(
                PoemModel.FANAR_DIWAN, "the sea"));
        require(body.length > 0, "poems encode");
    }

    private static void encodeImageGenerationRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, ImageGenerationRequest.of(
                ImageModel.FANAR_ORYX_IG_2, "a sunset"));
        require(body.length > 0, "images encode");
    }

    private static void encodeTextToSpeechRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, TextToSpeechRequest.of(
                TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY));
        require(body.length > 0, "tts encode");
    }

    private static void encodeTranscriptionRequest(FanarJsonCodec codec) throws IOException {
        // Note: TranscriptionRequest is sent as multipart/form-data on the wire, not JSON,
        // but the SDK still passes it through Jackson when capturing it for observability /
        // logging. Encoding it through the codec exercises the same record-introspection path.
        byte[] body = encode(codec, new TranscriptionRequest(
                new byte[]{1, 2, 3}, "audio.wav", "audio/wav",
                SttModel.FANAR_AURA_STT_1, SttFormat.TEXT));
        require(body.length > 0, "transcription encode");
    }

    private static void encodeCreateVoiceRequest(FanarJsonCodec codec) throws IOException {
        byte[] body = encode(codec, new CreateVoiceRequest(
                "alice", new byte[]{1, 2, 3}, "hello"));
        require(body.length > 0, "voice encode");
    }

    // --- observability + interceptor probes --------------------------------------------

    /**
     * Instantiate every published observability plugin, dispatch one full event lifecycle
     * (start → attribute → event → error → child → propagationHeaders → close), and the
     * composite that fans them out together.
     */
    private static void exerciseObservabilityPlugins() {
        ObservabilityPlugin slf4j = new Slf4jObservabilityPlugin();
        ObservabilityPlugin otel = new OpenTelemetryObservabilityPlugin(OpenTelemetry.noop());
        ObservabilityPlugin micrometer =
                new MicrometerObservabilityPlugin(ObservationRegistry.create());
        ObservabilityPlugin composite = ObservabilityPlugin.compose(slf4j, otel, micrometer);

        for (ObservabilityPlugin plugin : new ObservabilityPlugin[]{slf4j, otel, micrometer, composite}) {
            try (ObservationHandle h = plugin.start("fanar.smoke.op")) {
                h.attribute("k", "v")
                        .event("smoke_event")
                        .error(new IllegalStateException("smoke-error"));
                try (ObservationHandle child = h.child("decode")) {
                    child.attribute("nested", true);
                }
                Map<String, String> headers = h.propagationHeaders();
                require(headers != null, "propagation headers non-null");
            }
        }
    }

    /** Instantiate the wire-logging interceptor at every level so every code path is reachable. */
    private static void exerciseInterceptors() {
        for (WireLoggingInterceptor.Level level : WireLoggingInterceptor.Level.values()) {
            WireLoggingInterceptor interceptor = WireLoggingInterceptor.builder()
                    .level(level)
                    .addRedactedHeader("X-Smoke-Header")
                    .bodyByteCap(1024)
                    .build();
            require(interceptor != null, "interceptor at level " + level);
        }
    }

    // --- live ---------------------------------------------------------------------------

    /**
     * Live smoke: walk every domain against the real Fanar API with the full observability
     * stack wired (SLF4J + OTel + Micrometer composed) and the {@code WireLoggingInterceptor}
     * attached. Used during reachability-metadata bootstrap and on scheduled CI when
     * {@code FANAR_API_KEY} is set.
     *
     * <p>Each probe is wrapped in {@link #runProbe} so a typed {@link FanarException} (auth
     * gating, rate limiting, timeouts, not-found) is logged and the walk continues — failure
     * to authorize on one domain shouldn't stop us from exercising the next under native.
     * Unexpected runtime errors (e.g., GraalVM {@code UnsupportedFeatureError} from a
     * reachability gap) propagate so the binary fails loudly.</p>
     *
     * <p>Includes one async probe to exercise the virtual-thread async wrapper under
     * native-image — sync and async share the same encode/decode plumbing so one async
     * sample is enough to validate the wrapper itself.</p>
     */
    private static void live() {
        String key = System.getenv("FANAR_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("FANAR_API_KEY not set; pass --self-test for the offline smoke");
            System.exit(2);
        }
        try (FanarClient client = FanarClient.builder()
                .apiKey(key)
                .jsonCodec(new Jackson3FanarJsonCodec())
                .addInterceptor(WireLoggingInterceptor.builder()
                        .level(WireLoggingInterceptor.Level.BODY)
                        .build())
                .observability(ObservabilityPlugin.compose(
                        new Slf4jObservabilityPlugin(),
                        new OpenTelemetryObservabilityPlugin(OpenTelemetrySdk.builder()
                            .setTracerProvider(SdkTracerProvider.builder().build())
                            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                            .build()),
                        new MicrometerObservabilityPlugin(ObservationRegistry.create())))
                .build()) {
            // --- sync probes for every domain ---
            runProbe("chat",          () -> liveChat(client));
            runProbe("models",        () -> liveModels(client));
            runProbe("tokens",        () -> liveTokens(client));
            runProbe("moderations",   () -> liveModerations(client));
            runProbe("translations",  () -> liveTranslations(client));
            runProbe("poems",         () -> livePoems(client));
            runProbe("images",        () -> liveImages(client));
            runProbe("audio.voices",  () -> liveAudioVoices(client));
            byte[] wav = runWithResult("audio.speech", () -> liveAudioSpeech(client));
            if (wav != null) {
                runProbe("audio.transcribe", () -> liveAudioTranscribe(client, wav));
            }

            // --- async probe — exercises the virtual-thread async wrapper under native ---
            runProbe("chat.async",    () -> liveChatAsync(client));
        }
        System.out.println("live OK: walk complete");
    }

    private static void liveChat(FanarClient client) {
        ChatResponse r = client.chat().send(ChatRequest.builder()
                .model(ChatModel.FANAR_S_1_7B)
                .addMessage(UserMessage.of("hi"))
                .maxTokens(8)
                .temperature(0.0)
                .build());
        System.out.println("  chat: id=" + r.id() + " choices=" + r.choices().size()
                + " model=" + r.model());
    }

    private static void liveChatAsync(FanarClient client) throws Exception {
        ChatResponse r = client.chat().sendAsync(ChatRequest.builder()
                .model(ChatModel.FANAR_S_1_7B)
                .addMessage(UserMessage.of("hi"))
                .maxTokens(8)
                .temperature(0.0)
                .build()).get(60, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("  chat.async: id=" + r.id() + " choices=" + r.choices().size());
    }

    private static void liveModels(FanarClient client) {
        ModelsResponse r = client.models().list();
        System.out.println("  models: id=" + r.id() + " count=" + r.models().size());
    }

    private static void liveTokens(FanarClient client) {
        TokenizationResponse r = client.tokens().count(
                TokenizationRequest.of("Hello, how are you?", ChatModel.FANAR_S_1_7B));
        System.out.println("  tokens: id=" + r.id() + " count=" + r.tokens()
                + " maxRequestTokens=" + r.maxRequestTokens());
    }

    private static void liveModerations(FanarClient client) {
        SafetyFilterResponse r = client.moderations().score(
                SafetyFilterRequest.of(ModerationModel.FANAR_GUARD_2,
                        "What is the weather?", "The weather is sunny today."));
        System.out.println("  moderations: safety=" + r.safety()
                + " cultural=" + r.culturalAwareness());
    }

    private static void liveTranslations(FanarClient client) {
        TranslationResponse r = client.translations().translate(
                TranslationRequest.of(TranslationModel.FANAR_SHAHEEN_MT_1,
                        "Hello, how are you?", LanguagePair.EN_AR));
        System.out.println("  translations: id=" + r.id() + " textLen=" + r.text().length());
    }

    private static void livePoems(FanarClient client) {
        PoemGenerationResponse r = client.poems().generate(
                PoemGenerationRequest.of(PoemModel.FANAR_DIWAN, "Write a poem about the sea"));
        System.out.println("  poems: id=" + r.id() + " poemLen=" + r.poem().length());
    }

    private static void liveImages(FanarClient client) {
        // Slowest endpoint here (~5–15 s typical), largest body — exercises response buffering.
        ImageGenerationResponse r = client.images().generate(
                ImageGenerationRequest.of(ImageModel.FANAR_ORYX_IG_2,
                        "A futuristic cityscape at sunset"));
        int b64Len = r.data().isEmpty() ? 0 : r.data().getFirst().b64Json().length();
        System.out.println("  images: id=" + r.id() + " count=" + r.data().size()
                + " b64Len=" + b64Len);
    }

    private static void liveAudioVoices(FanarClient client) {
        VoiceResponse r = client.audio().listVoices();
        System.out.println("  audio.voices: count=" + r.voices().size());
    }

    private static byte[] liveAudioSpeech(FanarClient client) {
        // Probes the binary-response path — `BodyHandlers.ofInputStream` + `byte[]` return.
        byte[] wav = client.audio().speech(new TextToSpeechRequest(
                TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY,
                qa.fanar.core.audio.TtsResponseFormat.WAV, null));
        System.out.println("  audio.speech: bytes=" + wav.length);
        return wav;
    }

    private static void liveAudioTranscribe(FanarClient client, byte[] wav) {
        // Probes the multipart upload path — `MultipartBuilder` + multipart Content-Type.
        SpeechToTextResponse r = client.audio().transcribe(TranscriptionRequest.of(
                wav, "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_1));
        System.out.println("  audio.transcribe: variant=" + r.getClass().getSimpleName()
                + " id=" + r.id());
    }

    /** Run a probe; log typed Fanar errors and continue the walk. Other throwables propagate. */
    private static void runProbe(String name, ThrowingRunnable probe) {
        try {
            probe.run();
        } catch (FanarException e) {
            System.out.println("  " + name + ": surfaced "
                    + e.getClass().getSimpleName() + " — error path exercised");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(name + " probe failed unexpectedly", e);
        }
    }

    /** Variant of {@link #runProbe} that returns a result (or {@code null} on typed failure). */
    private static <T> T runWithResult(String name, ThrowingSupplier<T> probe) {
        try {
            return probe.get();
        } catch (FanarException e) {
            System.out.println("  " + name + ": surfaced "
                    + e.getClass().getSimpleName() + " — error path exercised");
            return null;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(name + " probe failed unexpectedly", e);
        }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    // --- helpers ------------------------------------------------------------------------

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encode(FanarJsonCodec codec, Object value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.encode(out, value);
        return out.toByteArray();
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("self-test assertion failed: " + label);
        }
    }
}
