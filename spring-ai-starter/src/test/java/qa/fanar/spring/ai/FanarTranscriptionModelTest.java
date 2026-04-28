package qa.fanar.spring.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.audio.SttModel;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link FanarTranscriptionModel}. Spring AI's contract takes the audio as a
 * {@link Resource}; we use {@link ByteArrayResource} variants in tests to avoid filesystem I/O.
 */
class FanarTranscriptionModelTest {

    private HttpServer server;
    private FanarClient client;
    private final AtomicReference<byte[]> capturedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();

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
    void callTranscribesAudioAndReturnsText() {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = """
                    {"id":"tx-1","text":"hello transcribed"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("RIFF....".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "speech.wav"; }
        };
        AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(audio));

        assertThat(response.getResult().getOutput()).isEqualTo("hello transcribed");
        assertThat(capturedContentType.get()).startsWith("multipart/form-data");
        // Multipart bodies for STT carry the model name and the audio bytes; assert both made
        // it across the wire without round-tripping the codec.
        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("Fanar-Aura-STT-1");
        assertThat(bodyAsText).contains("speech.wav");
        assertThat(bodyAsText).contains("audio/wav");
    }

    @Test
    void contentTypeInferredFromExtension() {
        // Capture and assert that mp3 gets `audio/mpeg`. Server returns a stub response.
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("ID3.....".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "clip.mp3"; }
        };
        model.call(new AudioTranscriptionPrompt(audio));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("audio/mpeg");
    }

    @Test
    void unknownExtensionFallsBackToOctetStream() {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "weird.xyz"; }
        };
        model.call(new AudioTranscriptionPrompt(audio));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("application/octet-stream");
    }

    @Test
    void missingFilenameFallsBackToAudioAndOctetStream() {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        // Anonymous Resource with null filename — exercises the null-fallback branches.
        Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8));
        model.call(new AudioTranscriptionPrompt(audio));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("application/octet-stream");
    }

    @Test
    void allKnownExtensionsMap() {
        // Helper test: covers the .m4a/.flac/.ogg branches by issuing one call per extension
        // against a stub server that just echoes 200 OK.
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        for (String[] pair : new String[][]{
                {"clip.m4a", "audio/mp4"},
                {"clip.flac", "audio/flac"},
                {"clip.ogg", "audio/ogg"},
        }) {
            Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8)) {
                @Override public String getFilename() { return pair[0]; }
            };
            model.call(new AudioTranscriptionPrompt(audio));
            String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
            assertThat(bodyAsText).as("expected %s for %s", pair[1], pair[0]).contains(pair[1]);
        }
    }

    @Test
    void optionsModelOverridesDefault() {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "speech.wav"; }
        };
        // AudioTranscriptionOptions is an interface — implement inline with an override
        // that returns a non-default model id.
        var options = new org.springframework.ai.audio.transcription.AudioTranscriptionOptions() {
            @Override public String getModel() { return "Fanar-Aura-STT-LF-1"; }
        };
        model.call(new AudioTranscriptionPrompt(audio, options));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("Fanar-Aura-STT-LF-1");
    }

    @Test
    void blankOrNullModelOptionFallsBackToDefault() {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "speech.wav"; }
        };
        var blankOptions = new org.springframework.ai.audio.transcription.AudioTranscriptionOptions() {
            @Override public String getModel() { return "   "; }
        };
        model.call(new AudioTranscriptionPrompt(audio, blankOptions));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("Fanar-Aura-STT-1");
    }

    @Test
    void nullModelOptionFallsBackToDefault() {
        // AudioTranscriptionOptions impl whose getModel() returns null literally — covers the
        // null-clause of resolveModel's compound condition, distinct from the blank-string clause.
        server.createContext("/v1/audio/transcriptions", exchange -> {
            capturedRequestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"id\":\"x\",\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource audio = new ByteArrayResource("xx".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "speech.wav"; }
        };
        var nullOptions = new org.springframework.ai.audio.transcription.AudioTranscriptionOptions() {
            @Override public String getModel() { return null; }
        };
        model.call(new AudioTranscriptionPrompt(audio, nullOptions));

        String bodyAsText = new String(capturedRequestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsText).contains("Fanar-Aura-STT-1");
    }

    @Test
    void unreadableResourceSurfacesAsTransportException() {
        // A Resource that throws on getInputStream — exercises the IOException branch in
        // readAllBytes(). No HttpServer interaction needed.
        client = clientFor(server);
        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        Resource broken = new ByteArrayResource("dummy".getBytes(StandardCharsets.UTF_8)) {
            @Override public java.io.InputStream getInputStream() throws IOException {
                throw new IOException("simulated read failure");
            }
        };

        assertThat(catchThrowable(() -> model.call(new AudioTranscriptionPrompt(broken))))
                .isInstanceOf(FanarTransportException.class)
                .hasMessageContaining("Failed to read audio resource");
    }

    @Test
    void nullArgsRejected() {
        client = clientFor(server);
        FanarTranscriptionModel model = new FanarTranscriptionModel(client, SttModel.FANAR_AURA_STT_1);
        assertThat(catchThrowable(() -> model.call(null))).isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarTranscriptionModel(null, SttModel.FANAR_AURA_STT_1)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarTranscriptionModel(client, null)))
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
