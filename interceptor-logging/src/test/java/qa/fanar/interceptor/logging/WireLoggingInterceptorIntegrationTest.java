package qa.fanar.interceptor.logging;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire logger inside the <em>real</em> chain: registered through {@code FanarClient.builder()
 * .addInterceptor(...)}, so it sits below the SDK's retry boundary and sees every attempt as raw
 * HTTP — the 503 body included — while the caller only sees the decoded success (ADR-012 amendment,
 * 2026-08-28). When the chain below it throws — the transport cannot connect, or a later
 * interceptor throws — it logs a {@code <-- failed} line on every attempt and the exception reaches
 * the caller unchanged (ADR-012 amendment, 2026-08-29). The unit test proves the formatting on
 * synthetic exchanges; this proves the placement.
 */
@Tag("integration")
class WireLoggingInterceptorIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private final List<String> blocks = new CopyOnWriteArrayList<>();

    @Test
    void registeredThroughTheBuilderItSeesEveryAttemptAsRawHttp() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK));
        WireLoggingInterceptor wire = WireLoggingInterceptor.builder()
                .level(WireLoggingInterceptor.Level.BODY)
                .sink(blocks::add)
                .build();

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                // no .jsonCodec(...): the Jackson 3 codec on the test module path is discovered via ServiceLoader (ADR-008)
                .retryPolicy(FAST)
                .addInterceptor(wire)
                .build()) {
            assertEquals("resp-1", client.chat().send(ping()).id());
        }

        assertEquals(2, server.hits(), "the 503 was retried once");
        assertEquals(4, blocks.size(), "request + response for each of the two attempts: " + blocks);
        assertTrue(blocks.get(0).startsWith("--> POST"), blocks.get(0));
        assertTrue(blocks.get(1).startsWith("<-- 503"), blocks.get(1));
        assertTrue(blocks.get(1).contains("busy"), "the raw error body is visible below the retry boundary: " + blocks.get(1));
        assertTrue(blocks.get(2).startsWith("--> POST"), blocks.get(2));
        assertTrue(blocks.get(3).startsWith("<-- 200"), blocks.get(3));
        assertTrue(blocks.get(3).contains("resp-1"), blocks.get(3));
        assertFalse(String.join("\n", blocks).contains("sk_test"), "the bearer token never reaches the log");
    }

    @Test
    void aTransportFailureBelowItIsLoggedOnEveryAttemptAndPropagates() {
        // A server that is already gone: the JDK client cannot connect, the transport wraps the
        // IOException into a FanarTransportException, and the retry boundary above the logger retries
        // it (retryable by default) — so the log must show each attempt's failure before the caller
        // gets the exception.
        URI gone;
        try (ScriptedHttpServer dead = ScriptedHttpServer.start()) {
            gone = dead.baseUri();
        }
        WireLoggingInterceptor wire = WireLoggingInterceptor.builder().sink(blocks::add).build();

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(gone)
                .retryPolicy(FAST.withMaxAttempts(2))
                .addInterceptor(wire)
                .build()) {
            assertThrows(FanarTransportException.class, () -> client.chat().send(ping()));
        }

        assertEquals(4, blocks.size(), "request + failure for each of the two attempts: " + blocks);
        assertTrue(blocks.get(0).startsWith("--> POST"), blocks.get(0));
        assertTrue(blocks.get(1).startsWith("<-- failed " + gone), blocks.get(1));
        assertTrue(blocks.get(1).contains("qa.fanar.core.FanarTransportException: "), blocks.get(1));
        assertTrue(blocks.get(2).startsWith("--> POST"), "the retry is logged as a fresh request: " + blocks.get(2));
        assertTrue(blocks.get(3).startsWith("<-- failed " + gone), blocks.get(3));
    }

    @Test
    void aLaterInterceptorThrowingIsLoggedAndPropagatesUnchanged() {
        WireLoggingInterceptor wire = WireLoggingInterceptor.builder().sink(blocks::add).build();
        IllegalStateException poisoned = new IllegalStateException("poisoned");

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .retryPolicy(RetryPolicy.disabled())
                .addInterceptor(wire)
                .addInterceptor((request, chain) -> { throw poisoned; })
                .build()) {
            assertSame(poisoned, assertThrows(IllegalStateException.class, () -> client.chat().send(ping())),
                    "a non-Fanar exception passes through the retry boundary and the facade untouched");
        }

        assertEquals(0, server.hits(), "the request never reached the transport");
        assertEquals(2, blocks.size(), "request + failure: " + blocks);
        assertTrue(blocks.get(0).startsWith("--> POST"), blocks.get(0));
        assertTrue(blocks.get(1).startsWith("<-- failed "), blocks.get(1));
        assertTrue(blocks.get(1).endsWith("java.lang.IllegalStateException: poisoned"), blocks.get(1));
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}
