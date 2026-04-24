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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarTransportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            // Request carries an explicit header so the newBuilder(req, headerFilter) rebuild on
            // the timeout path actually invokes the filter lambda.
            URI uri = URI.create("http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort() + "/");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("X-Custom", "keep-me")
                    .GET()
                    .build();
            FanarTransportException ex = assertThrows(
                    FanarTransportException.class, () -> transport.send(request));
            // The JDK HttpClient raises HttpTimeoutException (an IOException) when the per-request
            // timeout fires — we should see that wrapped as FanarTransportException.
            assertInstanceOf(IOException.class, ex.getCause());
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
        HttpServer server = startServer(exchange -> {
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
            Thread.sleep(200);
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
                () -> new DefaultHttpTransport(null, Duration.ofSeconds(1)));
    }

    @Test
    void acceptsNullRequestTimeout() {
        // null timeout is valid — DefaultHttpTransport uses the inbound request unchanged.
        new DefaultHttpTransport(HttpClient.newHttpClient(), null);
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
}
