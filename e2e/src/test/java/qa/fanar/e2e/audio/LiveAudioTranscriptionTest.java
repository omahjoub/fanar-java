package qa.fanar.e2e.audio;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.SttFormat;
import qa.fanar.core.audio.SttModel;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TranscriptionRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.LiveOutputs;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code POST /v1/audio/transcriptions} via {@link FanarClient#audio()},
 * parameterized over both codec adapters.
 *
 * <p>All tests transcribe one <em>shared</em> WAV clip, synthesised lazily on first use via
 * {@code .speech(...)} (the M.7b endpoint already battle-tested). Previously every test
 * synthesised its own clip — 8 TTS calls per live run on top of the speech suite — which
 * exhausted {@code Fanar-Aura-TTS-2}'s 20-per-24-hour window (429s on the speech endpoint, not a
 * transcription limit; see {@code docs/WIRE_OBSERVATIONS.md}). The clip's bytes come from the server,
 * not from the codec under test, so sharing it does not weaken the per-codec transcription
 * coverage.</p>
 *
 * <p>No silent catches per the fail-loudly preference — server errors surface verbatim with the
 * wire log.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveAudioTranscriptionTest {

    private static final String ARABIC_PROMPT = "السلام عليكم ورحمة الله وبركاته";

    private static byte[] sharedWav;

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    /** One TTS call per JVM run; the codec choice for synthesis is arbitrary (server produces the bytes). */
    private static synchronized byte[] sourceClip() {
        if (sharedWav == null) {
            try (FanarClient client = TestClients.liveWithLogging(new Jackson3FanarJsonCodec())) {
                sharedWav = client.audio().speech(TextToSpeechRequest.builder()
                        .model(TtsModel.FANAR_AURA_TTS_2)
                        .input(ARABIC_PROMPT)
                        .voice(Voice.HUDA)
                        .responseFormat(TtsResponseFormat.WAV)
                        .build());
                LiveOutputs.write("audio-output", "stt-source-shared", "wav", sharedWav);
            }
        }
        return sharedWav;
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=text) returns Text variant with non-empty body")
    void transcribe_textVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    sourceClip(), "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT));

            SpeechToTextResponse.Text text = assertInstanceOf(SpeechToTextResponse.Text.class, response,
                    "format=text must produce a Text variant");
            assertNotNull(text.id(), "id must be present");
            assertFalse(text.text().isBlank(), "transcribed text must not be blank");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=srt, long-form model) returns Srt variant")
    void transcribe_srtVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    sourceClip(), "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_LF_1, SttFormat.SRT));

            SpeechToTextResponse.Srt srt = assertInstanceOf(SpeechToTextResponse.Srt.class, response,
                    "format=srt must produce an Srt variant");
            assertNotNull(srt.id());
            assertFalse(srt.srt().isBlank(), "srt body must not be blank");
            assertTrue(srt.srt().contains("-->"),
                    "SRT format includes timing arrows; got: " + srt.srt());
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=json, long-form model) returns Json variant with segments")
    void transcribe_jsonVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    sourceClip(), "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_LF_1, SttFormat.JSON));

            SpeechToTextResponse.Json json = assertInstanceOf(SpeechToTextResponse.Json.class, response,
                    "format=json must produce a Json variant");
            assertNotNull(json.id());
            assertFalse(json.segments().isEmpty(),
                    "json variant must have at least one segment");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe with default format → Text variant (server default)")
    void transcribe_defaultFormat(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            SpeechToTextResponse response = client.audio().transcribe(TranscriptionRequest.of(
                    sourceClip(), "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_1));

            assertInstanceOf(SpeechToTextResponse.Text.class, response,
                    "server default for format is text");
        }
    }
}
