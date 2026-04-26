package qa.fanar.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Detail level hint for images attached to user messages.
 *
 * <p>Mirrors the {@code ImageURL.detail} schema in the Fanar OpenAPI spec. Open: callers can
 * pass any value Fanar may add later via {@link #of(String)} without waiting for an SDK release.
 * The server uses this hint to decide how many image tokens to spend on the visual input.</p>
 *
 * @param wireValue the exact string Fanar accepts on the wire
 *
 * @author Oussama Mahjoub
 */
public record ImageDetail(String wireValue) {

    /** Server chooses — the default when none is specified. */
    public static final ImageDetail AUTO = new ImageDetail("auto");

    /** Low-detail preview; cheaper on image tokens. */
    public static final ImageDetail LOW  = new ImageDetail("low");

    /** High-detail analysis; spends more image tokens for sharper recognition. */
    public static final ImageDetail HIGH = new ImageDetail("high");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<ImageDetail> KNOWN = Set.of(AUTO, LOW, HIGH);

    public ImageDetail {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new ImageDetail(wireValue)}; provided for API symmetry with other types. */
    public static ImageDetail of(String wireValue) {
        return new ImageDetail(wireValue);
    }
}
