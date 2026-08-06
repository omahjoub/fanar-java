package qa.fanar.spring.ai;

import java.util.Objects;

import org.springframework.ai.image.ImageGenerationMetadata;

/**
 * Per-image generation metadata from Fanar: whether the prompt was auto-revised for style,
 * quality, and cultural alignment, and the prompt actually used for generation.
 *
 * <p>Retrieve via {@code imageResponse.getResult().getMetadata()} and narrow with
 * {@code instanceof FanarImageGenerationMetadata} — the same access pattern other Spring AI
 * providers use for their revised-prompt metadata.</p>
 *
 * @param revised       whether Fanar revised the prompt before generation
 * @param revisedPrompt the prompt used for generation — equal to the request prompt when
 *                      {@code revised} is {@code false}
 *
 * @author Oussama Mahjoub
 */
public record FanarImageGenerationMetadata(boolean revised, String revisedPrompt)
        implements ImageGenerationMetadata {

    public FanarImageGenerationMetadata {
        Objects.requireNonNull(revisedPrompt, "revisedPrompt");
    }
}
