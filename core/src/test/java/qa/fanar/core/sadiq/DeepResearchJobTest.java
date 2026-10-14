package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepResearchJobTest {

    private static final DeepResearchRequest ECHO = new DeepResearchRequest(
            ChatModel.FANAR_SADIQ_2, "ما فضل الصدقة في الإسلام؟", DeepResearchDepth.QUICK, false);
    private static final DeepResearchReport REPORT =
            new DeepResearchReport(null, "ar", "فضل الصدقة في الإسلام", null, null, null, null, null, null);

    @Test
    void holdsAllFields() {
        DeepResearchJob job = new DeepResearchJob(
                "5bbed6e2-ae53-486f-823a-2f01992ece59", JobStatus.COMPLETED,
                new IncompleteDetails(IncompleteReason.CONTENT_FILTER),
                new JobError(JobErrorCode.BACKEND_FAILED, "worker died"),
                100, 1, 1791099046L, 1791099046L, 1791099575L, 1791099575L,
                CancelSource.OWNER, ECHO, REPORT);
        assertEquals("5bbed6e2-ae53-486f-823a-2f01992ece59", job.id());
        assertEquals(JobStatus.COMPLETED, job.status());
        assertEquals(IncompleteReason.CONTENT_FILTER, job.incompleteDetails().reason());
        assertEquals(JobErrorCode.BACKEND_FAILED, job.error().code());
        assertEquals(100, job.progressPct());
        assertEquals(1, job.attempt());
        assertEquals(1791099046L, job.createdAt());
        assertEquals(1791099046L, job.startedAt());
        assertEquals(1791099575L, job.completedAt());
        assertEquals(1791099575L, job.updatedAt());
        assertEquals(CancelSource.OWNER, job.cancelSource());
        assertEquals(ECHO, job.request());
        assertEquals("فضل الصدقة في الإسلام", job.result().title());
    }

    @Test
    void aQueuedJobCarriesOnlyItsRequiredFields() {
        DeepResearchJob job = new DeepResearchJob(
                "c3869c31", JobStatus.QUEUED, null, null, 0, 0, 1791099697L, null, null, 1791099697L,
                null, ECHO, null);
        assertNull(job.incompleteDetails());
        assertNull(job.error());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        assertNull(job.cancelSource());
        assertNull(job.result());
    }

    @Test
    void rejectsNullIdStatusAndRequest() {
        assertThrows(NullPointerException.class, () -> new DeepResearchJob(
                null, JobStatus.QUEUED, null, null, 0, 0, 0L, null, null, 0L, null, ECHO, null));
        assertThrows(NullPointerException.class, () -> new DeepResearchJob(
                "j", null, null, null, 0, 0, 0L, null, null, 0L, null, ECHO, null));
        assertThrows(NullPointerException.class, () -> new DeepResearchJob(
                "j", JobStatus.QUEUED, null, null, 0, 0, 0L, null, null, 0L, null, null, null));
    }
}
