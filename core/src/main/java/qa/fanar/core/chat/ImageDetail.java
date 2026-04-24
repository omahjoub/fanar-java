package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Detail level hint for images attached to user messages.
 *
 * <p>The server uses this to decide how many image tokens to spend on the visual input.
 * Mirrors the {@code ImageURL.detail} enum in the Fanar OpenAPI spec.</p>
 */
public enum ImageDetail {

    /** Server chooses — the default when none is specified. */
    AUTO("auto"),

    /** Low-detail preview; cheaper on image tokens. */
    LOW("low"),

    /** High-detail analysis; spends more image tokens for sharper recognition. */
    HIGH("high");

    private final String wireValue;

    ImageDetail(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar uses on the wire. */
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
    public static ImageDetail fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (ImageDetail detail : values()) {
            if (detail.wireValue.equals(value)) {
                return detail;
            }
        }
        throw new IllegalArgumentException("Unknown ImageDetail wire value: " + value);
    }
}
