package qa.fanar.e2e.jackson2;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.*;
import qa.fanar.e2e.shared.Probes;
import qa.fanar.e2e.shared.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First live smoke test: verify that {@code fanar-core} + {@code fanar-json-jackson2} can
 * successfully round-trip a {@code chat().send(...)} against the real Fanar API.
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class Jackson2ChatSmokeTest {

    @Test
    void pingRoundTrip() {
        try (FanarClient client = TestClients.liveWithLogging(new Jackson2FanarJsonCodec())) {
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
