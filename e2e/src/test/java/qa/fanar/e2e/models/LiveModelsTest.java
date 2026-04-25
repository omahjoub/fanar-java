package qa.fanar.e2e.models;

import java.util.Set;
import java.util.stream.Collectors;
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
import qa.fanar.core.models.AvailableModel;
import qa.fanar.core.models.ModelsResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code GET /v1/models} via {@link FanarClient#models()}, parameterized
 * over both codec adapters.
 *
 * <p>Asserts that every {@link ChatModel} constant the SDK ships still appears in the live
 * response. If Fanar drops a known model, this test fires before the regression hits any user.
 * Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveModelsTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.1 list returns every ChatModel.KNOWN.wireValue() the SDK ships")
    void list_returnsAllKnownChatModels(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            ModelsResponse r = client.models().list();
            assertNotNull(r.id(), "response id must be present");
            assertFalse(r.models().isEmpty(), "expected at least one model");

            Set<String> wireIds = r.models().stream()
                    .map(AvailableModel::id)
                    .collect(Collectors.toSet());
            System.out.println("Live /v1/models: " + wireIds);

            for (ChatModel known : ChatModel.KNOWN) {
                assertTrue(wireIds.contains(known.wireValue()),
                        "spec drift: SDK ships ChatModel.KNOWN entry \"" + known.wireValue()
                                + "\" but it isn't in /v1/models response. Either Fanar dropped "
                                + "the model or the SDK's catalogue is stale.");
            }
        }
    }
}
