package qa.fanar.adk;

import java.util.Objects;

/**
 * One request feature Fanar's chat endpoint cannot honour (ADR-030): which kind, and which one.
 * Carried by {@link UnsupportedFeatureException#features()} so a caller can branch on the kind
 * rather than parse the message.
 *
 * @param kind   the category
 * @param detail the item, as named in the exception message (for example {@code tool 'lookup'})
 */
public record UnsupportedFeature(Kind kind, String detail) {

    /** The categories of feature the adapter refuses or drops. */
    public enum Kind {
        /** A function declaration: declared by the agent, injected by ADK, or a built-in tool. */
        TOOL,
        /** A response schema, or a response MIME type other than plain text. */
        OUTPUT_SCHEMA,
        /** A request part with no Fanar mapping: inline bytes, function or tool calls and responses, code execution. */
        PART
    }

    public UnsupportedFeature {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }

    static UnsupportedFeature tool(String detail) {
        return new UnsupportedFeature(Kind.TOOL, detail);
    }

    static UnsupportedFeature outputSchema(String detail) {
        return new UnsupportedFeature(Kind.OUTPUT_SCHEMA, detail);
    }

    static UnsupportedFeature part(String detail) {
        return new UnsupportedFeature(Kind.PART, detail);
    }

    @Override
    public String toString() {
        return detail;
    }
}
