package qa.fanar.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Madhab (Islamic school of jurisprudence) filter for the madhab-aware Islamic RAG model
 * ({@code Fanar-Sadiq-2}).
 *
 * <p>Mirrors the {@code MadhabEnum} in the Fanar OpenAPI spec — but open: if Fanar adds a new
 * school, callers can target it via {@link #of(String)} without waiting for an SDK release.
 * Used on the {@code ChatRequest} {@code madhab} field, which only the {@code Fanar-Sadiq-2}
 * model honours.</p>
 *
 * @param wireValue the exact string Fanar uses on the wire for this madhab
 *
 * @author Oussama Mahjoub
 */
public record Madhab(String wireValue) {

    /** No filtering — draw from all schools. */
    public static final Madhab ALL     = new Madhab("all");

    /** The Hanafi school. */
    public static final Madhab HANAFI  = new Madhab("hanafi");

    /** The Maliki school. */
    public static final Madhab MALIKI  = new Madhab("maliki");

    /** The Shafi'i school. */
    public static final Madhab SHAFII  = new Madhab("shafii");

    /** The Hanbali school. */
    public static final Madhab HANBALI = new Madhab("hanbali");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<Madhab> KNOWN = Set.of(ALL, HANAFI, MALIKI, SHAFII, HANBALI);

    public Madhab {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new Madhab(wireValue)}; provided for API symmetry with other types. */
    public static Madhab of(String wireValue) {
        return new Madhab(wireValue);
    }
}
