package qa.fanar.sample.spring.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import qa.fanar.core.FanarClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: boot the sample context with a stub API key and confirm the starter wired the
 * full stack — Fanar SDK client, Spring AI model beans, plus the sample's own ChatClient and
 * ChatMemory beans. No network calls; the controllers don't fire.
 */
@SpringBootTest(properties = "fanar.api-key=test-key")
class SpringAiSampleApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextBootsAndAllBeansWired() {
        // From the SB4 starter:
        assertThat(context.getBeansOfType(FanarClient.class)).hasSize(1);

        // From the Spring AI starter:
        assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(ImageModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(TextToSpeechModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(TranscriptionModel.class)).hasSize(1);

        // From the sample's BeansConfig:
        assertThat(context.getBeansOfType(ChatClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatMemory.class)).hasSize(1);

        // Controllers:
        assertThat(context.getBeansOfType(ChatController.class)).hasSize(1);
        assertThat(context.getBeansOfType(MediaController.class)).hasSize(1);
    }
}
