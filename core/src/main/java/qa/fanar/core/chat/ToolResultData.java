package qa.fanar.core.chat;

import java.util.Map;
import java.util.Objects;

/**
 * Payload carrying the outcome of a server-side tool invocation, inside a
 * {@link ToolResultChunk}.
 *
 * <p>Distinct from {@link ToolCall} (request-side assistant message) because the wire shape
 * diverges: here {@code result} is a JSON string and {@code structured_content} is an object
 * map, whereas on {@code ToolCall} both are typed as {@code any}.</p>
 *
 * @param id                tool-call id (matches the id from the preceding {@link ToolCallData});
 *                          must not be {@code null}
 * @param name              tool name; may be {@code null} if Fanar chose to omit
 * @param arguments         arguments the tool was invoked with; never {@code null}, may be empty
 * @param result            result the tool returned (JSON-encoded string); may be {@code null}
 * @param structuredContent parsed-structure representation of the tool's result; may be
 *                          {@code null}
 * @param isError           whether the invocation failed
 *
 * @author Oussama Mahjoub
 */
public record ToolResultData(
        String id,
        String name,
        Map<String, Object> arguments,
        String result,
        Map<String, Object> structuredContent,
        boolean isError
) {

    public ToolResultData {
        Objects.requireNonNull(id, "id");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        structuredContent = structuredContent == null ? null : Map.copyOf(structuredContent);
    }
}
