package qa.fanar.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

        Reply delayed = Reply.sse("data: {}\n\n")
                .withHeaderDelay(Duration.ofMillis(5))
                .withBodyDelay(Duration.ofMillis(7));
        assertEquals(Duration.ofMillis(5), delayed.headerDelay());
        assertEquals(Duration.ofMillis(7), delayed.bodyDelay());
        assertEquals(Duration.ofMillis(5), delayed.withHeader("X", "y").headerDelay(), "withHeader keeps the delays");
        assertEquals(Duration.ofMillis(7), delayed.thenDropConnection().bodyDelay(), "thenDropConnection keeps the delays");
        assertEquals(Duration.ZERO, Reply.of(200, "x").headerDelay(), "no delay unless asked");
        assertEquals(Duration.ZERO, Reply.of(200, "x").bodyDelay(), "no delay unless asked");
    }

    @Test
    void bodyDelayHoldsTheBodyBackButNotTheHeaders() throws Exception {
        // Headers first, then a pause, then the body. The client clocks the headers a little
        // after the server starts its pause, so the measured gap can land a few milliseconds
        // under the scripted delay; half the delay is the bound — a loaded machine stretches the
        // gap, never shrinks it, and headers held back with the body would give a gap near zero.
        Duration delay = Duration.ofMillis(300);
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            server.enqueue(Reply.sse("data: {\"x\":1}\n\n").withBodyDelay(delay));

            HttpResponse<InputStream> response = http.send(get(server.baseUri()), HttpResponse.BodyHandlers.ofInputStream());
            long headersAt = System.nanoTime();
            String body = new String(response.body().readAllBytes());
            long bodyAt = System.nanoTime();

            assertEquals(200, response.statusCode());
            assertEquals("data: {\"x\":1}\n\n", body);
            assertTrue(Duration.ofNanos(bodyAt - headersAt).compareTo(delay.dividedBy(2)) >= 0,
                    "the body must arrive well after the headers, not with them");
        }
    }

    @Test
    void headerDelayHoldsTheWholeReplyBack() throws Exception {
        Duration delay = Duration.ofMillis(300);
        try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
            server.enqueue(Reply.of(200, "late").withHeaderDelay(delay));

            long started = System.nanoTime();
            HttpResponse<String> response = http.send(get(server.baseUri()), HttpResponse.BodyHandlers.ofString());

            assertEquals("late", response.body());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(delay) >= 0,
                    "the headers must arrive no sooner than the scripted delay");
            assertEquals(1, server.hits());
        }
    }

    @Test
    void closingTheServerAbandonsAReplyStillBeingHeldBack() throws Exception {
        // A client that gives up before a long header delay leaves the handler mid-pause; closing
        // the fixture interrupts it, the reply is abandoned, and the script still counts as served.
        ScriptedHttpServer server = ScriptedHttpServer.start();
        server.enqueue(Reply.of(200, "never sent").withHeaderDelay(Duration.ofSeconds(30)));
        HttpRequest request = HttpRequest.newBuilder(server.baseUri()).timeout(Duration.ofMillis(200)).GET().build();

        assertThrows(IOException.class, () -> http.send(request, HttpResponse.BodyHandlers.ofString()),
                "the client's own timeout must fire first");
        assertEquals(1, server.hits());
        server.close();
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
