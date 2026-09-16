package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telemetry cannot fail a request — proved through the <em>public</em> API:
 * {@code FanarClient.builder()} → {@code chat().send()} → interceptor chain → real JDK transport →
 * a local {@link ScriptedHttpServer}, with a plugin that throws from every SPI method.
 *
 * <p>This is the seam the guarantee lives on, and it is the reason the test is here rather than
 * only in {@code IsolatingObservabilityPluginTest}: the wrapper is applied in the
 * {@code FanarClient} constructor, so a unit test of the wrapper proves the wrapper, not that the
 * client actually uses it. Before the wrapper existed, isolation depended on <em>how</em> the
 * plugin was installed — a lone plugin was unguarded while two composed plugins were guarded, which
 * is precisely backwards (ADR-013, ADR-022).</p>
 *
 * <p>Both installation routes are covered: a plugin set directly, and one supplied through
 * {@link ObservabilityPlugin#compose}, which returns a single plugin unwrapped.</p>
 */
@Tag("integration")
class FanarClientObservabilityIsolationIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    /** Throws from every SPI method, as a networked telemetry backend can on a bad day. */
    private static final class Exploding implements ObservabilityPlugin, ObservationHandle {
        @Override public ObservationHandle start(String operationName) { return this; }
        @Override public ObservationHandle attribute(String k, Object v) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle event(String n) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle error(Throwable t) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle child(String n) { throw new IllegalStateException("boom"); }
        @Override public Map<String, String> propagationHeaders() { throw new IllegalStateException("boom"); }
        @Override public void close() { throw new IllegalStateException("boom"); }
    }

    @Test
    void aPluginSetDirectlyCannotFailTheCall() {
        server.enqueue(Reply.json(200, RESPONSE));

        try (FanarClient client = client(new Exploding())) {
            ChatResponse r = assertDoesNotThrow(() -> client.chat().send(ping()),
                    "a throwing plugin must not reach the caller");
            assertEquals("resp-1", r.id(), "and the response is intact");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void aSinglePluginSuppliedThroughComposeCannotFailTheCallEither() {
        // compose(single) returns the plugin unwrapped, so this is the route that used to be
        // unguarded — the guarantee must not depend on how many plugins happen to be installed.
        server.enqueue(Reply.json(200, RESPONSE));

        try (FanarClient client = client(ObservabilityPlugin.compose(new Exploding()))) {
            assertDoesNotThrow(() -> client.chat().send(ping()));
        }
        assertEquals(1, server.hits());
    }

    @Test
    void aThrowingPluginDoesNotSuppressAGenuineFailureFromTheCall() {
        // The exception the caller sees must be Fanar's, not the plugin's — the wrapper must not
        // swallow the real error path along with the telemetry.
        server.enqueue(Reply.json(401, """
                {"error":{"code":"invalid_authentication","message":"Invalid authentication","status":401}}"""));

        try (FanarClient client = client(new Exploding())) {
            FanarAuthenticationException e = assertThrows(
                    FanarAuthenticationException.class, () -> client.chat().send(ping()));
            assertEquals("Invalid authentication", e.getMessage());
        }
        assertEquals(1, server.hits(), "a 401 is not retryable");
    }

    @Test
    void aHealthyPluginStillSeesEveryCall() {
        // The guard must not cost observability its job.
        server.enqueue(Reply.json(200, RESPONSE));
        List<String> calls = new CopyOnWriteArrayList<>();

        ObservabilityPlugin recording = operationName -> {
            calls.add("start:" + operationName);
            return new ObservationHandle() {
                @Override public ObservationHandle attribute(String k, Object v) { calls.add("attribute:" + k); return this; }
                @Override public ObservationHandle event(String n) { calls.add("event:" + n); return this; }
                @Override public ObservationHandle error(Throwable t) { calls.add("error"); return this; }
                @Override public ObservationHandle child(String n) { return this; }
                @Override public Map<String, String> propagationHeaders() { return Map.of(); }
                @Override public void close() { calls.add("close"); }
            };
        };

        try (FanarClient client = client(recording)) {
            assertDoesNotThrow(() -> client.chat().send(ping()));
        }

        assertTrue(calls.contains("start:fanar.chat.send"), "the operation is still observed: " + calls);
        assertTrue(calls.contains("attribute:fanar.model"), "attributes still land: " + calls);
        assertTrue(calls.contains("close"), "the observation still closes: " + calls);
    }

    private static final String RESPONSE = """
            {"id":"resp-1","object":"chat.completion","created":1,"model":"Fanar","choices":[]}""";

    private FanarClient client(ObservabilityPlugin plugin) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(codec())
                .observability(plugin)
                .retryPolicy(FAST)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }

    /** Encodes the request; decodes the canned success body into a minimal {@link ChatResponse}. */
    private static FanarJsonCodec codec() {
        return new FanarJsonCodec() {
            @Override
            public void encode(OutputStream out, Object value) throws IOException {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                in.readAllBytes();
                return type.cast(new ChatResponse("resp-1", List.of(), 1L, "Fanar", null, null));
            }
        };
    }
}
