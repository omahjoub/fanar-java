package qa.fanar.core;

import java.util.Objects;

/**
 * Subtype of a content-filter rejection reported by Fanar's safety layer.
 *
 * <p>Attached to a {@link FanarContentFilterException} when the server provides one. Mirrors
 * the {@code ErrorContentFilterType} enum in the Fanar OpenAPI specification.</p>
 */
public enum ContentFilterType {

    /** Content blocked for safety reasons (toxicity, violence, self-harm, and similar). */
    SAFETY("safety"),

    /** Content matched a configured blocklist term. */
    BLOCKLIST("blocklist"),

    /** Response was rejected as incomplete or truncated. */
    INCOMPLETE("incomplete");

    private final String wireValue;

    ContentFilterType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar uses on the wire for this filter type. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Look up an enum value by its wire format.
     *
     * @param value the wire-format string; must not be {@code null}
     * @return the matching enum value
     * @throws IllegalArgumentException if no enum value matches
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    public static ContentFilterType fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (ContentFilterType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ContentFilterType wire value: " + value);
    }
}
