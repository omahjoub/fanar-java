package qa.fanar.spring.boot.v4;

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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

class FanarHealthIndicatorTest {

    private HttpServer server;
    private FanarClient client;

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
    void reportsUpWhenModelsCallSucceeds() {
        server.createContext("/v1/models", exchange -> {
            byte[] body = ("""
                    {
                      "id":"req-abc",
                      "models":[
                        {"id":"Fanar","object":"model","created":1700000000,"owned_by":"QCRI"},
                        {"id":"Fanar-S","object":"model","created":1700000001,"owned_by":"QCRI"}
                      ]
                    }
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = clientFor(server);

        Health health = new FanarHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("models", 2);
        assertThat(health.getDetails()).containsEntry("requestId", "req-abc");
    }

    @Test
    void reportsDownOnAuthFailure() {
        server.createContext("/v1/models", exchange -> {
            byte[] body = ("""
                    {"error":{"message":"bad key","code":"unauthorized","type":"auth"}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = clientFor(server);

        Health health = new FanarHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("httpStatus", 401);
        assertThat(health.getDetails()).containsKey("error");
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
