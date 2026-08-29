package qa.fanar.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A loopback {@link HttpServer} that answers requests from a scripted queue of {@link Reply replies}
 * — the fixture behind every {@code *IntegrationTest} in the reactor.
 *
 * <p>Binds {@link InetAddress#getLoopbackAddress() loopback} on an ephemeral port, serves every path
 * from one handler, records each request it sees ({@link #received()}) and hands out replies
 * strictly in the order they were {@linkplain #enqueue(Reply...) enqueued}. Handlers run on virtual
 * threads, so concurrent in-flight requests never deadlock the dispatcher.</p>
 *
 * <p>{@link #close()} stops the server and then <em>fails</em> with an {@link AssertionError} if
 * the script was not fully consumed, or if the server saw more requests than were scripted (those
 * are answered with a 500 whose body names the problem). Declare the field with JUnit's
 * {@code @AutoClose} and every test gets that check for free:</p>
 *
 * <pre>{@code
 * @AutoClose
 * private final ScriptedHttpServer server = ScriptedHttpServer.start();
 *
 * @Test
 * void retriesOnce() {
 *     server.enqueue(Reply.of(503, "busy"), Reply.json(200, "{}"));
 *     try (FanarClient client = FanarClient.builder().baseUrl(server.baseUri())…build()) {
 *         client.chat().send(request);
 *     }
 *     assertEquals(2, server.hits());
 * }
 * }</pre>
 *
 * @author Oussama Mahjoub
 */
public final class ScriptedHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Received> unscripted = new CopyOnWriteArrayList<>();

    private ScriptedHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Bind a fresh server on the loopback interface and an ephemeral port, register the catch-all
     * handler and start serving.
     *
     * @return the running server; close it (or let {@code @AutoClose} do so) when the test ends
     */
    public static ScriptedHttpServer start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            ScriptedHttpServer scripted = new ScriptedHttpServer(server, executor);
            server.createContext("/", scripted::handle);
            server.start();
            return scripted;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the scripted server", e);
        }
    }

    /**
     * The URI clients should use as their base URL — {@code http://127.0.0.1:<port>}, no path.
     *
     * @return the server's base URI
     */
    public URI baseUri() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort());
    }

    /**
     * Append replies to the script; they are served in this order, one per request, on any path.
     *
     * @param replies the replies to serve next
     * @return this server, for chaining
     */
    public ScriptedHttpServer enqueue(Reply... replies) {
        for (Reply reply : replies) {
            script.add(Objects.requireNonNull(reply, "reply"));
        }
        return this;
    }

    /**
     * Number of requests the server has answered so far, scripted or not.
     *
     * @return the request count
     */
    public int hits() {
        return received.size();
    }

    /**
     * Every request seen so far, in arrival order.
     *
     * @return an unmodifiable snapshot
     */
    public List<Received> received() {
        return List.copyOf(received);
    }

    /**
     * The most recent request.
     *
     * @return the last request seen
     * @throws IllegalStateException if nothing has been received yet
     */
    public Received lastReceived() {
        if (received.isEmpty()) {
            throw new IllegalStateException("no request received yet");
        }
        return received.get(received.size() - 1);
    }

    /**
     * Number of scripted replies not yet served.
     *
     * @return the remaining script length
     */
    public int remaining() {
        return script.size();
    }

    /**
     * Fail unless every scripted reply was requested and no unscripted request arrived.
     *
     * @throws AssertionError describing what went wrong
     */
    public void assertExhausted() {
        List<String> problems = new ArrayList<>();
        if (!script.isEmpty()) {
            problems.add(script.size() + " scripted reply(ies) never requested");
        }
        if (!unscripted.isEmpty()) {
            problems.add(unscripted.size() + " unscripted request(s) answered with 500: "
                    + unscripted.stream().map(r -> r.method() + " " + r.path()).toList());
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("ScriptedHttpServer: " + String.join("; ", problems));
        }
    }

    /** Stop the server, then {@link #assertExhausted() verify} the script was honoured. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertExhausted();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody;
        try (InputStream in = exchange.getRequestBody()) {
            requestBody = in.readAllBytes();
        }
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                copyOf(exchange.getRequestHeaders()), requestBody);
        received.add(request);

        Reply reply = script.poll();
        if (reply == null) {
            unscripted.add(request);
            reply = Reply.of(500, "ScriptedHttpServer: no reply scripted for request #" + received.size()
                    + " " + request.method() + " " + request.path());
        }
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] body = reply.body();
        if (reply.dropAfterBody()) {
            // Promise one byte more than is sent: closing the exchange short of the declared length
            // makes the JDK server close the socket, so the client observes a truncated response
            // rather than a clean end of stream.
            exchange.sendResponseHeaders(reply.status(), body.length + 1L);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.flush();
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, List<String>> copyOf(Headers headers) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * One scripted reply.
     *
     * @param status        the HTTP status to answer with
     * @param headers       response headers to add
     * @param body          the response body; empty means "no body"
     * @param dropAfterBody whether to close the connection short of the declared length after
     *                      writing the body, so the client sees a truncated response
     */
    public record Reply(int status, Map<String, String> headers, byte[] body, boolean dropAfterBody) {

        /** Defensive copies; {@code headers} and {@code body} must not be null. */
        public Reply {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        /**
         * A reply with a UTF-8 text body and no extra headers.
         *
         * @param status the HTTP status
         * @param body   the body text
         * @return the reply
         */
        public static Reply of(int status, String body) {
            return of(status, body, Map.of());
        }

        /**
         * A reply with a UTF-8 text body and the given headers.
         *
         * @param status  the HTTP status
         * @param body    the body text
         * @param headers response headers
         * @return the reply
         */
        public static Reply of(int status, String body, Map<String, String> headers) {
            return new Reply(status, headers, body.getBytes(StandardCharsets.UTF_8), false);
        }

        /**
         * A reply with a binary body and the given headers.
         *
         * @param status  the HTTP status
         * @param body    the body bytes
         * @param headers response headers
         * @return the reply
         */
        public static Reply of(int status, byte[] body, Map<String, String> headers) {
            return new Reply(status, headers, body, false);
        }

        /**
         * A {@code Content-Type: application/json} reply.
         *
         * @param status the HTTP status
         * @param json   the JSON body
         * @return the reply
         */
        public static Reply json(int status, String json) {
            return of(status, json, Map.of("Content-Type", "application/json"));
        }

        /**
         * A 200 {@code Content-Type: text/event-stream} reply carrying the given SSE frames.
         *
         * @param frames the raw SSE body, e.g. {@code "data: [DONE]\n\n"}
         * @return the reply
         */
        public static Reply sse(String frames) {
            return of(200, frames, Map.of("Content-Type", "text/event-stream"));
        }

        /**
         * This reply plus one header.
         *
         * @param name  the header name
         * @param value the header value
         * @return a new reply
         */
        public Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>();
            headers.forEach((existing, v) -> {
                if (!existing.equalsIgnoreCase(name)) {
                    merged.put(existing, v);
                }
            });
            merged.put(name, value);
            return new Reply(status, merged, body, dropAfterBody);
        }

        /**
         * This reply, but the connection is dropped right after the body is written — the client
         * observes an abruptly terminated response instead of a clean end.
         *
         * @return a new reply
         */
        public Reply thenDropConnection() {
            return new Reply(status, headers, body, true);
        }
    }

    /**
     * One request the server saw.
     *
     * @param method  the HTTP method
     * @param path    the request path (no query string)
     * @param headers request headers, keyed case-insensitively
     * @param body    the request body bytes (empty when there was none)
     */
    public record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {

        /**
         * The body decoded as UTF-8.
         *
         * @return the body text
         */
        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /**
         * The first value of a header, looked up case-insensitively.
         *
         * @param name the header name
         * @return the first value, or {@code null} when absent
         */
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
