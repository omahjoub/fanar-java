package qa.fanar.sample.spring.boot.v4;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import qa.fanar.core.FanarClient;
import qa.fanar.spring.boot.v4.FanarHealthIndicator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: boot the full sample context with a stub API key and confirm the starter wires
 * what the README promises — a {@link FanarClient} bean and a {@link FanarHealthIndicator}
 * contributor.
 *
 * <p>The dummy {@code fanar.api-key} property short-circuits the {@code FANAR_API_KEY}
 * environment variable the YAML normally reads, so the context boots even on a developer
 * machine without credentials. No network calls happen — the controller and health indicator
 * just instantiate, they don't run.</p>
 */
@SpringBootTest(properties = "fanar.api-key=test-key")
class FanarSampleApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextBootsAndStarterBeansArePresent() {
        assertThat(context.getBeansOfType(FanarClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(FanarHealthIndicator.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatController.class)).hasSize(1);
    }
}
