package qa.fanar.core.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Domain facade for the {@code /v1/audio/*} endpoints. Returned by {@code FanarClient.audio()}.
 *
 * <p>The audio domain has multiple concerns surfaced as flat methods on this single client:</p>
 * <ul>
 *   <li>{@link #listVoices()} — the voice catalogue: built-in public voices plus the
 *       personalized voices registered for the API key.</li>
 *   <li>{@link #createVoice(CreateVoiceRequest)} / {@link #deleteVoice(String)} — personalized
 *       voice management. Creation is sent as {@code multipart/form-data}.</li>
 *   <li>{@link #speech(TextToSpeechRequest)} / {@link #speechStream(TextToSpeechRequest)} —
 *       TTS, buffered or chunk-streamed binary audio out.</li>
 *   <li>{@link #transcribe(TranscriptionRequest)} — STT, multipart audio in.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code AudioClient} instance backs every call on
 * a given {@code FanarClient}.</p>
 *
 * @author Oussama Mahjoub
 */
public interface AudioClient {

    /**
     * List the available voices: always the built-in public voices, plus the personalized
     * voices registered for the current API key when voice personalization is authorized.
     */
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
     * Synthesize speech from text, streaming the audio bytes as the server generates them
     * (the wire {@code stream:true} mode; supported for both mp3 and wav).
     *
     * <p>The returned publisher supports a single subscriber, honours back-pressure, and emits
     * opaque {@code byte[]} chunks whose boundaries follow transport reads — concatenate them in
     * emission order to reconstruct the full clip. Cancelling the subscription closes the
     * underlying connection. The initial request (headers, interceptors, error mapping) behaves
     * exactly like {@link #speech(TextToSpeechRequest)}; mid-stream failures surface via
     * {@code Subscriber.onError}.</p>
     */
    Flow.Publisher<byte[]> speechStream(TextToSpeechRequest request);

    /**
     * Transcribe an audio file. The returned {@link SpeechToTextResponse} is one of three
     * sealed variants — {@link SpeechToTextResponse.Text}, {@link SpeechToTextResponse.Srt},
     * or {@link SpeechToTextResponse.Json} — depending on the {@link SttFormat} requested.
     */
    SpeechToTextResponse transcribe(TranscriptionRequest request);

    /** Async variant of {@link #transcribe(TranscriptionRequest)}. */
    CompletableFuture<SpeechToTextResponse> transcribeAsync(TranscriptionRequest request);
}
