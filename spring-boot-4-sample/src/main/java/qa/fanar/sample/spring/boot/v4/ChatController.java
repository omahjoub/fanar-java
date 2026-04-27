package qa.fanar.sample.spring.boot.v4;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.TextContent;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.models.AvailableModel;

/**
 * REST controller demoing the auto-wired {@link FanarClient}.
 *
 * <p>The controller does <em>not</em> mutate the client or build it explicitly — it just
 * receives the bean the starter produced from {@code fanar.*} configuration. That is the whole
 * value proposition of the starter: applications express intent in YAML, not Java.</p>
 *
 * @author Oussama Mahjoub
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final FanarClient fanar;

    public ChatController(FanarClient fanar) {
        this.fanar = fanar;
    }

    /** {@code GET /api/models} — list models the configured API key can call. */
    @GetMapping("/models")
    public ModelsView models() {
        var response = fanar.models().list();
        var ids = response.models().stream().map(AvailableModel::id).toList();
        return new ModelsView(response.id(), ids);
    }

    /** {@code POST /api/chat} — single-turn chat completion against the routing {@code Fanar} model. */
    @PostMapping("/chat")
    public ChatView chat(@RequestBody ChatPrompt prompt) {
        ChatRequest request = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of(prompt.prompt()))
                .build();
        ChatResponse response = fanar.chat().send(request);
        return new ChatView(response.id(), response.model(), firstReply(response));
    }

    private static String firstReply(ChatResponse response) {
        if (response.choices().isEmpty()) {
            return "";
        }
        ChatChoice choice = response.choices().getFirst();
        for (ResponseContent part : choice.message().content()) {
            if (part instanceof TextContent(String text1)) {
                return text1;
            }
        }
        return "";
    }

    /** Request body for {@link #chat(ChatPrompt)}. */
    public record ChatPrompt(String prompt) { }

    /** Response shape for {@link #chat(ChatPrompt)}. */
    public record ChatView(String id, String model, String reply) { }

    /** Response shape for {@link #models()}. */
    public record ModelsView(String id, List<String> models) { }
}
