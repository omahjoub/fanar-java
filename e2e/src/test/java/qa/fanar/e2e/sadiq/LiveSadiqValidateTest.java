package qa.fanar.e2e.sadiq;

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
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live battle-test of {@code POST /v1/sadiq/validate} via {@link FanarClient#sadiq()}, parameterized
 * over both codec adapters.
 *
 * <p><strong>Gated — observed 2026-09-15.</strong> With the standard key the endpoint answers
 * <strong>HTTP 403</strong>, envelope {@code code: "invalid_authorization"} /
 * {@code "Invalid authorization"}, so these cases fail with {@link
 * qa.fanar.core.FanarAuthorizationException} until the key is upgraded. That failure is the desired
 * diagnostic signal, not a flake. Note this is the <em>endpoint</em> gate, a different mechanism
 * from the <em>model</em> gate that the same {@code Fanar-Sadiq-2} answers with <strong>422</strong>
 * {@code unprocessable} / "Model not authorized" on chat — the endpoint check runs first and
 * short-circuits, so a granted endpoint authorization does not imply a granted model one. The
 * request body is accepted up to the authorization check, so a 403 here is not a wire-format
 * problem. Both routings are proved against a scripted server by
 * {@code FanarClientSadiqValidationIntegrationTest}; see WIRE_OBSERVATIONS, "Sadiq validation".</p>
 *
 * <p>Budget: 4 calls per full run on {@code Fanar-Sadiq-2} (50/min — not one of the scarce
 * trailing-24 h models). While the gate holds they are rejected before admission and carry no
 * rate-limit headers, so they consume none of that budget either (observed 2026-09-15).</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveSadiqValidateTest {

    /** A partially-quoted verse (Q29:45) plus a well-known hadith — both should verify. */
    private static final String PROBE =
            "قال الله تعالى: إن الصلاة تنهى عن الفحشاء والمنكر. "
                    + "وقال النبي صلى الله عليه وسلم: إنما الأعمال بالنيات.";

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§S.1 validate tags verified Qur'an and hadith (gated — fails until the key is upgraded)")
    void validate_tagsVerifiedQuotations(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SadiqValidationResponse r = client.sadiq().validate(
                    SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, PROBE));

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.text(), "validated text must be present");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§S.1 validateAsync().get() completes against live infra with the same shape")
    void validate_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SadiqValidationResponse r = client.sadiq()
                    .validateAsync(SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, PROBE))
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.text(), "validated text must be present");
        }
    }
}
