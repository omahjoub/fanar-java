package qa.fanar.spring.boot.v4;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.interceptor.logging.WireLoggingInterceptor;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

/**
 * Spring Boot 4 auto-configuration for the Fanar Java SDK.
 *
 * <p>Activates when {@link FanarClient} is on the classpath and {@code fanar.api-key} is set in
 * the application's environment. Produces a single {@link FanarClient} bean, configured from
 * {@link FanarProperties}, that the application can {@code @Autowire}. User-defined
 * {@code FanarJsonCodec}, {@code ObservabilityPlugin}, and {@code Interceptor} beans are picked
 * up automatically via {@link ObjectProvider} resolution — no extra wiring needed.</p>
 *
 * <p>If the user supplies their own {@code FanarClient} bean, this auto-configuration is skipped
 * (via {@link ConditionalOnMissingBean}).</p>
 *
 * @author Oussama Mahjoub
 */
@AutoConfiguration
@ConditionalOnClass(FanarClient.class)
@EnableConfigurationProperties(FanarProperties.class)
@ConditionalOnProperty(prefix = "fanar", name = "api-key")
public class FanarAutoConfiguration {

    /**
     * Default JSON codec — Jackson 3, matching what Spring Boot 4 ships. Replaced if the user
     * declares their own {@link FanarJsonCodec} bean. Provided explicitly (instead of relying on
     * the SDK's {@code ServiceLoader} discovery) so AOT processing and module-path classloaders
     * always resolve a codec without crossing the JPMS provider boundary.
     */
    @Bean
    @ConditionalOnMissingBean
    FanarJsonCodec fanarJsonCodec() {
        return new Jackson3FanarJsonCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    FanarClient fanarClient(
            FanarProperties props,
            FanarJsonCodec codec,
            ObjectProvider<ObservabilityPlugin> observability,
            ObjectProvider<Interceptor> interceptors) {

        FanarClient.Builder builder = FanarClient.builder()
                .apiKey(props.apiKey())
                .baseUrl(props.baseUrl())
                .connectTimeout(props.connectTimeout())
                .requestTimeout(props.requestTimeout())
                .retryPolicy(toRetryPolicy(props.retry()))
                .jsonCodec(codec);

        observability.ifAvailable(builder::observability);
        interceptors.orderedStream().forEach(builder::addInterceptor);

        if (props.wireLogging().level() != WireLoggingInterceptor.Level.NONE) {
            builder.addInterceptor(WireLoggingInterceptor.builder()
                    .level(props.wireLogging().level())
                    .build());
        }
        return builder.build();
    }

    private static RetryPolicy toRetryPolicy(FanarProperties.Retry retry) {
        return RetryPolicy.defaults()
                .withMaxAttempts(retry.maxAttempts())
                .withBaseDelay(retry.initialBackoff());
    }
}
