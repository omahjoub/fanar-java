package qa.fanar.json.jackson2;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import qa.fanar.core.chat.ImagePart;
import qa.fanar.core.chat.RefusalPart;
import qa.fanar.core.chat.TextPart;
import qa.fanar.core.chat.VideoPart;

/**
 * Jackson mix-ins injecting the {@code "type"} discriminator every Fanar content part requires
 * on the wire ({@code text}, {@code image_url}, {@code video_url}, {@code refusal}). The core
 * sealed hierarchies {@link qa.fanar.core.chat.UserContentPart} and
 * {@link qa.fanar.core.chat.AssistantContentPart} stay annotation-free.
 *
 * <p><em>Known gap:</em> {@link ImagePart} and {@link VideoPart} also require their data to be
 * nested under {@code image_url} / {@code video_url} sub-objects on the wire (per the Fanar
 * OpenAPI). The mixins below emit the {@code type} field correctly but do not yet rewrite the
 * body shape — adding that is a future step, handled the day a test actually sends an image or
 * video content part.</p>
 *
 * @author Oussama Mahjoub
 */
final class ContentPartMixIns {

    private ContentPartMixIns() {
        // container for the nested mixin interfaces
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextPart.class,  name = "text"),
            @JsonSubTypes.Type(value = ImagePart.class, name = "image_url"),
            @JsonSubTypes.Type(value = VideoPart.class, name = "video_url"),
    })
    interface UserContentPartMixIn { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextPart.class,    name = "text"),
            @JsonSubTypes.Type(value = RefusalPart.class, name = "refusal"),
    })
    interface AssistantContentPartMixIn { }
}
