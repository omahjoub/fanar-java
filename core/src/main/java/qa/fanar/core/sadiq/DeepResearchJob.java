package qa.fanar.core.sadiq;

import java.util.Objects;

/**
 * A deep-research run in the background: what {@code SadiqClient.startDeepResearch},
 * {@code retrieveDeepResearchJob} and {@code cancelDeepResearchJob} return. Mirrors the spec's
 * {@code Job} schema without its two constants, {@code object} ({@code "job"}) and {@code type}
 * ({@code "deep_research"}), which this record type already fixes (ADR-032).
 *
 * <p>The {@link #status() status} is the only authoritative "done": {@link JobStatus#isTerminal()}
 * tells a polling loop when to stop, and {@link #result() result} carries the report once the status
 * is {@link JobStatus#COMPLETED}. The server keeps a job for 24 hours after it ends; after that, or
 * for another key's job, the operations answer 404 ({@code FanarNotFoundException}).</p>
 *
 * @param id                the job id, also sent as the {@code X-Id} header when the job is created;
 *                          must not be {@code null}
 * @param status            where the job stands; must not be {@code null}
 * @param incompleteDetails why the job ended {@linkplain JobStatus#INCOMPLETE incomplete}, or
 *                          {@code null}
 * @param error             why the job {@linkplain JobStatus#FAILED failed}, or {@code null}
 * @param progressPct       research progress, 0–100; it can reach 100 before the job completes
 * @param attempt           how many times the job has been started; above 1, it was restarted after a
 *                          server failure and its events replayed from the start
 * @param createdAt         when the job was created, Unix seconds
 * @param startedAt         when the job started, Unix seconds, or {@code null} while queued
 * @param completedAt       when the job ended, Unix seconds, or {@code null} while running
 * @param updatedAt         when the job last changed, Unix seconds — every few seconds while running
 * @param cancelSource      who cancelled the job, or {@code null}
 * @param request           what the job was asked to research, as the server resolved it — depth and
 *                          web search filled in with its defaults; must not be {@code null}. Passing it
 *                          to {@code startDeepResearch} starts the same research again.
 * @param result            the report — exactly what the synchronous call returns — once the status is
 *                          {@link JobStatus#COMPLETED}, otherwise {@code null}
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchJob(
        String id,
        JobStatus status,
        IncompleteDetails incompleteDetails,
        JobError error,
        int progressPct,
        int attempt,
        long createdAt,
        Long startedAt,
        Long completedAt,
        long updatedAt,
        CancelSource cancelSource,
        DeepResearchRequest request,
        DeepResearchReport result
) {

    public DeepResearchJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(request, "request");
    }
}
