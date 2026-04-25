package qa.fanar.e2e.audio;

import java.nio.file.Path;
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
 * <p>Each test synthesises a short WAV clip via {@link FanarClient#audio()} {@code .speech(...)}
 * (the M.7b endpoint already battle-tested) and immediately transcribes that clip — a tight
 * round-trip that proves the entire audio pipeline end-to-end.</p>
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

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=text) returns Text variant with non-empty body")
    void transcribe_textVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] wav = client.audio().speech(new TextToSpeechRequest(
                    TtsModel.FANAR_AURA_TTS_2, ARABIC_PROMPT, Voice.HUDA, TtsResponseFormat.WAV, null));
            Path src = LiveOutputs.write("audio-output", "stt-source-text", "wav", wav);
            System.out.println("STT source clip: " + wav.length + " bytes → " + src);

            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    wav, "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT));

            SpeechToTextResponse.Text text = assertInstanceOf(SpeechToTextResponse.Text.class, response,
                    "format=text must produce a Text variant");
            assertNotNull(text.id(), "id must be present");
            assertFalse(text.text().isBlank(), "transcribed text must not be blank");
            System.out.println("Live /v1/audio/transcriptions (text): id=" + text.id()
                    + " → \"" + text.text() + "\"");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=srt, long-form model) returns Srt variant")
    void transcribe_srtVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] wav = client.audio().speech(new TextToSpeechRequest(
                    TtsModel.FANAR_AURA_TTS_2, ARABIC_PROMPT, Voice.HUDA, TtsResponseFormat.WAV, null));

            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    wav, "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_LF_1, SttFormat.SRT));

            SpeechToTextResponse.Srt srt = assertInstanceOf(SpeechToTextResponse.Srt.class, response,
                    "format=srt must produce an Srt variant");
            assertNotNull(srt.id());
            assertFalse(srt.srt().isBlank(), "srt body must not be blank");
            assertTrue(srt.srt().contains("-->"),
                    "SRT format includes timing arrows; got: " + srt.srt());
            System.out.println("Live /v1/audio/transcriptions (srt): id=" + srt.id()
                    + ", " + srt.srt().length() + " chars");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe (format=json, long-form model) returns Json variant with segments")
    void transcribe_jsonVariant(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] wav = client.audio().speech(new TextToSpeechRequest(
                    TtsModel.FANAR_AURA_TTS_2, ARABIC_PROMPT, Voice.HUDA, TtsResponseFormat.WAV, null));

            SpeechToTextResponse response = client.audio().transcribe(new TranscriptionRequest(
                    wav, "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_LF_1, SttFormat.JSON));

            SpeechToTextResponse.Json json = assertInstanceOf(SpeechToTextResponse.Json.class, response,
                    "format=json must produce a Json variant");
            assertNotNull(json.id());
            assertFalse(json.segments().isEmpty(),
                    "json variant must have at least one segment");
            System.out.println("Live /v1/audio/transcriptions (json): id=" + json.id()
                    + ", segments=" + json.segments().size()
                    + ", first=\"" + json.segments().getFirst().text() + "\"");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7c transcribe with default format → Text variant (server default)")
    void transcribe_defaultFormat(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] wav = client.audio().speech(new TextToSpeechRequest(
                    TtsModel.FANAR_AURA_TTS_2, ARABIC_PROMPT, Voice.HUDA, TtsResponseFormat.WAV, null));

            SpeechToTextResponse response = client.audio().transcribe(TranscriptionRequest.of(
                    wav, "input.wav", "audio/wav", SttModel.FANAR_AURA_STT_1));

            assertInstanceOf(SpeechToTextResponse.Text.class, response,
                    "server default for format is text");
        }
    }
}
