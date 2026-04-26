package qa.fanar.e2e.poems;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qa.fanar.core.FanarClient;
import qa.fanar.core.poems.PoemGenerationRequest;
import qa.fanar.core.poems.PoemGenerationResponse;
import qa.fanar.core.poems.PoemModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live battle-test of {@code POST /v1/poems/generations} via {@link FanarClient#poems()},
 * parameterized over both codec adapters.
 *
 * <p>Per the Fanar spec this endpoint requires additional authorization. {@code Fanar-Diwan}
 * also did not appear in the live {@code /v1/models} listing for our SDK key as of 2026-04-25,
 * so this test may surface a {@code FanarAuthorizationException} (HTTP 403). Share the wire
 * log when that happens. Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LivePoemsTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.5 generate returns non-empty poem text (or surfaces a typed access error)")
    void generate_returnsNonEmptyPoem(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            PoemGenerationResponse r = client.poems().generate(
                PoemGenerationRequest.of(PoemModel.FANAR_DIWAN,
                    "Write a poem about the sea"));

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.poem(), "poem text must be present");
            assertFalse(r.poem().isBlank(), "poem text must not be blank");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.5 generateAsync().get() completes against live infra with non-blank poem")
    void generate_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            PoemGenerationResponse r = client.poems().generateAsync(
                    PoemGenerationRequest.of(PoemModel.FANAR_DIWAN,
                            "Write a poem about the sea"))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.poem(), "poem text must be present");
            assertFalse(r.poem().isBlank(), "poem text must not be blank");
        }
    }
}
