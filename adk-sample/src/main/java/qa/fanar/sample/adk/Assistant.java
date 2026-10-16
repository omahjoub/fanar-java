package qa.fanar.sample.adk;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import qa.fanar.adk.FanarLlm;
import qa.fanar.core.chat.ChatModel;

/**
 * The smallest Fanar agent: one {@link LlmAgent} on Fanar's default chat model, one chat call per
 * turn. Start here in the dev UI; {@code story_studio} shows a whole pipeline.
 */
public final class Assistant {

    /** What ADK's dev UI looks for: a public static {@code ROOT_AGENT}. */
    public static final BaseAgent ROOT_AGENT = LlmAgent.builder()
            .name("assistant")
            .description("Answers questions on Fanar's default chat model")
            .model(new FanarLlm(SampleClient.CLIENT, ChatModel.FANAR))
            .instruction("You are a helpful assistant. Answer briefly, in the language the user writes in.")
            .build();

    private Assistant() {
        // ROOT_AGENT only
    }
}
