package qa.fanar.json.jackson3;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

/**
 * Shared extraction helpers for the {@code Choice*} flattening deserializers. Each Fanar choice
 * node has the wire shape {@code {index, finish_reason, delta: {...}}}; these helpers pull out
 * the common top-level fields and the {@code delta} subtree so each deserializer can focus on
 * its own delta-specific field.
 *
 * @author Oussama Mahjoub
 */
final class ChoiceNodes {

    private ChoiceNodes() {
        // not instantiable
    }

    /** 0-based choice index; defaults to 0 when absent. */
    static int index(JsonNode choice) {
        JsonNode n = choice.path("index");
        return n.isMissingNode() || n.isNull() ? 0 : n.asInt();
    }

    /** Choice-level {@code finish_reason}, or {@code null} when absent or JSON {@code null}. */
    static String finishReason(JsonNode choice) {
        return textOrNull(choice, "finish_reason");
    }

    /** The {@code delta} subtree, or {@code null} when absent or JSON {@code null}. */
    static JsonNode delta(JsonNode choice) {
        JsonNode d = choice.path("delta");
        return d.isMissingNode() || d.isNull() ? null : d;
    }

    /**
     * Extract a scalar text field from {@code node}, returning {@code null} when the key is
     * absent or the JSON value is {@code null}.
     */
    static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    /**
     * Read {@code arrayNode} as a {@code List<T>}, element-by-element via the supplied
     * {@code DeserializationContext}. Returns an empty list when the node is not an array
     * (including the {@code MissingNode} returned by {@code JsonNode.path(...)} for absent keys).
     *
     * <p>Callers must pass a non-null node — use {@code path(...)} rather than {@code get(...)}.
     */
    static <T> List<T> readList(JsonNode arrayNode, Class<T> elementType, DeserializationContext ctxt) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(arrayNode.size());
        for (JsonNode element : arrayNode) {
            out.add(ctxt.readTreeAsValue(element, elementType));
        }
        return out;
    }
}
