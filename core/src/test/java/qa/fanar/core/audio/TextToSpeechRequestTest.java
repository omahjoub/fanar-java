package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextToSpeechRequestTest {

    @Test
    void holdsAllFields() {
        TextToSpeechRequest r = new TextToSpeechRequest(
                TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY,
                TtsResponseFormat.WAV, QuranReciter.ABDUL_BASIT, true);
        assertEquals(TtsModel.FANAR_AURA_TTS_2, r.model());
        assertEquals("hello", r.input());
        assertEquals(Voice.HARRY, r.voice());
        assertEquals(TtsResponseFormat.WAV, r.responseFormat());
        assertEquals(QuranReciter.ABDUL_BASIT, r.quranReciter());
        assertEquals(true, r.withEmotion());
    }

    @Test
    void ofLeavesOptionalsNull() {
        TextToSpeechRequest r = TextToSpeechRequest.of(
                TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY);
        assertEquals(TtsModel.FANAR_AURA_TTS_2, r.model());
        assertEquals("hello", r.input());
        assertEquals(Voice.HARRY, r.voice());
        assertNull(r.responseFormat());
        assertNull(r.quranReciter());
        assertNull(r.withEmotion());
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(null, "t", Voice.HARRY, null, null, null));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(TtsModel.FANAR_AURA_TTS_2, null, Voice.HARRY, null, null, null));
    }

    @Test
    void rejectsNullVoice() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(TtsModel.FANAR_AURA_TTS_2, "t", null, null, null, null));
    }

    // --- Builder --------------------------------------------------------------------------

    @Test
    void builderReturnsFreshInstance() {
        assertNotSame(TextToSpeechRequest.builder(), TextToSpeechRequest.builder());
    }

    @Test
    void builderAllFieldsRoundtrip() {
        TextToSpeechRequest r = TextToSpeechRequest.builder()
                .model(TtsModel.FANAR_SADIQ_TTS_1)
                .input("bismillah")
                .voice(Voice.RADWA)
                .responseFormat(TtsResponseFormat.MP3)
                .quranReciter(QuranReciter.MAHER_AL_MUAIQLY)
                .withEmotion(false)
                .build();
        assertEquals(TtsModel.FANAR_SADIQ_TTS_1, r.model());
        assertEquals("bismillah", r.input());
        assertEquals(Voice.RADWA, r.voice());
        assertEquals(TtsResponseFormat.MP3, r.responseFormat());
        assertEquals(QuranReciter.MAHER_AL_MUAIQLY, r.quranReciter());
        assertEquals(false, r.withEmotion());
    }

    @Test
    void builderLeavesUnsetOptionalsNull() {
        TextToSpeechRequest r = TextToSpeechRequest.builder()
                .model(TtsModel.FANAR_AURA_TTS_2)
                .input("hello")
                .voice(Voice.AMELIA)
                .build();
        assertNull(r.responseFormat());
        assertNull(r.quranReciter());
        assertNull(r.withEmotion());
    }

    @Test
    void builderValidationDelegatesToCanonicalConstructor() {
        assertThrows(NullPointerException.class, () -> TextToSpeechRequest.builder()
                .input("hello")
                .voice(Voice.AMELIA)
                .build());
    }
}
