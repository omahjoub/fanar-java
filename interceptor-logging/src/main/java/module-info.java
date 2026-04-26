/**
 * Wire-level logging interceptor for the Fanar Java SDK.
 *
 * <p>Sits in the {@code FanarClient} interceptor chain and prints the outbound HTTP request and
 * inbound HTTP response — method, URL, headers, body — at a configurable level. Sink is SLF4J
 * by default (logger name {@code fanar.wire} at {@code DEBUG}); a {@code Consumer<String>} hook
 * routes lines to stdout, files, or any custom destination.</p>
 *
 * <p>Streaming-aware: at {@code BODY} level the response body is captured only when it is not
 * a streaming media type, so SSE and other chunked responses keep flowing without being drained
 * into memory.</p>
 */
module qa.fanar.interceptor.logging {
    requires qa.fanar.core;
    requires org.slf4j;

    requires java.net.http;

    exports qa.fanar.interceptor.logging;
}
