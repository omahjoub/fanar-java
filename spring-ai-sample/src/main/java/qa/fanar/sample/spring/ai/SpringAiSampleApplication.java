package qa.fanar.sample.spring.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable demo for the Fanar Spring AI starter. Exposes Spring AI's higher-level
 * {@code ChatClient} (with memory) plus direct calls to the image / TTS / STT model beans.
 *
 * <p>REST endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/chat}                         — single-turn ChatClient call.</li>
 *   <li>{@code POST /api/chat/{conversationId}}        — multi-turn with MessageChatMemoryAdvisor.</li>
 *   <li>{@code GET  /api/chat/{conversationId}/history} — peek at the conversation memory.</li>
 *   <li>{@code POST /api/chat/stream}                  — server-sent events of token chunks.</li>
 *   <li>{@code POST /api/image}                        — generate an image, return base64.</li>
 *   <li>{@code POST /api/speak}                        — TTS, return audio bytes.</li>
 *   <li>{@code POST /api/transcribe}                   — STT, multipart audio in, text out.</li>
 * </ul>
 *
 * <p>Run with {@code FANAR_API_KEY=… ./mvnw -pl spring-ai-sample spring-boot:run}.</p>
 *
 * @author Oussama Mahjoub
 */
@SpringBootApplication
public class SpringAiSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiSampleApplication.class, args);
    }
}
