package qa.fanar.e2e.shared;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Simple diagnostic {@link Interceptor} that prints the outbound HTTP request (method, URI,
 * headers, body) and the inbound response (status, headers, body) to a caller-supplied sink.
 *
 * <p>Meant for debugging e2e failures — not a production-quality logger. Reads the response
 * body fully into memory to print it, then hands the subscriber a fresh {@link InputStream}
 * over the same bytes.</p>
 *
 * <p>Sample:</p>
 * <pre>{@code
 * FanarClient client = FanarClient.builder()
 *         .apiKey(...)
 *         .jsonCodec(...)
 *         .addInterceptor(LoggingInterceptor.toStdOut())
 *         .build();
 * }</pre>
 */
public final class LoggingInterceptor implements Interceptor {

    private final Consumer<String> sink;

    public LoggingInterceptor(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Convenience: emit lines via {@code System.out.println}. */
    public static LoggingInterceptor toStdOut() {
        return new LoggingInterceptor(System.out::println);
    }

    @Override
    public HttpResponse<InputStream> intercept(HttpRequest request, Chain chain) {
        sink.accept("--> " + request.method() + " " + request.uri());
        request.headers().map().forEach((k, values) ->
                values.forEach(v -> sink.accept("    " + k + ": " + redact(k, v))));
        sink.accept("");
        sink.accept(readRequestBody(request));
        sink.accept("");

        HttpResponse<InputStream> response = chain.proceed(request);

        sink.accept("<-- " + response.statusCode() + " " + request.uri());
        response.headers().map().forEach((k, values) ->
                values.forEach(v -> sink.accept("    " + k + ": " + v)));
        sink.accept("");

        byte[] body = drain(response.body());
        sink.accept(new String(body, StandardCharsets.UTF_8));
        sink.accept("");
        return replay(response, body);
    }

    private static String redact(String header, String value) {
        // Authorization carries the bearer token — never dump it in full.
        if ("Authorization".equalsIgnoreCase(header)) {
            int sp = value.indexOf(' ');
            return sp < 0 ? "[redacted]" : value.substring(0, sp + 1) + "[redacted]";
        }
        return value;
    }

    private static String readRequestBody(HttpRequest request) {
        Optional<HttpRequest.BodyPublisher> bp = request.bodyPublisher();
        if (bp.isEmpty()) {
            return "(no body)";
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        bp.get().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            public void onNext(ByteBuffer item) {
                byte[] arr = new byte[item.remaining()];
                item.get(arr);
                try {
                    buf.write(arr);
                } catch (IOException ignored) {
                    // ByteArrayOutputStream.write doesn't throw.
                }
            }
            public void onError(Throwable throwable) { future.completeExceptionally(throwable); }
            public void onComplete() { future.complete(buf.toByteArray()); }
        });
        try {
            return new String(future.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(body read failed: " + e.getMessage() + ")";
        }
    }

    private static byte[] drain(InputStream body) {
        try (InputStream in = body) {
            return in.readAllBytes();
        } catch (IOException e) {
            return ("(body read failed: " + e.getMessage() + ")").getBytes(StandardCharsets.UTF_8);
        }
    }

    private static HttpResponse<InputStream> replay(HttpResponse<InputStream> original, byte[] body) {
        return new HttpResponse<>() {
            public int statusCode() { return original.statusCode(); }
            public HttpRequest request() { return original.request(); }
            public Optional<HttpResponse<InputStream>> previousResponse() { return original.previousResponse(); }
            public HttpHeaders headers() { return original.headers(); }
            public InputStream body() { return new ByteArrayInputStream(body); }
            public Optional<SSLSession> sslSession() { return original.sslSession(); }
            public URI uri() { return original.uri(); }
            public HttpClient.Version version() { return original.version(); }
        };
    }

    // Retain ObservationHandle as an accessor just to document that the class needs no
    // observation access of its own; the field exists to satisfy future extension points.
    @SuppressWarnings("unused")
    private ObservationHandle observationSlot;
}
