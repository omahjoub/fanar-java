package qa.fanar.core.chat;

import java.util.Objects;

/**
 * A function identifier + arguments payload carried inside a {@link ToolCallData}.
 *
 * <p>Mirrors the {@code FunctionData} schema in the OpenAPI spec. Fanar emits the arguments as
 * a JSON-encoded string, not a parsed object — applications that want a typed shape deserialize
 * it themselves.</p>
 *
 * @param name      the function name being invoked; must not be {@code null}
 * @param arguments arguments as a JSON-encoded string; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record FunctionData(String name, String arguments) {

    public FunctionData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}
