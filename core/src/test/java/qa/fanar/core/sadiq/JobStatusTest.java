package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStatusTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (JobStatus s : JobStatus.KNOWN) {
            assertEquals(s, JobStatus.of(s.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        JobStatus custom = JobStatus.of("requires_action");
        assertEquals("requires_action", custom.wireValue());
        assertFalse(JobStatus.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new JobStatus(null));
        assertThrows(NullPointerException.class, () -> JobStatus.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(6, JobStatus.KNOWN.size());
        assertEquals("queued", JobStatus.QUEUED.wireValue());
        assertEquals("in_progress", JobStatus.IN_PROGRESS.wireValue());
        assertEquals("completed", JobStatus.COMPLETED.wireValue());
        assertEquals("failed", JobStatus.FAILED.wireValue());
        assertEquals("cancelled", JobStatus.CANCELLED.wireValue());
        assertEquals("incomplete", JobStatus.INCOMPLETE.wireValue());
    }

    @Test
    void onlyQueuedAndInProgressAreStillRunning() {
        assertFalse(JobStatus.QUEUED.isTerminal(), "queued");
        assertFalse(JobStatus.IN_PROGRESS.isTerminal(), "in_progress");
        assertTrue(JobStatus.COMPLETED.isTerminal(), "completed");
        assertTrue(JobStatus.FAILED.isTerminal(), "failed");
        assertTrue(JobStatus.CANCELLED.isTerminal(), "cancelled");
        assertTrue(JobStatus.INCOMPLETE.isTerminal(), "incomplete");
    }

    @Test
    void anUnknownStatusCountsAsEndedSoAPollingLoopStops() {
        assertTrue(JobStatus.of("requires_action").isTerminal());
        assertFalse(JobStatus.of("queued").isTerminal(), "a decoded known value behaves like its constant");
    }
}
