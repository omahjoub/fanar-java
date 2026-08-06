package qa.fanar.e2e.poems;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarUnprocessableException;
import qa.fanar.core.poems.PoemGenerationRequest;
import qa.fanar.core.poems.PoemGenerationResponse;
import qa.fanar.core.poems.PoemModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live battle-test of {@code POST /v1/poems/generations} via {@link FanarClient#poems()},
 * parameterized over both codec adapters.
 *
 * <p><strong>Diwan history for our SDK key.</strong> As of 2026-04-25 the model was gated
 * (absent from {@code /v1/models}, calls surfacing 403/504). As of 2026-08-06 generation
 * <em>works</em> — but the endpoint is nondeterministic: within one run, identical requests
 * alternated between 200 (a full poem) and 422 {@code unprocessable} / "No suitable verses
 * found for the given prompt" (observed 2 of 4 calls). Diwan composes from a verse corpus and
 * sometimes reports a retrieval miss for a prompt it served moments earlier.</p>
 *
 * <p>Each test therefore retries <em>only</em> {@link FanarUnprocessableException} up to
 * {@link #VERSE_MATCH_ATTEMPTS} times. This is a deliberate, narrowly-scoped exception to the
 * fail-loudly rule: a documented nondeterministic <em>semantic</em> outcome is retried, while
 * authorization / timeout / transport errors still fail on the first occurrence, and a
 * persistent verse-miss still fails with the typed exception preserved. Share the wire log
 * when that happens. Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LivePoemsTest {

    private static final PoemGenerationRequest SEA_POEM = PoemGenerationRequest.of(
            PoemModel.FANAR_DIWAN, "Write a poem about the sea");

    /** Verse-miss retries per test; Diwan is 50/min so the extra budget is negligible. */
    private static final int VERSE_MATCH_ATTEMPTS = 3;

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.5 generate returns non-empty poem text (verse-miss 422 retried, see Javadoc)")
    void generate_returnsNonEmptyPoem(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            PoemGenerationResponse r = generateRetryingVerseMisses(client);

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
            PoemGenerationResponse r = generateAsyncRetryingVerseMisses(client);

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.poem(), "poem text must be present");
            assertFalse(r.poem().isBlank(), "poem text must not be blank");
        }
    }

    private static PoemGenerationResponse generateRetryingVerseMisses(FanarClient client) {
        FanarUnprocessableException lastMiss = null;
        for (int attempt = 1; attempt <= VERSE_MATCH_ATTEMPTS; attempt++) {
            try {
                return client.poems().generate(SEA_POEM);
            } catch (FanarUnprocessableException e) {
                lastMiss = e; // nondeterministic verse miss — retry; anything else propagates
            }
        }
        throw lastMiss;
    }

    private static PoemGenerationResponse generateAsyncRetryingVerseMisses(FanarClient client)
            throws Exception {
        FanarUnprocessableException lastMiss = null;
        for (int attempt = 1; attempt <= VERSE_MATCH_ATTEMPTS; attempt++) {
            try {
                return client.poems().generateAsync(SEA_POEM).get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FanarUnprocessableException miss) {
                    lastMiss = miss; // nondeterministic verse miss — retry
                } else {
                    throw e;
                }
            }
        }
        throw lastMiss;
    }
}
