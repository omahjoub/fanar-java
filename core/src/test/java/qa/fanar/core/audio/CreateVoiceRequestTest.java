package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateVoiceRequestTest {

    @Test
    void holdsAllFields() {
        byte[] audio = "wav-bytes".getBytes();
        CreateVoiceRequest r = new CreateVoiceRequest("alice", audio, "hello world");
        assertEquals("alice", r.name());
        assertEquals(audio, r.audio());
        assertEquals("hello world", r.transcript());
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class,
                () -> new CreateVoiceRequest(null, new byte[0], "t"));
    }

    @Test
    void rejectsNullAudio() {
        assertThrows(NullPointerException.class,
                () -> new CreateVoiceRequest("alice", null, "t"));
    }

    @Test
    void rejectsNullTranscript() {
        assertThrows(NullPointerException.class,
                () -> new CreateVoiceRequest("alice", new byte[0], null));
    }

    @Test
    void equalityIsContentBasedOnAudioBytes() {
        CreateVoiceRequest a = new CreateVoiceRequest("alice", new byte[]{1, 2, 3}, "t");
        CreateVoiceRequest b = new CreateVoiceRequest("alice", new byte[]{1, 2, 3}, "t");
        CreateVoiceRequest c = new CreateVoiceRequest("alice", new byte[]{1, 2, 4}, "t");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void equalsRejectsOtherTypes() {
        CreateVoiceRequest a = new CreateVoiceRequest("alice", new byte[]{1}, "t");
        assertNotEquals("not a request", a);
        assertNotEquals(null, a);
    }

    @Test
    void equalityRejectsDifferentName() {
        CreateVoiceRequest a = new CreateVoiceRequest("alice", new byte[]{1}, "t");
        CreateVoiceRequest b = new CreateVoiceRequest("bob",   new byte[]{1}, "t");
        assertNotEquals(a, b);
    }

    @Test
    void equalityRejectsDifferentTranscript() {
        CreateVoiceRequest a = new CreateVoiceRequest("alice", new byte[]{1}, "hello");
        CreateVoiceRequest b = new CreateVoiceRequest("alice", new byte[]{1}, "world");
        assertNotEquals(a, b);
    }

    @Test
    void toStringHidesAudioBytes() {
        CreateVoiceRequest r = new CreateVoiceRequest("alice", new byte[10], "hello");
        String s = r.toString();
        assertTrue(s.contains("alice"), s);
        assertTrue(s.contains("10 bytes"), s);
        assertTrue(s.contains("hello"), s);
    }
}
