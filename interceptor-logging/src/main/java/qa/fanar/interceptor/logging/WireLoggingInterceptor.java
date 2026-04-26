package qa.fanar.interceptor.logging;

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
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qa.fanar.core.spi.Interceptor;

/**
 * {@link Interceptor} that prints the outbound HTTP request and the inbound HTTP response to a
 * configurable sink. Modeled after OkHttp's {@code HttpLoggingInterceptor}: a four-step level
 * ladder from silence to full body capture.
 *
 * <h2>Levels</h2>
 *
 * <ul>
 *   <li>{@link Level#NONE} — interceptor is a no-op.</li>
 *   <li>{@link Level#BASIC} — one line per request {@code (--> METHOD URL)} and one per response
 *       {@code (<-- STATUS URL (Nms))}.</li>
 *   <li>{@link Level#HEADERS} — {@code BASIC} plus all request and response headers (with the
 *       configured ones redacted).</li>
 *   <li>{@link Level#BODY} — {@code HEADERS} plus request and response bodies, truncated to the
 *       configured byte cap. Streaming responses ({@code Content-Type: text/event-stream}) are
 *       <em>not</em> drained — at {@code BODY} level the interceptor still emits headers but
 *       prints {@code (streaming response — body not captured)} in place of the body so SSE
 *       chunks keep flowing to the consumer.</li>
 * </ul>
 *
 * <h2>Sink</h2>
 *
 * <p>By default the interceptor routes lines through SLF4J: logger name {@code fanar.wire},
 * level {@code DEBUG}. Configure your SLF4J binding to silence, redirect, or change format. For
 * non-SLF4J destinations (stdout, a custom file, a structured-log shipper), pass a
 * {@code Consumer<String>} via {@link Builder#sink(Consumer)}.</p>
 *
 * <h2>Redaction</h2>
 *
 * <p>By default, the {@code Authorization} header value is partially redacted (the scheme prefix
 * — {@code Bearer}, {@code Basic} — is preserved, the credential is replaced with
 * {@code [redacted]}). Add header names to redact via {@link Builder#addRedactedHeader(String)};
 * non-{@code Authorization} headers in the redaction set have their entire value replaced.
 * Header-name comparison is case-insensitive (per RFC 7230).</p>
 *
 * <h2>Body byte cap</h2>
 *
 * <p>At {@code BODY} level, request and response bodies are truncated to
 * {@link Builder#bodyByteCap(int)} bytes (default 8192). Truncation appends a marker
 * {@code (... N more bytes elided)} so callers know the body was cut. This protects against
 * accidentally logging multi-megabyte audio downloads or image-generation responses.</p>
 *
 * <p>Thread-safe: a single instance is shared across all requests on a {@code FanarClient}.</p>
 */
public final class WireLoggingInterceptor implements Interceptor {

    private static final String DEFAULT_LOGGER_NAME = "fanar.wire";
    private static final int DEFAULT_BODY_BYTE_CAP = 8192;
    private static final String STREAMING_CONTENT_TYPE_PREFIX = "text/event-stream";

    /** OkHttp-style log-volume ladder. */
    public enum Level {
        /** No log lines. */
        NONE,
        /** One line per request and one per response (status + duration). */
        BASIC,
        /** {@code BASIC} plus all request/response headers (with redaction applied). */
        HEADERS,
        /** {@code HEADERS} plus bodies (truncated to {@link Builder#bodyByteCap(int)} bytes). */
        BODY
    }

    private final Level level;
    private final Consumer<String> sink;
    private final Set<String> redactedHeadersLower;
    private final int bodyByteCap;

    private WireLoggingInterceptor(Builder b) {
        this.level = b.level;
        this.sink = b.sink;
        this.redactedHeadersLower = Set.copyOf(b.redactedHeadersLower);
        this.bodyByteCap = b.bodyByteCap;
    }

    /** Begin building a customized interceptor. Defaults: BASIC level, SLF4J sink, 8 KB body cap. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public HttpResponse<InputStream> intercept(HttpRequest request, Chain chain) {
        if (level == Level.NONE) {
            return chain.proceed(request);
        }

        long start = System.nanoTime();
        sink.accept(buildRequestBlock(request));

        HttpResponse<InputStream> response = chain.proceed(request);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;

        if (level != Level.BODY) {
            sink.accept(buildResponseBlock(response, request.uri(), durationMs, null));
            return response;
        }
        if (isStreamingResponse(response)) {
            sink.accept(buildResponseBlock(response, request.uri(), durationMs,
                    "(streaming response — body not captured)"));
            return response;
        }
        byte[] body = drain(response.body());
        sink.accept(buildResponseBlock(response, request.uri(), durationMs, formatBody(body)));
        return replay(response, body);
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Build the request side of an exchange as a single multi-line message. One sink call per
     * direction keeps the SLF4J prefix (timestamp, level, logger) from being repeated for every
     * header / body byte and lets log shippers treat each direction as one event.
     */
    private String buildRequestBlock(HttpRequest request) {
        StringBuilder sb = new StringBuilder()
                .append("--> ").append(request.method()).append(' ').append(request.uri());
        if (level == Level.HEADERS || level == Level.BODY) {
            appendHeaders(sb, request.headers(), true);
        }
        if (level == Level.BODY) {
            sb.append('\n').append('\n').append(readRequestBody(request));
        }
        return sb.toString();
    }

