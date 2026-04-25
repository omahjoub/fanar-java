package qa.fanar.json.jackson3;

import java.util.List;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import qa.fanar.core.chat.AssistantContentPart;
import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.UserContentPart;

/**
 * Mix-ins that attach {@link ContentListSerializer} to the {@code content} accessor on each
 * request-side message variant that carries a list of content parts. Wired via
 * {@code addMixIn(UserMessage.class, MessageContentMixIns.UserMessageMixIn.class)} etc.
 */
final class MessageContentMixIns {

    private MessageContentMixIns() {
        // container for the nested mix-in interfaces
    }

    interface UserMessageMixIn {
        @JsonSerialize(using = ContentListSerializer.class)
        List<UserContentPart> content();
    }

    interface AssistantMessageMixIn {
        @JsonSerialize(using = ContentListSerializer.class)
        List<AssistantContentPart> content();
    }

    interface ChatMessageMixIn {
        @JsonDeserialize(using = ResponseContentListDeserializer.class)
        List<ResponseContent> content();
    }
}
