package qa.fanar.spring.ai;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import qa.fanar.core.FanarClient;
import qa.fanar.core.images.ImageGenerationRequest;
import qa.fanar.core.images.ImageGenerationResponse;

/**
 * Spring AI {@link ImageModel} adapter backed by the Fanar Java SDK's image-generation domain.
 *
 * <p>Maps Spring AI's {@link ImagePrompt} (a list of {@link ImageMessage}s + optional
 * {@link ImageOptions}) onto a single Fanar {@link ImageGenerationRequest}. Fanar's endpoint
 * accepts one text prompt per call — when the {@code ImagePrompt} carries multiple
 * {@code ImageMessage}s, their {@code getText()} values are concatenated with newlines and sent
 * as a single prompt.</p>
 *
 * <p>What the adapter does <em>not</em> do:</p>
 * <ul>
 *   <li><b>Width/height/style/quality.</b> Fanar's {@code /v1/images/generations} only takes
 *       {@code model + prompt} — there's no wire field for any of these. Spring AI's options for
 *       them are silently dropped (no exception, since dropping is the standard provider posture
 *       when an upstream doesn't support an option).</li>
 *   <li><b>{@code n} (multi-image).</b> Fanar always returns one image; if the caller requests
 *       {@code n > 1}, only one is returned.</li>
 *   <li><b>URL response format.</b> Fanar always returns {@code b64Json}; we expose only that.</li>
 * </ul>
 *
 * @author Oussama Mahjoub
 */
public final class FanarImageModel implements ImageModel {

    private final FanarClient fanar;
    private final qa.fanar.core.images.ImageModel defaultModel;

    /**
     * Construct an adapter that defaults to the supplied Fanar image model when an
     * {@link ImagePrompt} does not carry an {@link ImageOptions#getModel()} override.
     *
     * @param fanar        the auto-wired SDK client
     * @param defaultModel typed Fanar image model (e.g. {@code ImageModel.FANAR_ORYX_IG_2})
     */
    public FanarImageModel(FanarClient fanar, qa.fanar.core.images.ImageModel defaultModel) {
        this.fanar = Objects.requireNonNull(fanar, "fanar");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
    }

    @Override
    public ImageResponse call(ImagePrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ImageGenerationRequest request = new ImageGenerationRequest(resolveModel(prompt), promptText(prompt));
        ImageGenerationResponse fanarResponse = fanar.images().generate(request);
        return toSpringAiResponse(fanarResponse);
    }

    private qa.fanar.core.images.ImageModel resolveModel(ImagePrompt prompt) {
        ImageOptions options = prompt.getOptions();
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return qa.fanar.core.images.ImageModel.of(options.getModel());
        }
        return defaultModel;
    }

    private static String promptText(ImagePrompt prompt) {
        // Fanar takes a single prompt string. Multi-message prompts are concatenated with
        // newlines so weighting hints from `ImageMessage.getWeight()` (which we'd otherwise
        // have to drop) are at least preserved as ordering.
        List<ImageMessage> messages = prompt.getInstructions();
        if (messages.size() == 1) {
            return messages.getFirst().getText();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(messages.get(i).getText());
        }
        return sb.toString();
    }

    private static ImageResponse toSpringAiResponse(ImageGenerationResponse fanarResponse) {
        List<ImageGeneration> generations = fanarResponse.data().stream()
                .map(item -> new ImageGeneration(new Image(null, item.b64Json())))
                .toList();
        return new ImageResponse(generations);
    }
}