    /**
     * Build the response side of an exchange. {@code body} is appended verbatim if non-null;
     * pass {@code null} when the level is below {@code BODY} or the response is streaming.
     */
    private String buildResponseBlock(HttpResponse<?> response, java.net.URI uri,
                                      long durationMs, String body) {
        StringBuilder sb = new StringBuilder()
                .append("<-- ").append(response.statusCode()).append(' ').append(uri)
                .append(" (").append(durationMs).append("ms)");
        if (level == Level.HEADERS || level == Level.BODY) {
            appendHeaders(sb, response.headers(), false);
        }
        if (body != null) {
            sb.append('\n').append('\n').append(body);
        }
        return sb.toString();
    }

    private void appendHeaders(StringBuilder sb, HttpHeaders headers, boolean isRequest) {
        headers.map().forEach((k, values) ->
                values.forEach(v -> sb.append('\n').append("    ").append(k).append(": ")
                        .append(redact(k, v, isRequest))));
    }

    private String redact(String header, String value, boolean isRequest) {
        if (!redactedHeadersLower.contains(header.toLowerCase(Locale.ROOT))) {
            return value;
        }
        if ("authorization".equalsIgnoreCase(header) && isRequest) {
            int sp = value.indexOf(' ');
            return sp < 0 ? "[redacted]" : value.substring(0, sp + 1) + "[redacted]";
        }
        return "[redacted]";
    }

    private String readRequestBody(HttpRequest request) {
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
                buf.writeBytes(arr);
            }
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }
            public void onComplete() {
                future.complete(buf.toByteArray());
            }
        });
        try {
            return formatBody(future.get(2, TimeUnit.SECONDS));
        } catch (Exception e) {
            return "(body read failed: " + e.getMessage() + ")";
        }
    }

    private String formatBody(byte[] body) {
        if (body.length == 0) {
            return "(empty body)";
        }
        if (body.length <= bodyByteCap) {
            return new String(body, StandardCharsets.UTF_8);
        }
        String prefix = new String(body, 0, bodyByteCap, StandardCharsets.UTF_8);
        return prefix + "\n... (" + (body.length - bodyByteCap) + " more bytes elided)";
    }

    private static boolean isStreamingResponse(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(ct -> ct.startsWith(STREAMING_CONTENT_TYPE_PREFIX))
                .orElse(false);
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

    private static Consumer<String> defaultSlf4jSink() {
        Logger logger = LoggerFactory.getLogger(DEFAULT_LOGGER_NAME);
        return logger::debug;
    }

    /** Fluent builder for {@link WireLoggingInterceptor}. */
    public static final class Builder {

        private Level level = Level.BASIC;
        private Consumer<String> sink = defaultSlf4jSink();
        private final Set<String> redactedHeadersLower = new HashSet<>(Set.of("authorization"));
        private int bodyByteCap = DEFAULT_BODY_BYTE_CAP;

        private Builder() { }

        /**
         * Set the log-volume level. Default {@link Level#BASIC}.
         *
         * @param level the level; must not be {@code null}
         */
        public Builder level(Level level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Replace the default SLF4J sink with a custom destination.
         *
         * @param sink receives one line per emitted record; must not be {@code null}
         */
        public Builder sink(Consumer<String> sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
            return this;
        }

        /**
         * Replace the redacted-header set in one call. Header-name comparison is
         * case-insensitive. Default: {@code Set.of("Authorization")}.
         *
         * @param headers header names to redact; must not be {@code null}
         */
        public Builder redactedHeaders(Set<String> headers) {
            Objects.requireNonNull(headers, "headers");
            this.redactedHeadersLower.clear();
            for (String h : headers) {
                addRedactedHeader(h);
            }
            return this;
        }

        /**
         * Add a header name to the redaction set. Comparison is case-insensitive.
         *
         * @param header header name; must not be {@code null}
         */
        public Builder addRedactedHeader(String header) {
            Objects.requireNonNull(header, "header");
            this.redactedHeadersLower.add(header.toLowerCase(Locale.ROOT));
            return this;
        }

        /**
         * Set the maximum number of body bytes to log at {@link Level#BODY}. Bodies larger than
         * this are truncated with a {@code (... N more bytes elided)} marker. Default 8 KB.
         *
         * @param bytes maximum body size in bytes; must be non-negative
         */
        public Builder bodyByteCap(int bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("bodyByteCap must be non-negative, got " + bytes);
            }
            this.bodyByteCap = bytes;
            return this;
        }

        /** Build the interceptor. */
        public WireLoggingInterceptor build() {
            return new WireLoggingInterceptor(this);
        }
    }

    // Visible for documentation / debugging — gives a deterministic snapshot of the redacted set
    // (lowercased) so users can verify configuration.
    Set<String> redactedHeadersForTest() {
        return new TreeSet<>(redactedHeadersLower);
    }
}
