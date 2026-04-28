package qa.fanar.spring.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.Voice;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link FanarTextToSpeechModel}. The endpoint returns binary audio bytes
 * directly — we serve a fixed magic byte sequence from the local server and assert it round-trips
 * untouched through the adapter.
 */
class FanarTextToSpeechModelTest {

    private static final byte[] FAKE_AUDIO = "AUDIO-BYTES".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private FanarClient client;
    private String capturedRequestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(null);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callForwardsTextAndReturnsAudioBytes() {
        server.createContext("/v1/audio/speech", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, FAKE_AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(FAKE_AUDIO); }
        });
        server.start();
        client = clientFor(server);

        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);
        TextToSpeechResponse response = model.call(new TextToSpeechPrompt("hello world"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput()).isEqualTo(FAKE_AUDIO);
        assertThat(capturedRequestBody)
                .contains("\"input\":\"hello world\"")
                .contains("\"model\":\"Fanar-Aura-TTS-2\"")
                .contains("\"voice\":\"Amelia\"");
    }

    @Test
    void streamEmitsSingleResponseWithTheSameAudio() {
        server.createContext("/v1/audio/speech", exchange -> {
            exchange.sendResponseHeaders(200, FAKE_AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(FAKE_AUDIO); }
        });
        server.start();
        client = clientFor(server);

        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);
        // Fanar's TTS isn't a true stream — the adapter wraps the one-shot result as a
        // single-element Flux. Asserting cardinality + content matches the call() path.
        var chunks = model.stream(new TextToSpeechPrompt("hi")).collectList().block(Duration.ofSeconds(5));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getResult().getOutput()).isEqualTo(FAKE_AUDIO);
    }

    @Test
    void optionsOverrideModelVoiceAndFormat() {
        server.createContext("/v1/audio/speech", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, FAKE_AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(FAKE_AUDIO); }
        });
        server.start();
        client = clientFor(server);

        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);
        TextToSpeechOptions options = TextToSpeechOptions.builder()
                .model("Fanar-Sadiq-TTS-1")
                .voice("Hamad")
                .format("wav")
                .build();
        model.call(new TextToSpeechPrompt("hi", options));

        assertThat(capturedRequestBody)
                .contains("\"model\":\"Fanar-Sadiq-TTS-1\"")
                .contains("\"voice\":\"Hamad\"")
                .contains("\"response_format\":\"wav\"");
    }

    @Test
    void blankOptionFieldsFallBackToDefaults() {
        server.createContext("/v1/audio/speech", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, FAKE_AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(FAKE_AUDIO); }
        });
        server.start();
        client = clientFor(server);

        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);
        TextToSpeechOptions options = TextToSpeechOptions.builder()
                .model("   ").voice("").format("   ").build();
        model.call(new TextToSpeechPrompt("hi", options));

        assertThat(capturedRequestBody)
                .contains("\"model\":\"Fanar-Aura-TTS-2\"")
                .contains("\"voice\":\"Amelia\"")
                .doesNotContain("\"response_format\"");  // null format is dropped from JSON
    }

    @Test
    void defaultBuiltOptionsWithNullFieldsFallBackToDefaults() {
        // Two paths through the resolve helpers' null-handling:
        //   1. options == null entirely (constructor passed null, or setOptions(null)).
        //   2. options non-null but getModel/getVoice/getFormat all return null
        //      (e.g. TextToSpeechOptions.builder().build() with no setters called).
        // Two distinct calls below cover branches (1) and (2) respectively.
        server.createContext("/v1/audio/speech", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, FAKE_AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(FAKE_AUDIO); }
        });
        server.start();
        client = clientFor(server);

        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);

        // (1) options == null — explicit null via the 2-arg constructor.
        model.call(new TextToSpeechPrompt("hi", null));
        assertThat(capturedRequestBody)
                .contains("\"model\":\"Fanar-Aura-TTS-2\"")
                .contains("\"voice\":\"Amelia\"")
                .doesNotContain("\"response_format\"");

        // (2) options non-null but every field null — default-built options.
        TextToSpeechOptions emptyOptions = TextToSpeechOptions.builder().build();
        model.call(new TextToSpeechPrompt("hi", emptyOptions));
        assertThat(capturedRequestBody)
                .contains("\"model\":\"Fanar-Aura-TTS-2\"")
                .contains("\"voice\":\"Amelia\"")
                .doesNotContain("\"response_format\"");
    }

    @Test
    void nullArgsRejected() {
        client = clientFor(server);
        FanarTextToSpeechModel model = new FanarTextToSpeechModel(
                client, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA);
        // Cast disambiguates against TextToSpeechModel's default `call(String)` overload.
        assertThat(catchThrowable(() -> model.call((TextToSpeechPrompt) null))).isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarTextToSpeechModel(null, TtsModel.FANAR_AURA_TTS_2, Voice.AMELIA)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarTextToSpeechModel(client, null, Voice.AMELIA)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarTextToSpeechModel(client, TtsModel.FANAR_AURA_TTS_2, null)))
                .isInstanceOf(NullPointerException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }

    private static FanarClient clientFor(HttpServer server) {
        URI base = URI.create("http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort());
        return FanarClient.builder()
                .apiKey("test-key")
                .baseUrl(base)
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(2))
                .retryPolicy(RetryPolicy.disabled())
                .jsonCodec(new Jackson3FanarJsonCodec())
                .build();
    }
}
