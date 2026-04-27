package qa.fanar.spring.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import qa.fanar.spring.boot.v4.FanarAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class FanarSpringAiAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FanarAutoConfiguration.class,
                    FanarSpringAiAutoConfiguration.class));

    @Test
    void allModelBeansRegisteredWhenFanarClientPresent() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ChatModel.class);
                    assertThat(ctx).hasBean("fanarSpringAiChatModel");
                    assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(FanarChatModel.class);

                    assertThat(ctx).hasSingleBean(ImageModel.class);
                    assertThat(ctx).hasBean("fanarSpringAiImageModel");
                    assertThat(ctx.getBean(ImageModel.class)).isInstanceOf(FanarImageModel.class);

                    assertThat(ctx).hasSingleBean(TextToSpeechModel.class);
                    assertThat(ctx).hasBean("fanarSpringAiTextToSpeechModel");
                    assertThat(ctx.getBean(TextToSpeechModel.class)).isInstanceOf(FanarTextToSpeechModel.class);

                    assertThat(ctx).hasSingleBean(TranscriptionModel.class);
                    assertThat(ctx).hasBean("fanarSpringAiTranscriptionModel");
                    assertThat(ctx.getBean(TranscriptionModel.class)).isInstanceOf(FanarTranscriptionModel.class);
                });
    }

    @Test
    void chatModelAbsentWithoutFanarClient() {
        // No api-key → FanarAutoConfiguration is skipped → no FanarClient → @ConditionalOnBean
        // gates the adapter off.
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ChatModel.class));
    }

    @Test
    void chatModelAbsentWhenSpringAiNotOnClasspath() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withClassLoader(new FilteredClassLoader(ChatModel.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FanarChatModel.class));
    }

    @Test
    void userDefinedChatModelWins() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomChatModelConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ChatModel.class);
                    assertThat(ctx.getBean(ChatModel.class)).isSameAs(CustomChatModelConfig.MARKER);
                    assertThat(ctx).doesNotHaveBean(FanarChatModel.class);
                });
    }

    @Test
    void userDefinedImageModelWins() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomImageModelConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ImageModel.class);
                    assertThat(ctx.getBean(ImageModel.class)).isSameAs(CustomImageModelConfig.MARKER);
                    assertThat(ctx).doesNotHaveBean(FanarImageModel.class);
                });
    }

    @Test
    void userDefinedTextToSpeechModelWins() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomTextToSpeechModelConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TextToSpeechModel.class);
                    assertThat(ctx.getBean(TextToSpeechModel.class)).isSameAs(CustomTextToSpeechModelConfig.MARKER);
                    assertThat(ctx).doesNotHaveBean(FanarTextToSpeechModel.class);
                });
    }

    @Test
    void userDefinedTranscriptionModelWins() {
        runner.withPropertyValues("fanar.api-key=test-key")
                .withUserConfiguration(CustomTranscriptionModelConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TranscriptionModel.class);
                    assertThat(ctx.getBean(TranscriptionModel.class)).isSameAs(CustomTranscriptionModelConfig.MARKER);
                    assertThat(ctx).doesNotHaveBean(FanarTranscriptionModel.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomChatModelConfig {
        static final ChatModel MARKER = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
        };

        @Bean
        ChatModel customChatModel() { return MARKER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomImageModelConfig {
        static final ImageModel MARKER = prompt -> { throw new UnsupportedOperationException(); };

        @Bean
        ImageModel customImageModel() { return MARKER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTextToSpeechModelConfig {
        static final TextToSpeechModel MARKER = new TextToSpeechModel() {
            @Override public TextToSpeechResponse call(TextToSpeechPrompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) { throw new UnsupportedOperationException(); }
        };

        @Bean
        TextToSpeechModel customTextToSpeechModel() { return MARKER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTranscriptionModelConfig {
        static final TranscriptionModel MARKER = new TranscriptionModel() {
            @Override public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) { throw new UnsupportedOperationException(); }
        };

        @Bean
        TranscriptionModel customTranscriptionModel() { return MARKER; }
    }
}
