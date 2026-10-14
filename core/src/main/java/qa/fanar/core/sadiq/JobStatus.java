package qa.fanar.core.sadiq;

import java.util.Objects;
import java.util.Set;

/**
 * Where a deep-research background job stands. Mirrors the {@code JobStatus} schema in the OpenAPI
 * spec — "OpenAI's background-response vocabulary, verbatim" — but open: an unknown status decodes
 * via {@link #of(String)} instead of failing.
 *
 * <p>A job moves {@link #QUEUED} → {@link #IN_PROGRESS} → one of {@link #COMPLETED},
 * {@link #FAILED}, {@link #CANCELLED} or {@link #INCOMPLETE} (the output was stopped by a content
 * filter). Only the status says a job is done: its {@code progressPct} can reach 100 while the last
 * sentences are still being checked.</p>
 *
 * @param wireValue the exact string Fanar sends in the {@code status} field
 *
 * @author Oussama Mahjoub
 */
public record JobStatus(String wireValue) {

    public static final JobStatus QUEUED      = new JobStatus("queued");
    public static final JobStatus IN_PROGRESS = new JobStatus("in_progress");
    public static final JobStatus COMPLETED   = new JobStatus("completed");
    public static final JobStatus FAILED      = new JobStatus("failed");
    public static final JobStatus CANCELLED   = new JobStatus("cancelled");
    public static final JobStatus INCOMPLETE  = new JobStatus("incomplete");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<JobStatus> KNOWN =
            Set.of(QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, INCOMPLETE);

    public JobStatus {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new JobStatus(wireValue)}; provided for API symmetry. */
    public static JobStatus of(String wireValue) {
        return new JobStatus(wireValue);
    }

    /**
     * Whether the job has ended — every status but {@link #QUEUED} and {@link #IN_PROGRESS}. A
     * status this SDK does not know counts as ended, so a polling loop stops and surfaces it rather
     * than spinning until the job's 24-hour retention runs out (ADR-032).
     *
     * @return {@code true} once the job will change no further
     */
    public boolean isTerminal() {
        return !equals(QUEUED) && !equals(IN_PROGRESS);
    }
}
