package qa.fanar.obs.slf4j;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SLF4J adapter fed by a real, <em>retried</em> public call: {@code FanarClient.builder()
 * .observability(plugin)} → interceptor chain → scripted local server. The unit test drives
 * synthetic handles; this proves the SDK actually hands the adapter the {@code retry_attempt}
 * event, the {@code fanar.retry_count} / {@code http.status_code} attributes (ADR-013, ADR-025) and
 * the {@code fanar.ratelimit.*} window of the last attempt (ADR-026).
 */
@Tag("integration")
class Slf4jObservabilityPluginIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private final List<LogCall> calls = new CopyOnWriteArrayList<>();

    @Test
    void retriedCallIsLoggedWithItsRetryTelemetry() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK)
                        .withHeader("x-ratelimit-limit", "50")
                        .withHeader("x-ratelimit-remaining", "49")
                        .withHeader("x-ratelimit-reset", "60")
                        .withHeader("ratelimit-policy", "50;w=60"));

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                // no .jsonCodec(...): the Jackson 3 codec on the test module path is discovered via ServiceLoader (ADR-008)
                .retryPolicy(FAST)
                .observability(new Slf4jObservabilityPlugin(this::recordingLogger))
                .build()) {
            assertEquals("resp-1", client.chat().send(ping()).id());
        }

        assertEquals(2, server.hits(), "the 503 was retried once");
        assertTrue(calls.stream().anyMatch(c -> c.is("debug", "event={}") && "retry_attempt".equals(c.args()[1])),
                "the retry_attempt event is logged as it happens: " + calls);
        LogCall closed = calls.stream().filter(c -> c.is("debug", "ok in {}ms attrs={}")).findFirst()
                .orElseThrow(() -> new AssertionError("no success line logged on close: " + calls));
        assertTrue(closed.loggerName().startsWith("fanar.chat"), "logger is the operation name: " + closed.loggerName());
        Map<?, ?> attrs = (Map<?, ?>) closed.args()[2];
        assertEquals(1, attrs.get("fanar.retry_count"));
        assertEquals(200, attrs.get("http.status_code"), "last attempt's status wins");
        assertEquals(ChatModel.FANAR.wireValue(), attrs.get("fanar.model"));
        assertEquals(50L, attrs.get("fanar.ratelimit.limit"), "the window of the last attempt (ADR-026)");
        assertEquals(49L, attrs.get("fanar.ratelimit.remaining"));
        assertEquals(60L, attrs.get("fanar.ratelimit.reset"));
        assertEquals("50;w=60", attrs.get("fanar.ratelimit.policy"));
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }

    /** A {@link Logger} recording every call, debug and error enabled — the unit test's double. */
    private Logger recordingLogger(String name) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                (proxy, method, args) -> {
                    calls.add(new LogCall(name, method.getName(), args == null ? new Object[0] : args));
                    return switch (method.getName()) {
                        case "isDebugEnabled", "isErrorEnabled" -> true;
                        case "isTraceEnabled", "isInfoEnabled", "isWarnEnabled" -> false;
                        case "getName" -> name;
                        default -> null;
                    };
                });
    }

    private record LogCall(String loggerName, String method, Object[] args) {
        boolean is(String method, String format) {
            return this.method.equals(method) && args.length > 0 && format.equals(args[0]);
        }

        @Override
        public String toString() {
            return loggerName + "." + method + List.of(args);
        }
    }
}
