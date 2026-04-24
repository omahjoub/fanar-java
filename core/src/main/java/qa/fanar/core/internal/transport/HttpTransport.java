package qa.fanar.core.internal.transport;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Abstraction for the tail of the interceptor chain — the actual HTTP send.
 *
 * <p>Defined as an interface so tests can substitute a lambda without standing up a real HTTP
 * server or subclassing the JDK's {@link java.net.http.HttpClient}. Production code uses
 * {@link DefaultHttpTransport}, which wraps {@code HttpClient.send}.</p>
 *
 * <p>Internal (ADR-018): the SDK does not expose a public transport SPI. Callers who need to
 * swap the underlying HTTP client supply their own via
 * {@code FanarClient.Builder.httpClient(...)}.</p>
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * Execute {@code request} and return the full response.
     *
     * <p>Implementations translate checked IO/interrupt failures into
     * {@code qa.fanar.core.FanarTransportException} so callers never see checked exceptions.</p>
     */
    HttpResponse<InputStream> send(HttpRequest request);
}
