package qa.fanar.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.testsupport.ScriptedHttpServer.Received;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedHttpServerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void servesRepliesInOrderAndRecordsEveryRequest() throws Exception {
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            server.enqueue(Reply.json(503, "{\"busy\":true}"), Reply.of(200, "ok", Map.of("X-Id", "42")));

            HttpResponse<String> first = http.send(post(server.baseUri().resolve("/v1/chat/completions"), "{\"a\":1}"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> second = http.send(post(server.baseUri().resolve("/v1/models"), ""),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(503, first.statusCode());
            assertEquals("{\"busy\":true}", first.body());
            assertEquals("application/json", first.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(200, second.statusCode());
            assertEquals("ok", second.body());
            assertEquals("42", second.headers().firstValue("X-Id").orElseThrow());

            assertEquals(2, server.hits());
            assertEquals(0, server.remaining());
            List<Received> received = server.received();
            assertEquals("POST", received.get(0).method());
            assertEquals("/v1/chat/completions", received.get(0).path());
            assertEquals("{\"a\":1}", received.get(0).bodyAsString());
            assertEquals("Bearer sk_test", received.get(0).header("authorization"), "header lookup is case-insensitive");
            assertEquals("/v1/models", server.lastReceived().path());
            assertNull(received.get(1).header("Nope"));
        }
    }

    @Test
    void unscriptedRequestIsAnsweredWith500AndFailsClose() throws Exception {
        ScriptedHttpServer server = ScriptedHttpServer.start();
        HttpResponse<String> response = http.send(post(server.baseUri().resolve("/anything"), ""),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("no reply scripted for request #1 POST /anything"), response.body());
        AssertionError error = assertThrows(AssertionError.class, server::close);
        assertTrue(error.getMessage().contains("1 unscripted request(s)"), error.getMessage());
    }

    @Test
    void unconsumedScriptFailsClose() {
        ScriptedHttpServer server = ScriptedHttpServer.start();
        server.enqueue(Reply.of(200, "never requested"));

        AssertionError error = assertThrows(AssertionError.class, server::close);
        assertTrue(error.getMessage().contains("1 scripted reply(ies) never requested"), error.getMessage());
    }

    @Test
    void droppedConnectionTruncatesTheResponse() throws Exception {
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            server.enqueue(Reply.sse("data: {\"x\":1}\n\n").thenDropConnection());

            HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("/stream")).GET().build();
            assertThrows(IOException.class, () -> http.send(request, HttpResponse.BodyHandlers.ofString()),
                    "the declared length is never satisfied, so the client must see a transport failure");
            assertEquals(1, server.hits());
        }
    }

    @Test
    void emptyBodyRepliesAreServedWithoutABody() throws Exception {
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            server.enqueue(Reply.of(204, ""), Reply.of(200, new byte[0], Map.of()));

            assertEquals(204, http.send(get(server.baseUri()), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> second = http.send(get(server.baseUri()), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, second.statusCode());
            assertEquals("", second.body());
        }
    }

    @Test
    void replyBuildersComposeHeaders() {
        Reply reply = Reply.json(429, "{}").withHeader("Retry-After", "1").withHeader("content-type", "text/plain");
        assertEquals(429, reply.status());
        assertEquals("1", reply.headers().get("Retry-After"));
        assertEquals("text/plain", reply.headers().get("content-type"), "later header wins, case-insensitively");
        assertEquals(1, reply.headers().keySet().stream().filter(k -> k.equalsIgnoreCase("content-type")).count());
        assertTrue(Reply.of(200, "x").thenDropConnection().dropAfterBody());
    }

    @Test
    void baseUriIsLoopbackOnAnEphemeralPort() throws Exception {
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            URI uri = server.baseUri();
            assertEquals("http", uri.getScheme());
            assertEquals("127.0.0.1", uri.getHost());
            assertTrue(uri.getPort() > 0);
            assertThrows(IllegalStateException.class, server::lastReceived);
        }
    }

    private static HttpRequest post(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer sk_test")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).GET().build();
    }
}
