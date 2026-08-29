package qa.fanar.e2e.audio;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>Budget, observed 2026-08-28 and 2026-08-29: {@code Fanar-Aura-TTS-2} allows 20 requests in any
 * <em>trailing 24 hours</em> — a sliding window ({@code ratelimit-policy: 20;w=86400};
 * {@code x-ratelimit-reset} counts down to the oldest counted request ageing out), not a per-minute
 * window and not a calendar day. A full e2e run spends 11 of them (the five cases here × 2 codecs,
 * plus one shared STT source clip per JVM — the per-model table is in
 * {@code docs/WIRE_OBSERVATIONS.md}), so a second full run within 24 hours fails loudly here with a
 * {@code FanarRateLimitException} whose {@code retryAfter()} is hours long ({@code retry-after}
 * equals {@code x-ratelimit-reset}, envelope code {@code rate_limit_reached}). That is the
 * budget, not the SDK — the retry interceptor surfaces it immediately per ADR-025.</p>
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
                    Voice.HUDA, TtsResponseFormat.WAV, null, null));

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
    @DisplayName("§M.7b speechStream (wav) delivers chunks that concatenate to a RIFF/WAVE clip")
    void speechStream_deliversWavChunks(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            Flow.Publisher<byte[]> publisher = client.audio().speechStream(TextToSpeechRequest.builder()
                    .model(TtsModel.FANAR_AURA_TTS_2)
                    .input("مرحبا بكم في فنار")
                    .voice(Voice.HAMAD)
                    .responseFormat(TtsResponseFormat.WAV)
                    .build());

            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<byte[]>() {
                public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(byte[] chunk) { collected.writeBytes(chunk); }
                public void onError(Throwable t) { failure.set(t); done.countDown(); }
                public void onComplete() { done.countDown(); }
            });

            assertTrue(done.await(60, TimeUnit.SECONDS), "stream must terminate within 60s");
            assertNull(failure.get(), () -> "stream errored: " + failure.get());
            byte[] audio = collected.toByteArray();
            assertTrue(audio.length > 12, "WAV minimum header is 12 bytes, got " + audio.length);
            assertTrue(audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F',
                    "expected RIFF prefix on the concatenated stream");

            LiveOutputs.write("audio-output", "speech-stream-wav", "wav", audio);
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.7b speech with with_emotion=true on an emotion-capable voice (Radwa) returns audio")
    void speech_withEmotionOnCapableVoice(FanarJsonCodec codec) {
        // Radwa and Abdulrahman are the two emotion-capable built-ins per the 2026-08 spec.
        // An emotion-incapable voice or Fanar-Sadiq-TTS-1 would be rejected with HTTP 422.
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            byte[] audio = client.audio().speech(TextToSpeechRequest.builder()
                    .model(TtsModel.FANAR_AURA_TTS_2)
                    .input("يا لها من ليلة جميلة!")
                    .voice(Voice.RADWA)
                    .responseFormat(TtsResponseFormat.WAV)
                    .withEmotion(true)
                    .build());

            assertNotNull(audio, "audio bytes must be present");
            assertTrue(audio.length > 12, "WAV minimum header is 12 bytes, got " + audio.length);
            assertTrue(audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F',
                    "expected RIFF prefix for emotional synthesis output");

            LiveOutputs.write("audio-output", "speech-radwa-emotion-wav", "wav", audio);
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
