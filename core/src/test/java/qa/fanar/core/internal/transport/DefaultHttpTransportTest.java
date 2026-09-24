package qa.fanar.core.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarTransportException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultHttpTransportTest {

    @Test
    void sendReturnsResponseFromServer() throws Exception {
        HttpServer server = startServer(exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        try {
            DefaultHttpTransport transport = new DefaultHttpTransport(HttpClient.newHttpClient(), null);
            HttpResponse<InputStream> response = transport.send(getRequest(server, "/"));
            assertEquals(200, response.statusCode());
            assertEquals("hello", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nullTimeoutPassesRequestThrough() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            DefaultHttpTransport transport = new DefaultHttpTransport(HttpClient.newHttpClient(), null);
            HttpResponse<InputStream> response = transport.send(getRequest(server, "/"));
            assertEquals(204, response.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestTimeoutIsAppliedWhenSet() throws Exception {
        // The handler stalls past the client's request timeout. This is the documented exception
        // to the no-sleep rule (CONTRIBUTING, Testing): the behaviour under test *is* elapsed time,
        // and the sleep is a generous upper bound on the server side, never a synchronisation
        // point on the test side.
        HttpServer server = startServer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            DefaultHttpTransport transport = new DefaultHttpTransport(
                    HttpClient.newHttpClient(), Duration.ofMillis(150));
            URI uri = URI.create("http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort() + "/");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("X-Custom", "keep-me")
                    .GET()
                    .build();
            FanarTransportException ex = assertThrows(
                    FanarTransportException.class, () -> transport.send(request));
            // The transport's own timed wait expires (the JDK request timer is not used, see the
            // class Javadoc) and surfaces as HttpTimeoutException — an IOException, so the
            // retry policy's transport-failure rule (ADR-014) sees the same type as before.
            assertInstanceOf(HttpTimeoutException.class, ex.getCause());
            assertTrue(ex.getMessage().startsWith("HTTP request timed out"),
                    "Expected the timeout message, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionFailureWrapsInTransportException() throws Exception {
        int freePort = findFreePort();
        DefaultHttpTransport transport = new DefaultHttpTransport(HttpClient.newHttpClient(), null);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + freePort + "/"))
                .GET()
                .build();

        FanarTransportException ex = assertThrows(
                FanarTransportException.class, () -> transport.send(request));
        assertInstanceOf(IOException.class, ex.getCause());
        assertTrue(ex.getMessage().startsWith("HTTP request failed"),
                "Expected transport-failure message, got: " + ex.getMessage());
    }

    @Test
    void interruptedSendPreservesInterruptFlag() throws Exception {
        // The handler stalls so the client is still blocked in send() when we interrupt it.
        // `requestArrived` is what makes the interrupt deterministic: it fires once the server has
        // the request in hand, which means the worker is inside send() and not merely started.
        // (The stall itself is the documented exception to the no-sleep rule — the behaviour under
        // test is a thread blocked on I/O.)
        CountDownLatch requestArrived = new CountDownLatch(1);
        HttpServer server = startServer(exchange -> {
            requestArrived.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            DefaultHttpTransport transport = new DefaultHttpTransport(HttpClient.newHttpClient(), null);
            HttpRequest request = getRequest(server, "/");

            AtomicReference<Throwable> caught = new AtomicReference<>();
            AtomicBoolean interruptFlag = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    transport.send(request);
                } catch (Throwable t) {
                    caught.set(t);
                    interruptFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();
            assertTrue(requestArrived.await(5, TimeUnit.SECONDS),
                    "the worker must be blocked inside send() before we interrupt it");
            worker.interrupt();
            worker.join(3_000);

            assertInstanceOf(FanarTransportException.class, caught.get());
            assertInstanceOf(InterruptedException.class, caught.get().getCause());
            assertTrue(interruptFlag.get(), "Interrupt flag must be preserved on the calling thread");
            assertEquals("HTTP request interrupted", caught.get().getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNullHttpClient() {
        assertThrows(NullPointerException.class,
                () -> new DefaultHttpTransport((HttpClient) null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new DefaultHttpTransport((Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>>) null,
                        Duration.ofSeconds(1)));
    }

    @Test
    void acceptsNullRequestTimeout() {
        // null timeout is valid — DefaultHttpTransport uses the inbound request unchanged.
        assertDoesNotThrow(() -> new DefaultHttpTransport(HttpClient.newHttpClient(), null));
    }

    // --- helpers

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static HttpRequest getRequest(HttpServer server, String path) {
        URI uri = URI.create("http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort() + path);
        return HttpRequest.newBuilder(uri).GET().build();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    // --- the exchange seam: outcomes and timing scripted without a server

    @Test
    void aResponseThatLandsAsTheWaitExpiresIsClosedNotLeaked() throws Exception {
        // The race: get(timeout) has thrown, and the headers arrive before cancel(true) runs — the
        // future is complete, cancel returns false, and the response nobody will return must
        // release its connection.
        CountDownLatch closed = new CountDownLatch(1);
        InputStream body = new InputStream() {
            public int read() { return -1; }
            public void close() { closed.countDown(); }
        };
        CompletableFuture<HttpResponse<InputStream>> raced = new CompletableFuture<>() {
            @Override
            public HttpResponse<InputStream> get(long timeout, TimeUnit unit) throws TimeoutException {
                complete(response(body));   // headers land while the caller is timing out
                throw new TimeoutException();
            }
        };
        DefaultHttpTransport transport = new DefaultHttpTransport(request -> raced, Duration.ofMillis(10));

        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build()));
        assertTrue(ex.getMessage().startsWith("HTTP request timed out"), ex.getMessage());
        assertTrue(closed.await(1, TimeUnit.SECONDS), "the completed response's body must be closed");
    }

    @Test
    void aCloseFailureWhileAbandoningIsSwallowed() {
        InputStream body = new InputStream() {
            public int read() { return -1; }
            public void close() throws IOException { throw new IOException("already gone"); }
        };
        CompletableFuture<HttpResponse<InputStream>> raced = new CompletableFuture<>() {
            @Override
            public HttpResponse<InputStream> get(long timeout, TimeUnit unit) throws TimeoutException {
                complete(response(body));
                throw new TimeoutException();
            }
        };
        DefaultHttpTransport transport = new DefaultHttpTransport(request -> raced, Duration.ofMillis(10));
        assertThrows(FanarTransportException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build()));
    }

    @Test
    void anExchangeThatFailedAsTheWaitExpiresHasNothingToClose() {
        CompletableFuture<HttpResponse<InputStream>> raced = new CompletableFuture<>() {
            @Override
            public HttpResponse<InputStream> get(long timeout, TimeUnit unit) throws TimeoutException {
                completeExceptionally(new IOException("reset"));
                throw new TimeoutException();
            }
        };
        DefaultHttpTransport transport = new DefaultHttpTransport(request -> raced, Duration.ofMillis(10));
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build()));
        assertTrue(ex.getMessage().startsWith("HTTP request timed out"), ex.getMessage());
    }

    @Test
    void aRuntimeFailureOfTheExchangeIsRethrownRawLikeHttpClientSendDoes() {
        // An IllegalArgumentException or SecurityException is a programming error, never a
        // retryable transport failure; the JDK's blocking send rethrows them too.
        IllegalArgumentException bad = new IllegalArgumentException("bad request shape");
        DefaultHttpTransport transport = new DefaultHttpTransport(
                request -> CompletableFuture.failedFuture(bad), null);
        assertSame(bad, assertThrows(IllegalArgumentException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build())));
    }

    @Test
    void anErrorFailingTheExchangeIsRethrownRaw() {
        AssertionError error = new AssertionError("boom");
        DefaultHttpTransport transport = new DefaultHttpTransport(
                request -> CompletableFuture.failedFuture(error), Duration.ofSeconds(1));
        assertSame(error, assertThrows(AssertionError.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build())));
    }

    @Test
    void aCheckedNonIoFailureOfTheExchangeIsWrapped() {
        Exception checked = new Exception("odd");
        DefaultHttpTransport transport = new DefaultHttpTransport(
                request -> CompletableFuture.failedFuture(checked), Duration.ofSeconds(1));
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://t/")).GET().build()));
        assertSame(checked, ex.getCause());
        assertEquals("HTTP request failed: odd", ex.getMessage());
    }

    private static HttpResponse<InputStream> response(InputStream body) {
        return new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public java.util.Optional<HttpResponse<InputStream>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public InputStream body() { return body; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { return URI.create("http://t/"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
