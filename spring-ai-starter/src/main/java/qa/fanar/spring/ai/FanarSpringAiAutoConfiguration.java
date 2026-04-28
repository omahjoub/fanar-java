package qa.fanar.spring.ai;

import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import qa.fanar.core.FanarClient;
import qa.fanar.spring.boot.v4.FanarAutoConfiguration;

/**
 * Registers Spring AI model beans wrapping the auto-configured {@link FanarClient}.
 *
 * <p>Activates after {@link FanarAutoConfiguration} (so the {@code FanarClient} bean is in
 * scope), and only when Spring AI's {@link ChatModel} interface and a {@code FanarClient} bean
 * are present. Each adapter bean is replaceable independently — supplying any user-defined
 * {@code ChatModel} or {@code ImageModel} bean disables our default for that one slot only.</p>
 *
 * @author Oussama Mahjoub
 */
@AutoConfiguration(after = FanarAutoConfiguration.class)
@ConditionalOnClass(ChatModel.class)
@ConditionalOnBean(FanarClient.class)
public class FanarSpringAiAutoConfiguration {

    /**
     * Default Fanar model used when the {@link org.springframework.ai.chat.prompt.Prompt}'s
     * options don't specify {@code model}. The router model {@code "Fanar"} works for general
     * chat; users can override per-request via Spring AI's {@code ChatOptions}.
     */
    @Bean
    @ConditionalOnMissingBean
    ChatModel fanarSpringAiChatModel(FanarClient fanar) {
        return new FanarChatModel(fanar, qa.fanar.core.chat.ChatModel.FANAR);
    }

    /**
     * Spring AI {@link ImageModel} bean. Defaults to {@code Fanar-Oryx-IG-2}, the only image
     * model Fanar exposes today. Per-request {@link org.springframework.ai.image.ImageOptions}
     * can override.
     */
    @Bean
    @ConditionalOnMissingBean
    ImageModel fanarSpringAiImageModel(FanarClient fanar) {
        return new FanarImageModel(fanar, qa.fanar.core.images.ImageModel.FANAR_ORYX_IG_2);
    }

    /**
     * Spring AI {@link TextToSpeechModel} bean. Defaults to {@code Fanar-Aura-TTS-2} with the
     * built-in {@code Amelia} voice; per-request {@link org.springframework.ai.audio.tts.TextToSpeechOptions}
     * can override both.
     */
    @Bean
    @ConditionalOnMissingBean
    TextToSpeechModel fanarSpringAiTextToSpeechModel(FanarClient fanar) {
        return new FanarTextToSpeechModel(fanar,
                qa.fanar.core.audio.TtsModel.FANAR_AURA_TTS_2,
                qa.fanar.core.audio.Voice.AMELIA);
    }

    /**
     * Spring AI {@link TranscriptionModel} bean. Defaults to {@code Fanar-Aura-STT-1};
     * per-request {@link org.springframework.ai.audio.transcription.AudioTranscriptionOptions}
     * can override.
     */
    @Bean
    @ConditionalOnMissingBean
    TranscriptionModel fanarSpringAiTranscriptionModel(FanarClient fanar) {
        return new FanarTranscriptionModel(fanar, qa.fanar.core.audio.SttModel.FANAR_AURA_STT_1);
    }
}
