package qa.fanar.core.chat;

import java.util.Map;
import java.util.Objects;

/**
 * A tool invocation recorded on an {@link AssistantMessage}.
 *
 * <p>Fanar emits tool calls from the server side — the SDK's chat request does not currently have
 * a {@code tools} parameter for client-declared function calling. The fields here describe what
 * the server recorded: which tool was invoked, with what arguments, what result came back, and
 * whether it errored.</p>
 *
 * <p>The {@code arguments} map is defensively copied on construction. {@code result} and
 * {@code structuredContent} are intentionally {@code Object} — their shape depends on the
 * specific tool and is preserved verbatim from the wire.</p>
 *
 * @param id                unique identifier assigned by the server; must not be {@code null}
 * @param name              the name of the tool invoked; must not be {@code null}
 * @param arguments         arguments passed to the tool; must not be {@code null}
 * @param result            result returned by the tool (wire-preserved, may be {@code null})
 * @param structuredContent structured representation of the tool's result (wire-preserved, may be {@code null})
 * @param isError           {@code true} if the tool invocation errored
 *
 * @author Oussama Mahjoub
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments,
        Object result,
        Object structuredContent,
        boolean isError
) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = Map.copyOf(arguments);
    }
}
