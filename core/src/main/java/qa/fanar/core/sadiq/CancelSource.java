package qa.fanar.core.sadiq;

import java.util.Objects;
import java.util.Set;

/**
 * Who cancelled a deep-research background job. Mirrors the {@code CancelSource} schema in the
 * OpenAPI spec, but open: an unknown source decodes via {@link #of(String)} instead of failing.
 *
 * @param wireValue the exact string Fanar sends in the {@code cancel_source} field
 *
 * @author Oussama Mahjoub
 */
public record CancelSource(String wireValue) {

    /** The job's owner — a {@code cancelDeepResearchJob} call. */
    public static final CancelSource OWNER = new CancelSource("owner");
    /** Fanar's operators. */
    public static final CancelSource ADMIN = new CancelSource("admin");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<CancelSource> KNOWN = Set.of(OWNER, ADMIN);

    public CancelSource {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new CancelSource(wireValue)}; provided for API symmetry. */
    public static CancelSource of(String wireValue) {
        return new CancelSource(wireValue);
    }
}
