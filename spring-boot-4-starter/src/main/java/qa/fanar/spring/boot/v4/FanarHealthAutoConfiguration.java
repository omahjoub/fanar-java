package qa.fanar.spring.boot.v4;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

import qa.fanar.core.FanarClient;

/**
 * Registers a {@link FanarHealthIndicator} when {@code spring-boot-health} is on the classpath
 * and a {@link FanarClient} bean is available. Activates after {@link FanarAutoConfiguration} so
 * the auto-configured client is in scope.
 *
 * <p>Disable with {@code management.health.fanar.enabled=false}.</p>
 *
 * @author Oussama Mahjoub
 */
@AutoConfiguration(after = FanarAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(FanarClient.class)
@ConditionalOnProperty(prefix = "management.health.fanar", name = "enabled", matchIfMissing = true)
public class FanarHealthAutoConfiguration {

    @Bean("fanarHealthIndicator")
    @ConditionalOnMissingBean(name = "fanarHealthIndicator")
    FanarHealthIndicator fanarHealthIndicator(FanarClient fanar) {
        return new FanarHealthIndicator(fanar);
    }
}
