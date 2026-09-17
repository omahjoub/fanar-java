package qa.fanar.adk;

/**
 * What {@link FanarLlm} does with request features Fanar's chat endpoint cannot honour
 * (ADR-030): function declarations, whether the agent declared them or ADK injected them; an
 * output schema or a non-text response MIME type; and request parts with no Fanar mapping, such
 * as inline bytes, function calls and responses, or executable code.
 */
public enum UnsupportedFeaturePolicy {

    /**
     * Refuse the request before anything goes on the wire, with an
     * {@link UnsupportedFeatureException} naming the offending items. The default: in ADK a
     * dropped tool is not a missing feature but a broken control flow, and a dropped output
     * schema has the flow parse prose as JSON.
     */
    REJECT,

    /** Drop the unsupported features and send the rest of the request. */
    IGNORE
}
