package qa.fanar.e2e.jackson3;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.TextContent;
import qa.fanar.e2e.shared.Probes;
import qa.fanar.e2e.shared.TestClients;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First live smoke test: verify that {@code fanar-core} + {@code fanar-json-jackson3} can
 * successfully round-trip a {@code chat().send(...)} against the real Fanar API.
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class Jackson3ChatSmokeTest {

    @Test
    void pingRoundTrip() {
        try (FanarClient client = TestClients.liveWithLogging(new Jackson3FanarJsonCodec())) {
            ChatResponse response = client.chat().send(Probes.ping());

            assertNotNull(response.id(), "response id must be present");
            assertNotNull(response.model(), "response model must be present");
            assertFalse(response.choices().isEmpty(), "at least one choice expected");

            ChatChoice choice = response.choices().getFirst();
            assertEquals(FinishReason.STOP, choice.finishReason(),
                    "short greedy completion should finish on a stop token");
            assertFalse(choice.message().content().isEmpty(),
                    "assistant message must carry at least one content part");

            ResponseContent first = choice.message().content().getFirst();
            if (first instanceof TextContent(String text)) {
                assertFalse(text.isBlank(), "assistant text must not be blank");
            }
        }
    }
}
