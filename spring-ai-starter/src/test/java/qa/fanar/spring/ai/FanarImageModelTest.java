package qa.fanar.spring.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.images.ImageModel;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link FanarImageModel}. Same pattern as {@link FanarChatModelTest}: real
 * {@link HttpServer}, real {@link FanarClient}, no mocks.
 */
class FanarImageModelTest {

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
    void callForwardsPromptAndDecodesB64Json() {
        server.createContext("/v1/images/generations", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"id":"img-1","created":1700000000,
                     "data":[{"b64_json":"AAAA"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarImageModel model = new FanarImageModel(client, ImageModel.FANAR_ORYX_IG_2);
        ImageResponse response = model.call(new ImagePrompt("a calligraphy mosque"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("AAAA");
        assertThat(response.getResult().getOutput().getUrl()).isNull();
        assertThat(capturedRequestBody)
                .contains("\"prompt\":\"a calligraphy mosque\"")
                .contains("\"model\":\"Fanar-Oryx-IG-2\"");
    }

    @Test
    void multiMessagePromptsAreJoinedWithNewlines() {
        server.createContext("/v1/images/generations", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"id":"img","created":1,"data":[{"b64_json":"x"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarImageModel model = new FanarImageModel(client, ImageModel.FANAR_ORYX_IG_2);
        model.call(new ImagePrompt(List.of(
                new ImageMessage("a mosque"),
                new ImageMessage("at sunset"))));

        // Newline becomes "\n" in JSON.
        assertThat(capturedRequestBody).contains("\"prompt\":\"a mosque\\nat sunset\"");
    }

    @Test
    void imageOptionsModelOverridesDefault() {
        server.createContext("/v1/images/generations", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"id":"img","created":1,"data":[{"b64_json":"x"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        // Build options via the default builder and bypass the convenience class. Spring AI's
        // ImageOptionsBuilder produces a DefaultImageOptions that satisfies ImageOptions.
        FanarImageModel model = new FanarImageModel(client, ImageModel.FANAR_ORYX_IG_2);
        ImageOptions options = org.springframework.ai.image.ImageOptionsBuilder.builder()
                .model("future-fanar-image-model").build();
        model.call(new ImagePrompt("x", options));

        assertThat(capturedRequestBody).contains("\"model\":\"future-fanar-image-model\"");
    }

    @Test
    void blankModelOptionFallsBackToDefault() {
        server.createContext("/v1/images/generations", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"id":"img","created":1,"data":[{"b64_json":"x"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarImageModel model = new FanarImageModel(client, ImageModel.FANAR_ORYX_IG_2);
        ImageOptions options = org.springframework.ai.image.ImageOptionsBuilder.builder()
                .model("   ").build();
        model.call(new ImagePrompt("x", options));

        assertThat(capturedRequestBody).contains("\"model\":\"Fanar-Oryx-IG-2\"");
    }

    @Test
    void nullPromptAndConstructorArgsRejected() {
        client = clientFor(server);
        FanarImageModel model = new FanarImageModel(client, ImageModel.FANAR_ORYX_IG_2);
        assertThat(catchThrowable(() -> model.call(null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarImageModel(null, ImageModel.FANAR_ORYX_IG_2)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarImageModel(client, null)))
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
