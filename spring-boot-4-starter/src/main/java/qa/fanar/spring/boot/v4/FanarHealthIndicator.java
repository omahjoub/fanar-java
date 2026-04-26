package qa.fanar.spring.boot.v4;

import java.util.Objects;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarException;
import qa.fanar.core.models.ModelsResponse;

/**
 * Spring Boot Actuator health indicator backed by {@code GET /v1/models}. Reports {@code UP} when
 * the call succeeds and {@code DOWN} when any {@link FanarException} is thrown — including auth
 * failures, transport errors, and 5xx responses, so a misconfigured API key or a Fanar outage
 * surfaces in {@code /actuator/health} immediately.
 *
 * <p>{@code /v1/models} is the cheapest authenticated endpoint Fanar exposes: it exercises DNS,
 * TLS, and the API key without spending tokens or hitting a model. The response carries the
 * server-side request id and the count of available models, both of which are reported as
 * actuator details for diagnostics.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarHealthIndicator extends AbstractHealthIndicator {

    private final FanarClient fanar;

    public FanarHealthIndicator(FanarClient fanar) {
        this.fanar = Objects.requireNonNull(fanar, "fanar");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            ModelsResponse response = fanar.models().list();
            builder.up()
                    .withDetail("models", response.models().size())
                    .withDetail("requestId", response.id());
        } catch (FanarException e) {
            builder.down(e)
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("httpStatus", e.httpStatus());
        }
    }
}
