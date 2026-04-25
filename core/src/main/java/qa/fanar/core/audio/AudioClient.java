package qa.fanar.core.audio;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/audio/*} endpoints. Returned by {@code FanarClient.audio()}.
 *
 * <p>The audio domain has multiple concerns surfaced as flat methods on this single client:</p>
 * <ul>
 *   <li>{@link #listVoices()} — list user-created voices (built-in voices are
 *       {@link Voice#KNOWN}; this returns only personalized ones).</li>
 *   <li>{@link #createVoice(CreateVoiceRequest)} — upload a WAV sample to create a personalized
 *       voice. Sent as {@code multipart/form-data}.</li>
 *   <li>{@link #deleteVoice(String)} — remove a personalized voice by name.</li>
 * </ul>
 *
 * <p>Subsequent sub-milestones add {@code speech(...)} (TTS — binary audio out) and
 * {@code transcribe(...)} (STT — multipart audio in) to this same interface.</p>
 *
 * <p>Implementations must be thread-safe — one {@code AudioClient} instance backs every call on
 * a given {@code FanarClient}.</p>
 */
public interface AudioClient {

    /** List the user-created (personalized) voices for the current API key. */
    VoiceResponse listVoices();

    /** Async variant of {@link #listVoices()}. */
    CompletableFuture<VoiceResponse> listVoicesAsync();

    /** Create a personalized voice from a WAV sample. */
    void createVoice(CreateVoiceRequest request);

    /** Async variant of {@link #createVoice(CreateVoiceRequest)}. */
    CompletableFuture<Void> createVoiceAsync(CreateVoiceRequest request);

    /** Delete a personalized voice by name. */
    void deleteVoice(String name);

    /** Async variant of {@link #deleteVoice(String)}. */
    CompletableFuture<Void> deleteVoiceAsync(String name);

    /**
     * Synthesize speech from text. Returns the raw audio bytes — caller controls the format via
     * {@link TextToSpeechRequest#responseFormat()} ({@link TtsResponseFormat#MP3} or
     * {@link TtsResponseFormat#WAV}; default mp3) and is responsible for writing the bytes to
     * disk, streaming them, or playing them back as appropriate.
     */
    byte[] speech(TextToSpeechRequest request);

    /** Async variant of {@link #speech(TextToSpeechRequest)}. */
    CompletableFuture<byte[]> speechAsync(TextToSpeechRequest request);

    /**
     * Transcribe an audio file. The returned {@link SpeechToTextResponse} is one of three
     * sealed variants — {@link SpeechToTextResponse.Text}, {@link SpeechToTextResponse.Srt},
     * or {@link SpeechToTextResponse.Json} — depending on the {@link SttFormat} requested.
     */
    SpeechToTextResponse transcribe(TranscriptionRequest request);

    /** Async variant of {@link #transcribe(TranscriptionRequest)}. */
    CompletableFuture<SpeechToTextResponse> transcribeAsync(TranscriptionRequest request);
}
