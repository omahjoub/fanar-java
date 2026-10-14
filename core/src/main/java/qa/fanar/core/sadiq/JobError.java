package qa.fanar.core.sadiq;

import java.util.Objects;

/**
 * Why a deep-research background job {@linkplain JobStatus#FAILED failed}. Part of the job's
 * {@code 200} response, not an HTTP error: retrieving a failed job succeeds.
 *
 * @param code    the failure's code; must not be {@code null}
 * @param message the server's description; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record JobError(JobErrorCode code, String message) {

    public JobError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
