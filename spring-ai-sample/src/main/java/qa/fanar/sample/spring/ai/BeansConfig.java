package qa.fanar.sample.spring.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Spring AI's higher-level surface on top of the {@link ChatModel} bean the starter
 * registers.
 *
 * <p>{@link ChatClient} is built once with a {@link MessageChatMemoryAdvisor} as a default
 * advisor — every prompt that flows through this client (including ones with no
 * {@code conversationId}) gets persisted into the in-memory {@link MessageWindowChatMemory}.
 * Callers can pin a conversation by supplying {@code ChatMemory.CONVERSATION_ID} as an advisor
 * param at call time.</p>
 *
 * @author Oussama Mahjoub
 */
@Configuration(proxyBeanMethods = false)
class BeansConfig {

    /**
     * Sliding-window in-memory store. Keeps at most {@code maxMessages=20} per conversation —
     * enough for a multi-turn demo without unbounded growth. Production apps swap this for a
     * persistent store (Spring AI ships JDBC / Redis variants).
     */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    /**
     * Single shared {@link ChatClient} pre-configured with the memory advisor. The controller
     * just injects this and calls {@code prompt().user(...).call()} — Spring AI handles the
     * before/after advisor lifecycle that fetches and stores history.
     */
    @Bean
    ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
