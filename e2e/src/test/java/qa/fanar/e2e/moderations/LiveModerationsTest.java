package qa.fanar.e2e.moderations;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.moderations.ModerationModel;
import qa.fanar.core.moderations.SafetyFilterRequest;
import qa.fanar.core.moderations.SafetyFilterResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code POST /v1/moderations} via {@link FanarClient#moderations()},
 * parameterized over both codec adapters.
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveModerationsTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.3 score returns finite safety + cultural-awareness scores")
    void score_returnsFiniteScores(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SafetyFilterResponse r = client.moderations().score(
                    SafetyFilterRequest.of(
                            ModerationModel.FANAR_GUARD_2,
                            "What is the weather?",
                            "The weather is sunny today."));

            // Score range isn't pinned by the spec; we only assert finiteness so this test
            // doesn't break if Fanar shifts the scale.
            assertTrue(Double.isFinite(r.safety()),
                    "safety score must be finite, got " + r.safety());
            assertTrue(Double.isFinite(r.culturalAwareness()),
                    "cultural awareness score must be finite, got " + r.culturalAwareness());
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.3 scoreAsync().get() completes against live infra with finite scores")
    void score_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SafetyFilterResponse r = client.moderations().scoreAsync(
                    SafetyFilterRequest.of(
                            ModerationModel.FANAR_GUARD_2,
                            "What is the weather?",
                            "The weather is sunny today."))
                    .get(60, TimeUnit.SECONDS);
            assertTrue(Double.isFinite(r.safety()),
                    "safety score must be finite, got " + r.safety());
            assertTrue(Double.isFinite(r.culturalAwareness()),
                    "cultural awareness score must be finite, got " + r.culturalAwareness());
        }
    }
}
