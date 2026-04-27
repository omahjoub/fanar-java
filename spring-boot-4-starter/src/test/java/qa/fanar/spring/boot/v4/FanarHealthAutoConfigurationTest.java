package qa.fanar.spring.boot.v4;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.springframework.boot.health.contributor.HealthIndicator;

import static org.assertj.core.api.Assertions.assertThat;

class FanarHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FanarAutoConfiguration.class,
                    FanarHealthAutoConfiguration.class));

    @Test
    void healthIndicatorRegisteredWhenClientIsPresent() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FanarHealthIndicator.class);
                    assertThat(ctx).hasBean("fanarHealthIndicator");
                });
    }

    @Test
    void healthIndicatorAbsentWhenDisabledByProperty() {
        runner.withPropertyValues(
                        "fanar.api-key=test-key",
                        "management.health.fanar.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FanarHealthIndicator.class));
    }

    @Test
    void healthIndicatorAbsentWithoutFanarClient() {
        // No api-key → FanarAutoConfiguration is skipped → no FanarClient bean → @ConditionalOnBean
        // gates the indicator off.
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FanarHealthIndicator.class));
    }

    @Test
    void healthIndicatorAbsentWhenSpringBootHealthMissing() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FanarHealthIndicator.class));
    }
}
