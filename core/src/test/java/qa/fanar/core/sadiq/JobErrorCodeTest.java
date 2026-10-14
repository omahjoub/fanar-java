package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobErrorCodeTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (JobErrorCode c : JobErrorCode.KNOWN) {
            assertEquals(c, JobErrorCode.of(c.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        JobErrorCode custom = JobErrorCode.of("quota_revoked");
        assertEquals("quota_revoked", custom.wireValue());
        assertFalse(JobErrorCode.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new JobErrorCode(null));
        assertThrows(NullPointerException.class, () -> JobErrorCode.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(5, JobErrorCode.KNOWN.size());
        assertEquals("backend_task_lost", JobErrorCode.BACKEND_TASK_LOST.wireValue());
        assertEquals("backend_failed", JobErrorCode.BACKEND_FAILED.wireValue());
        assertEquals("backend_unavailable", JobErrorCode.BACKEND_UNAVAILABLE.wireValue());
        assertEquals("max_runtime_exceeded", JobErrorCode.MAX_RUNTIME_EXCEEDED.wireValue());
        assertEquals("internal_error", JobErrorCode.INTERNAL_ERROR.wireValue());
    }
}
