package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptionRequestTest {

    @Test
    void holdsAllFields() {
        byte[] audio = "wav-bytes".getBytes();
        TranscriptionRequest r = new TranscriptionRequest(
                audio, "audio.wav", "audio/wav",
                SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        assertEquals(audio, r.file());
        assertEquals("audio.wav", r.filename());
        assertEquals("audio/wav", r.contentType());
        assertEquals(SttModel.FANAR_AURA_STT_1, r.model());
        assertEquals(SttFormat.TEXT, r.format());
    }

    @Test
    void factoryDefaultsFormatToNull() {
        TranscriptionRequest r = TranscriptionRequest.of(
                new byte[]{1, 2, 3}, "audio.wav", "audio/wav", SttModel.FANAR_AURA_STT_1);
        assertNull(r.format());
        assertEquals(SttModel.FANAR_AURA_STT_1, r.model());
    }

    @Test
    void rejectsNullFile() {
        assertThrows(NullPointerException.class, () -> new TranscriptionRequest(
                null, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, null));
    }

    @Test
    void rejectsNullFilename() {
        assertThrows(NullPointerException.class, () -> new TranscriptionRequest(
                new byte[0], null, "audio/wav", SttModel.FANAR_AURA_STT_1, null));
    }

    @Test
    void rejectsNullContentType() {
        assertThrows(NullPointerException.class, () -> new TranscriptionRequest(
                new byte[0], "f", null, SttModel.FANAR_AURA_STT_1, null));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class, () -> new TranscriptionRequest(
                new byte[0], "f", "audio/wav", null, null));
    }

    @Test
    void formatIsNullable() {
        TranscriptionRequest r = new TranscriptionRequest(
                new byte[0], "f", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        assertNull(r.format());
    }

    @Test
    void equalityIsContentBasedOnFileBytes() {
        TranscriptionRequest a = new TranscriptionRequest(
                new byte[]{1, 2, 3}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        TranscriptionRequest b = new TranscriptionRequest(
                new byte[]{1, 2, 3}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        TranscriptionRequest c = new TranscriptionRequest(
                new byte[]{1, 2, 4}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void equalsRejectsOtherTypes() {
        TranscriptionRequest r = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        // Direct .equals() to exercise the instanceof-false branch in the record's
        // overridden equals — assertNotEquals(unexpected, actual) dispatches on the
        // first arg, so swapping the order would never reach our equals at all.
        assertFalse(r.equals(new Object()));
        assertFalse(r.equals(null));
    }

    @Test
    void equalityRejectsDifferentFilename() {
        TranscriptionRequest a = new TranscriptionRequest(
                new byte[]{1}, "a.wav", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        TranscriptionRequest b = new TranscriptionRequest(
                new byte[]{1}, "b.wav", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        assertNotEquals(a, b);
    }

    @Test
    void equalityRejectsDifferentContentType() {
        TranscriptionRequest a = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        TranscriptionRequest b = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/mpeg", SttModel.FANAR_AURA_STT_1, null);
        assertNotEquals(a, b);
    }

    @Test
    void equalityRejectsDifferentModel() {
        TranscriptionRequest a = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, null);
        TranscriptionRequest b = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_LF_1, null);
        assertNotEquals(a, b);
    }

    @Test
    void equalityRejectsDifferentFormat() {
        TranscriptionRequest a = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        TranscriptionRequest b = new TranscriptionRequest(
                new byte[]{1}, "f", "audio/wav", SttModel.FANAR_AURA_STT_1, SttFormat.JSON);
        assertNotEquals(a, b);
    }

    @Test
    void toStringHidesFileBytes() {
        TranscriptionRequest r = new TranscriptionRequest(
                new byte[42], "audio.wav", "audio/wav",
                SttModel.FANAR_AURA_STT_1, SttFormat.TEXT);
        String s = r.toString();
        assertTrue(s.contains("42 bytes"), s);
        assertTrue(s.contains("audio.wav"), s);
        assertTrue(s.contains("audio/wav"), s);
    }
}
