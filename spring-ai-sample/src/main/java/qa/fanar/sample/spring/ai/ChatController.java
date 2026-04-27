package qa.fanar.sample.spring.ai;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * REST controller demoing Spring AI's {@link ChatClient} on top of the auto-wired Fanar
 * {@code ChatModel}. The interesting part is the multi-turn endpoint — by passing a
 * {@code conversationId} as an advisor param, Spring AI's
 * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} loads prior
 * messages from {@link ChatMemory} into the prompt and persists the response automatically.
 *
 * @author Oussama Mahjoub
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chat;
    private final ChatMemory memory;

    public ChatController(ChatClient chat, ChatMemory memory) {
        this.chat = chat;
        this.memory = memory;
    }

    /** Single-turn — no conversation id, no memory pin. Each call is independent. */
    @PostMapping
    public Reply chat(@RequestBody Prompt prompt) {
        String reply = chat.prompt().user(prompt.message()).call().content();
        return new Reply(reply);
    }

    /**
     * Multi-turn — the {@code conversationId} pins this exchange to a slot in
     * {@link ChatMemory}. The advisor pre-loads the prior messages on the way in and persists
     * the model's reply on the way out.
     */
    @PostMapping("/{conversationId}")
    public Reply chatInConversation(@PathVariable("conversationId") String conversationId,
                                    @RequestBody Prompt prompt) {
        String reply = chat.prompt()
                .user(prompt.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new Reply(reply);
    }

    /** Peek at what the memory has stored — useful for debugging the advisor. */
    @GetMapping("/{conversationId}/history")
    public List<HistoryEntry> history(@PathVariable("conversationId") String conversationId) {
        return memory.get(conversationId).stream()
                .map(m -> new HistoryEntry(m.getMessageType().getValue(), textOf(m)))
                .toList();
    }

    /**
     * Streaming — Spring AI returns a {@code Flux<ChatResponse>} of incremental chunks. We
     * project to a {@code Flux<String>} of token text and serve as Server-Sent Events so a
     * browser/curl client can consume incrementally.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody Prompt prompt) {
        return chat.prompt().user(prompt.message()).stream().content();
    }

    private static String textOf(Message m) {
        // Message.getText() collapses multi-part content to a string for the simple case.
        // The history endpoint is a debugging aid — fancier rendering belongs in the UI layer.
        return m.getText();
    }

    public record Prompt(String message) { }
    public record Reply(String reply) { }
    public record HistoryEntry(String role, String text) { }
}
