package qa.fanar.core;

import java.util.Objects;
import java.util.Set;

/**
 * Subtype of a content-filter rejection reported by Fanar's safety layer.
 *
 * <p>Attached to a {@link FanarContentFilterException} when the server provides one. Mirrors
 * the {@code ErrorContentFilterType} schema in the Fanar OpenAPI spec — but open: if Fanar
 * ever adds a new filter type the SDK will decode it gracefully into a {@code ContentFilterType}
 * carrying the new wire string instead of failing.</p>
 *
 * @param wireValue the exact string Fanar uses on the wire for this filter type
 *
 * @author Oussama Mahjoub
 */
public record ContentFilterType(String wireValue) {

    /** Content blocked for safety reasons (toxicity, violence, self-harm, and similar). */
    public static final ContentFilterType SAFETY     = new ContentFilterType("safety");

    /** Content matched a configured blocklist term. */
    public static final ContentFilterType BLOCKLIST  = new ContentFilterType("blocklist");

    /** Response was rejected as incomplete or truncated. */
    public static final ContentFilterType INCOMPLETE = new ContentFilterType("incomplete");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<ContentFilterType> KNOWN = Set.of(SAFETY, BLOCKLIST, INCOMPLETE);

    public ContentFilterType {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new ContentFilterType(wireValue)}; provided for API symmetry with other types. */
    public static ContentFilterType of(String wireValue) {
        return new ContentFilterType(wireValue);
    }
}
