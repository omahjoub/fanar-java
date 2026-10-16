package qa.fanar.sample.adk.studio;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Part;
import qa.fanar.core.FanarClient;
import qa.fanar.core.images.ImageGenerationItem;
import qa.fanar.core.images.ImageGenerationRequest;
import qa.fanar.core.images.ImageModel;

/** Asks Fanar-Oryx-IG-2 for a cover from the editor's brief; the PNG becomes an artifact and an inline image. */
final class CoverArtist extends FanarStep {

    CoverArtist(Supplier<FanarClient> client) {
        super("cover_artist", "Illustrates the cover with Fanar-Oryx-IG-2", client);
    }

    @Override
    protected Event produce(InvocationContext ctx) {
        String prompt = line(state(ctx, "cover_brief", ""), "COVER:")
                .orElse("A warm children's book cover illustration for an Arabic short story about: " + idea(ctx));

        ImageGenerationItem image = client().images()
                .generate(ImageGenerationRequest.of(ImageModel.FANAR_ORYX_IG_2, prompt))
                .data().getFirst();
        byte[] png = Base64.getDecoder().decode(image.b64Json());
        int version = save(ctx, "cover.png", Part.fromBytes(png, "image/png"));

        String note = "🎨 Fanar-Oryx-IG-2 painted the cover (" + png.length / 1024 + " KB, artifact cover.png v" + version + ")."
                + "\nBrief: " + prompt
                + (image.revised() ? "\nFanar revised it to: " + image.revisedPrompt() : "");
        return event(ctx, note, List.of(Part.fromBytes(png, "image/png")),
                Map.of("cover_prompt", prompt), Map.of("cover.png", version));
    }
}
