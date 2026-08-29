package qa.fanar.spring.boot.v4;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
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
                    assertThat(props.retry().initialBackoff().toMillis()).isEqualTo(500);
                    assertThat(props.retry().maxDelay().toSeconds()).isEqualTo(30);
                    assertThat(props.retry().maxTotalDelay()).isEqualTo(Duration.ofMinutes(1));
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
                        "fanar.retry.max-delay=45s",
                        "fanar.retry.max-total-delay=5m",
                        "fanar.wire-logging.level=BODY")
                .run(ctx -> {
                    FanarProperties props = ctx.getBean(FanarProperties.class);
                    assertThat(props.baseUrl().toString()).isEqualTo("https://staging.fanar.qa");
                    assertThat(props.connectTimeout().toSeconds()).isEqualTo(5);
                    assertThat(props.requestTimeout().toSeconds()).isEqualTo(30);
                    assertThat(props.retry().maxAttempts()).isEqualTo(5);
                    assertThat(props.retry().initialBackoff().toMillis()).isEqualTo(250);
                    assertThat(props.retry().maxDelay().toSeconds()).isEqualTo(45);
                    assertThat(props.retry().maxTotalDelay()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(props.wireLogging().level().name()).isEqualTo("BODY");
                });
    }

    @Test
    void defaultRetryPolicyBeanMirrorsTheSdkDefaults() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> assertThat(ctx.getBean(RetryPolicy.class)).isEqualTo(RetryPolicy.defaults()));
    }

    @Test
    void retryPolicyBeanReflectsTheRetryKnobs() {
        // initial-backoff above the SDK's default cap used to fail at startup; the four knobs are
        // now validated together (a max-delay of 90 s also needs max-total-delay >= 90 s).
        runner.withPropertyValues(
                        "fanar.api-key=test-key",
                        "fanar.retry.max-attempts=5",
                        "fanar.retry.initial-backoff=45s",
                        "fanar.retry.max-delay=90s",
                        "fanar.retry.max-total-delay=10m")
                .run(ctx -> {
                    RetryPolicy policy = ctx.getBean(RetryPolicy.class);
                    assertThat(policy.maxAttempts()).isEqualTo(5);
                    assertThat(policy.baseDelay()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(policy.maxDelay()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(policy.maxTotalDelay()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(policy.jitter()).isEqualTo(RetryPolicy.defaults().jitter());
                    assertThat(policy.backoffMultiplier()).isEqualTo(RetryPolicy.defaults().backoffMultiplier());
                });
    }

    @Test
    void retryKnobsAreValidatedTogetherAtStartup() {
        // max-delay raised above the default 1 m budget without raising max-total-delay: the
        // policy's own validation fails the context, loudly, instead of a silent mis-configuration.
        runner.withPropertyValues(
                        "fanar.api-key=test-key",
                        "fanar.retry.max-delay=2m")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("maxTotalDelay");
                });
    }

    @Test
    void userDefinedRetryPolicyBeanWins() {
        runner.withPropertyValues("fanar.api-key=test-key", "fanar.retry.max-attempts=7")
                .withUserConfiguration(CustomRetryPolicyConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FanarClient.class);
                    assertThat(ctx.getBean(RetryPolicy.class)).isSameAs(CustomRetryPolicyConfig.MARKER);
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
    static class CustomRetryPolicyConfig {
        static final RetryPolicy MARKER = RetryPolicy.disabled();

        @Bean
        RetryPolicy fanarRetryPolicy() {
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
}
