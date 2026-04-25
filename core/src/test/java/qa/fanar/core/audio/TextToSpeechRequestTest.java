package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextToSpeechRequestTest {

    @Test
    void holdsAllFields() {
        TextToSpeechRequest r = new TextToSpeechRequest(
                TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY,
                TtsResponseFormat.WAV, QuranReciter.ABDUL_BASIT);
        assertEquals(TtsModel.FANAR_AURA_TTS_2, r.model());
        assertEquals("hello", r.input());
        assertEquals(Voice.HARRY, r.voice());
        assertEquals(TtsResponseFormat.WAV, r.responseFormat());
        assertEquals(QuranReciter.ABDUL_BASIT, r.quranReciter());
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
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(null, "t", Voice.HARRY, null, null));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(TtsModel.FANAR_AURA_TTS_2, null, Voice.HARRY, null, null));
    }

    @Test
    void rejectsNullVoice() {
        assertThrows(NullPointerException.class,
                () -> new TextToSpeechRequest(TtsModel.FANAR_AURA_TTS_2, "t", null, null, null));
    }
}
