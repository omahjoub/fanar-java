package qa.fanar.spring.boot.v4;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import qa.fanar.interceptor.logging.WireLoggingInterceptor;

/**
 * Typed mapping of the {@code fanar.*} configuration namespace. Bound by Spring Boot at
 * startup and consumed by {@link FanarAutoConfiguration} to build the {@link
 * qa.fanar.core.FanarClient} bean.
 *
 * <p>Defaults mirror the SDK's own defaults so that an application supplying only
 * {@code fanar.api-key} ends up with the same client a hand-rolled
 * {@code FanarClient.builder().apiKey(...).build()} would produce.</p>
 *
 * @param apiKey         the Fanar API key — required; if absent the auto-configuration
 *                       skips bean creation entirely
 * @param baseUrl        the Fanar API base URL — defaults to {@code https://api.fanar.qa}
 * @param connectTimeout HTTP connect timeout — defaults to 10 seconds
 * @param requestTimeout HTTP request timeout — defaults to 60 seconds
 * @param retry          retry policy knobs (see {@link Retry})
 * @param wireLogging    wire-logging interceptor knobs (see {@link WireLogging})
 *
 * @author Oussama Mahjoub
 */
@ConfigurationProperties("fanar")
public record FanarProperties(
        String apiKey,
        @DefaultValue("https://api.fanar.qa") URI baseUrl,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue Retry retry,
        @DefaultValue WireLogging wireLogging
) {

    /**
     * Retry policy knobs. Maps to {@link qa.fanar.core.RetryPolicy}.
     *
     * @param maxAttempts    maximum number of attempts including the first — defaults to 3
     * @param initialBackoff base delay before the second attempt — defaults to 100ms,
     *                       grown by the SDK's default backoff multiplier per attempt
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("100ms") Duration initialBackoff
    ) { }

    /**
     * Wire-logging knobs. When {@link #level()} is anything other than
     * {@link WireLoggingInterceptor.Level#NONE}, the auto-configuration adds a
     * {@link WireLoggingInterceptor} to the client at the configured level. Default {@code NONE}.
     *
     * @param level the wire-logging verbosity level
     */
    public record WireLogging(
            @DefaultValue("NONE") WireLoggingInterceptor.Level level
    ) { }
}
