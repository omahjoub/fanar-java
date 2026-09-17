package qa.fanar.e2e.chat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarUnprocessableException;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.Probes;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the model gate on {@code Fanar-Agentic} (ADR-030). Observed 2026-09-15: the chat endpoint
 * accepts the id in its own validation enum but answers HTTP <strong>422</strong>
 * {@code unprocessable} / "Model not authorized" for the standard key, with or without a
 * {@code tools} array, so whether the agentic variants honour user-supplied tools is untestable
 * here — and that is the one finding that would reshape the ADK adapter's scope.
 *
 * <p>This test asserts the gate itself, so it goes <em>red</em> the day the key is granted: the
 * signal to send a tools payload by hand, look at {@code result} on the returned
 * {@code tool_calls}, and reopen ADR-021, ADR-024 and ADR-030 in that order
 * ({@code PROJECT_STATE.md}, "When the upgraded key arrives"). Unlike the silent-drop probe the
 * ledger declined to retain, a gate assertion pins something a change would break. One call per
 * codec; no rate-limit budget beyond the chat window.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveAgenticGateTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("Fanar-Agentic is gated: 422 'Model not authorized' for the standard key (goes red when granted)")
    void agenticModelIsStillGated(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            FanarUnprocessableException ex = assertThrows(FanarUnprocessableException.class,
                    () -> client.chat().send(Probes.pingFor(ChatModel.of("Fanar-Agentic"))),
                    "the gate lifted: probe tools by hand and reopen ADR-021 / ADR-024 / ADR-030");
            assertEquals(422, ex.httpStatus());
        }
    }
}
