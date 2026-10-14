package qa.fanar.core.sadiq;

import java.util.Objects;
import java.util.Set;

/**
 * Why a deep-research background job ended {@linkplain JobStatus#INCOMPLETE incomplete}. Mirrors
 * the {@code IncompleteReason} schema in the OpenAPI spec, but open: an unknown reason decodes via
 * {@link #of(String)} instead of failing.
 *
 * @param wireValue the exact string Fanar sends in the {@code reason} field
 *
 * @author Oussama Mahjoub
 */
public record IncompleteReason(String wireValue) {

    /** The output was stopped by a content filter; the job has no result. */
    public static final IncompleteReason CONTENT_FILTER = new IncompleteReason("content_filter");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<IncompleteReason> KNOWN = Set.of(CONTENT_FILTER);

    public IncompleteReason {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new IncompleteReason(wireValue)}; provided for API symmetry. */
    public static IncompleteReason of(String wireValue) {
        return new IncompleteReason(wireValue);
    }
}
