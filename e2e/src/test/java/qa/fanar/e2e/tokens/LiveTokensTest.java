package qa.fanar.e2e.tokens;

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
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.tokens.TokenizationRequest;
import qa.fanar.core.tokens.TokenizationResponse;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code POST /v1/tokens} via {@link FanarClient#tokens()}, parameterized
 * over both codec adapters.
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveTokensTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.2 count returns positive token count and a per-request budget")
    void count_returnsPositiveTokenCount(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            TokenizationResponse r = client.tokens().count(
                    TokenizationRequest.of("Hello, how are you?", ChatModel.FANAR_S_1_7B));

            assertNotNull(r.id(), "response id must be present");
            assertTrue(r.tokens() > 0, "expected at least one token, got " + r.tokens());
            assertTrue(r.maxRequestTokens() > 0,
                    "max_request_tokens must be positive, got " + r.maxRequestTokens());
            System.out.println("Live /v1/tokens: tokens=" + r.tokens()
                    + " maxRequestTokens=" + r.maxRequestTokens());
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.2 countAsync().get() completes against live infra with the same shape")
    void count_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            TokenizationResponse r = client.tokens().countAsync(
                    TokenizationRequest.of("Hello, how are you?", ChatModel.FANAR_S_1_7B))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(r.id(), "response id must be present");
            assertTrue(r.tokens() > 0, "expected at least one token, got " + r.tokens());
        }
    }
}
