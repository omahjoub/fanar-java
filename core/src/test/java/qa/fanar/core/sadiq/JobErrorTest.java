package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobErrorTest {

    @Test
    void holdsFields() {
        JobError e = new JobError(JobErrorCode.MAX_RUNTIME_EXCEEDED, "ran out of time");
        assertEquals(JobErrorCode.MAX_RUNTIME_EXCEEDED, e.code());
        assertEquals("ran out of time", e.message());
    }

    @Test
    void rejectsNullCodeAndMessage() {
        assertThrows(NullPointerException.class, () -> new JobError(null, "m"));
        assertThrows(NullPointerException.class, () -> new JobError(JobErrorCode.BACKEND_FAILED, null));
    }
}
