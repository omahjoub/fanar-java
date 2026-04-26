package qa.fanar.e2e.audio;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.audio.AudioClient;
import qa.fanar.core.audio.CreateVoiceRequest;
import qa.fanar.core.audio.VoiceResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of the voice CRUD operations on {@link FanarClient#audio()}, parameterized
 * over both codec adapters: one test per method.
 *
 * <p>Tests fail loudly on upstream issues — there's no try/catch around the calls, so any
 * authorization, timeout, or rejection error surfaces as a test failure with the typed
 * exception preserved. The wire log (via {@code LoggingInterceptor}) shows the request/response
 * for diagnosis.</p>
 *
 * <p>{@code createVoice} uses a synthetic 1-second silent WAV from {@link SilenceWav}; Fanar
 * may reject silent audio at the model layer (voice cloning needs real speech), in which case
 * the test fails with the actual server response visible in the log — that's the desired
 * diagnostic signal, not a flake.</p>
 *
 * <p>Voice names are UUID-prefixed so concurrent runs don't collide. Skipped when
 * {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveAudioVoicesTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7a listVoices returns the (possibly empty) personalized voice list")
    void listVoices(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            VoiceResponse r = client.audio().listVoices();
            assertNotNull(r.voices(), "voices list must be present (may be empty)");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7a createVoice uploads a sample and the new voice appears in the list")
    void createVoice(FanarJsonCodec codec) {
        String voiceName = "fanar-java-e2e-" + UUID.randomUUID().toString().substring(0, 8);

        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            AudioClient audio = client.audio();
            audio.createVoice(new CreateVoiceRequest(
                    voiceName, SilenceWav.bytes(), "this is a test voice sample"));

            try {
                VoiceResponse afterCreate = audio.listVoices();
                assertTrue(afterCreate.voices().contains(voiceName),
                        "voice " + voiceName + " not in list after create: "
                                + afterCreate.voices());
            } finally {
                // Best-effort cleanup so a failed assertion above doesn't leak voices.
                audio.deleteVoice(voiceName);
            }
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7a deleteVoice removes a previously-created voice")
    void deleteVoice(FanarJsonCodec codec) {
        String voiceName = "fanar-java-e2e-" + UUID.randomUUID().toString().substring(0, 8);

        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            AudioClient audio = client.audio();
            // Setup: create the voice so we have something to delete.
            audio.createVoice(new CreateVoiceRequest(
                    voiceName, SilenceWav.bytes(), "this is a test voice sample"));

            // Act: delete it.
            audio.deleteVoice(voiceName);

            // Assert: voice is no longer in the list.
            VoiceResponse afterDelete = audio.listVoices();
            assertFalse(afterDelete.voices().contains(voiceName),
                    "voice " + voiceName + " still present after delete: "
                            + afterDelete.voices());
        }
    }
}
