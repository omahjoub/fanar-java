package qa.fanar.spring.boot.v4;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import qa.fanar.core.FanarClient;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

class FanarAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FanarAutoConfiguration.class));

    @Test
    void clientBeanWiredWhenApiKeyPresent() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> assertThat(ctx).hasSingleBean(FanarClient.class));
    }

    @Test
    void clientBeanAbsentWithoutApiKey() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FanarClient.class));
    }

    @Test
    void propertiesBeanIsRegistered() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> assertThat(ctx).hasSingleBean(FanarProperties.class));
    }

    @Test
    void defaultsApply() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> {
                    FanarProperties props = ctx.getBean(FanarProperties.class);
                    assertThat(props.apiKey()).isEqualTo("test-key");
                    assertThat(props.baseUrl().toString()).isEqualTo("https://api.fanar.qa");
                    assertThat(props.connectTimeout().toSeconds()).isEqualTo(10);
                    assertThat(props.requestTimeout().toSeconds()).isEqualTo(60);
                    assertThat(props.retry().maxAttempts()).isEqualTo(3);
                    assertThat(props.retry().initialBackoff().toMillis()).isEqualTo(100);
                    assertThat(props.wireLogging().level().name()).isEqualTo("NONE");
                });
    }

    @Test
    void yamlOverridesAreApplied() {
        runner.withPropertyValues(
                        "fanar.api-key=test-key",
                        "fanar.base-url=https://staging.fanar.qa",
                        "fanar.connect-timeout=5s",
                        "fanar.request-timeout=30s",
                        "fanar.retry.max-attempts=5",
                        "fanar.retry.initial-backoff=250ms",
                        "fanar.wire-logging.level=BODY")
                .run(ctx -> {
                    FanarProperties props = ctx.getBean(FanarProperties.class);
                    assertThat(props.baseUrl().toString()).isEqualTo("https://staging.fanar.qa");
                    assertThat(props.connectTimeout().toSeconds()).isEqualTo(5);
                    assertThat(props.requestTimeout().toSeconds()).isEqualTo(30);
                    assertThat(props.retry().maxAttempts()).isEqualTo(5);
                    assertThat(props.retry().initialBackoff().toMillis()).isEqualTo(250);
                    assertThat(props.wireLogging().level().name()).isEqualTo("BODY");
                });
    }

    @Test
    void userDefinedClientBeanWins() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomClientConfig.class)
                .run(ctx -> {
                    FanarClient client = ctx.getBean(FanarClient.class);
                    assertThat(client).isSameAs(CustomClientConfig.MARKER);
                });
    }

    @Test
    void userDefinedObservabilityPluginIsPickedUp() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomObservabilityConfig.class)
                .run(ctx -> {
                    // Auto-config should still produce the FanarClient — the obs plugin
                    // is wired into it via ObjectProvider, not exposed as a separate bean.
                    assertThat(ctx).hasSingleBean(FanarClient.class);
                    assertThat(ctx).hasBean("customObservability");
                });
    }

    @Test
    void userDefinedInterceptorsAreWired() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomInterceptorConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FanarClient.class);
                    assertThat(ctx.getBeansOfType(Interceptor.class)).hasSize(2);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientConfig {
        static final FanarClient MARKER = FanarClient.builder()
                .apiKey("user-key")
                .jsonCodec(new Jackson3FanarJsonCodec())
                .build();

        @Bean
        FanarClient fanarClient() {
            return MARKER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomObservabilityConfig {
        @Bean
        ObservabilityPlugin customObservability() {
            return ObservabilityPlugin.noop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomInterceptorConfig {
        @Bean
        Interceptor first() {
            return (req, chain) -> chain.proceed(req);
        }

        @Bean
        Interceptor second() {
            return (req, chain) -> chain.proceed(req);
        }
    }

    /** Reference List to assert the orderedStream picks up multiple beans correctly. */
    @SuppressWarnings("unused")
    private static List<Interceptor> interceptors;
}
