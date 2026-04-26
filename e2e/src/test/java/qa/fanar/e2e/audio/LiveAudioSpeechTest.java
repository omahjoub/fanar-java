package qa.fanar.e2e.audio;

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
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.LiveOutputs;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code POST /v1/audio/speech} via {@link FanarClient#audio()}, parameterized
 * over both codec adapters.
 *
 * <p>Asserts the synthesised audio bytes have a recognisable container prefix (MP3 frame sync
 * marker {@code 0xFF Fx} for mp3, or {@code "RIFF"} ASCII for wav). No silent catches per the
 * fail-loudly preference — auth/timeout errors surface with the wire log showing the server
 * response.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveAudioSpeechTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7b speech (default mp3) returns non-empty audio bytes with MP3 frame sync")
    void speech_returnsMp3AudioBytes(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] audio = client.audio().speech(TextToSpeechRequest.of(
                    TtsModel.FANAR_AURA_TTS_2, "يدعُونَ عنترَ والرّماحُ كأنّها", Voice.HAMAD));

            assertNotNull(audio, "audio bytes must be present");
            assertTrue(audio.length > 0, "audio must be non-empty");
            // MP3 frame sync: byte[0] == 0xFF and byte[1] high nibble == 0xF
            assertTrue((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xF0) == 0xF0,
                    "expected MP3 frame sync 0xFF Fx, got "
                            + String.format("0x%02x 0x%02x", audio[0] & 0xFF, audio[1] & 0xFF));

            LiveOutputs.write("audio-output", "speech-harry-mp3", "mp3", audio);
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7b speech with response_format=wav returns RIFF/WAVE bytes")
    void speech_returnsWavAudioBytes(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] audio = client.audio().speech(new TextToSpeechRequest(
                    TtsModel.FANAR_AURA_TTS_2, "نحن بنات طارق نمشي على النمارق",
                    Voice.HUDA, TtsResponseFormat.WAV, null));

            assertNotNull(audio);
            assertTrue(audio.length > 12, "WAV minimum header is 12 bytes, got " + audio.length);
            assertTrue(audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F',
                    "expected RIFF prefix, got "
                            + new String(audio, 0, Math.min(4, audio.length)));
            assertTrue(audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E',
                    "expected WAVE marker at byte 8");

            LiveOutputs.write("audio-output", "speech-harry-wav", "wav", audio);
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7b speechAsync().get() completes against live infra with MP3 audio bytes")
    void speech_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] audio = client.audio().speechAsync(TextToSpeechRequest.of(
                    TtsModel.FANAR_AURA_TTS_2, "يدعُونَ عنترَ والرّماحُ كأنّها", Voice.HAMAD))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(audio, "audio bytes must be present");
            assertTrue(audio.length > 0, "audio must be non-empty");
            assertTrue((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xF0) == 0xF0,
                    "expected MP3 frame sync 0xFF Fx, got "
                            + String.format("0x%02x 0x%02x", audio[0] & 0xFF, audio[1] & 0xFF));
        }
    }
}
