package qa.fanar.core.sadiq;

import java.util.Objects;
import java.util.Set;

/**
 * Why a deep-research background job failed — the {@code code} of a {@link JobError}. Mirrors the
 * {@code JobErrorCode} schema in the OpenAPI spec, but open: an unknown code decodes via
 * {@link #of(String)} instead of failing.
 *
 * <p>These codes describe a job's failure inside a {@code 200} response; they are not the error
 * envelope codes an HTTP error carries, and they map to no exception.</p>
 *
 * @param wireValue the exact string Fanar sends in the {@code code} field
 *
 * @author Oussama Mahjoub
 */
public record JobErrorCode(String wireValue) {

    public static final JobErrorCode BACKEND_TASK_LOST    = new JobErrorCode("backend_task_lost");
    public static final JobErrorCode BACKEND_FAILED       = new JobErrorCode("backend_failed");
    public static final JobErrorCode BACKEND_UNAVAILABLE  = new JobErrorCode("backend_unavailable");
    public static final JobErrorCode MAX_RUNTIME_EXCEEDED = new JobErrorCode("max_runtime_exceeded");
    public static final JobErrorCode INTERNAL_ERROR       = new JobErrorCode("internal_error");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<JobErrorCode> KNOWN = Set.of(
            BACKEND_TASK_LOST, BACKEND_FAILED, BACKEND_UNAVAILABLE, MAX_RUNTIME_EXCEEDED, INTERNAL_ERROR);

    public JobErrorCode {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new JobErrorCode(wireValue)}; provided for API symmetry. */
    public static JobErrorCode of(String wireValue) {
        return new JobErrorCode(wireValue);
    }
}
