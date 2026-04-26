package qa.fanar.core.internal.transport;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.function.Supplier;

import qa.fanar.core.spi.Interceptor;

/**
 * Built-in interceptor that injects {@code Authorization: Bearer &lt;token&gt;} into every
 * outbound request.
 *
 * <p>The token is resolved via a {@link Supplier} on each call, which supports short-lived or
 * rotating credentials — the interceptor re-reads the supplier for every request. Registered
 * outermost in the chain so the token is refreshed on every retry attempt (ADR-012).</p>
 *
 * <p>Internal (ADR-018). Callers enable this indirectly via
 * {@code FanarClient.Builder.apiKey(...)}.</p>
 *
 * @author Oussama Mahjoub
 */
public final class BearerTokenInterceptor implements Interceptor {

    private final Supplier<String> tokenSupplier;

    public BearerTokenInterceptor(Supplier<String> tokenSupplier) {
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    @Override
    public HttpResponse<InputStream> intercept(HttpRequest request, Chain chain) {
        String token = tokenSupplier.get();
        HttpRequest authenticated = HttpRequest.newBuilder(request, (n, v) -> true)
                .header("Authorization", "Bearer " + token)
                .build();
        return chain.proceed(authenticated);
    }
}
