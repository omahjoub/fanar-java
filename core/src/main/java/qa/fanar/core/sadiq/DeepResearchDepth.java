package qa.fanar.core.sadiq;

import java.util.Objects;
import java.util.Set;

/**
 * How far a deep-research run goes before it writes the report. Mirrors the
 * {@code DeepResearchDepth} schema in the OpenAPI spec, but open: callers can target a new depth
 * via {@link #of(String)} the day Fanar ships it.
 *
 * <p>Depths, with the runtimes the spec quotes (a research run takes minutes, not seconds):</p>
 * <ul>
 *   <li>{@link #QUICK} — single-pass summary, roughly 3–6 minutes.</li>
 *   <li>{@link #STANDARD} — multi-pass with balanced depth and speed, roughly 7–10 minutes; the
 *       server's default when the request omits the depth.</li>
 *   <li>{@link #COMPREHENSIVE} — exhaustive research with maximum source coverage, longer still.</li>
 * </ul>
 *
 * @param wireValue the exact string Fanar accepts in the {@code depth} field
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchDepth(String wireValue) {

    public static final DeepResearchDepth QUICK         = new DeepResearchDepth("quick");
    public static final DeepResearchDepth STANDARD      = new DeepResearchDepth("standard");
    public static final DeepResearchDepth COMPREHENSIVE = new DeepResearchDepth("comprehensive");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<DeepResearchDepth> KNOWN = Set.of(QUICK, STANDARD, COMPREHENSIVE);

    public DeepResearchDepth {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new DeepResearchDepth(wireValue)}; provided for API symmetry. */
    public static DeepResearchDepth of(String wireValue) {
        return new DeepResearchDepth(wireValue);
    }
}
