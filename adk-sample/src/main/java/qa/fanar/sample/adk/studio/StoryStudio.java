package qa.fanar.sample.adk.studio;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import qa.fanar.adk.FanarLlm;
import qa.fanar.adk.FanarLlmOptions;
import qa.fanar.adk.UnsupportedFeaturePolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.sample.adk.SampleClient;

/**
 * Story Studio — one idea in, an illustrated, checked, translated and narrated Arabic short story
 * out, across six Fanar models and three model-variant chat calls:
 *
 * <pre>
 *   story_studio (SequentialAgent)
 *   ├── storyteller   LlmAgent  fanar/Fanar-C-2-27B   writes the story          → state.story
 *   ├── editor        LlmAgent  fanar/Fanar-C-1-8.7B  title + cover brief       → state.cover_brief
 *   ├── production    ParallelAgent
 *   │   ├── cover_artist   Fanar-Oryx-IG-2      cover.png (artifact + inline image)
 *   │   ├── guardian       Fanar-Guard-2        safety + cultural-awareness verdict → state.moderation
 *   │   ├── translator     Fanar-Shaheen-MT-1   English back cover                 → state.story_en
 *   │   └── narrator       Fanar-Aura-TTS-2     story.mp3 (artifact + inline audio)
 *   └── publisher     LlmAgent  Fanar-S-1-7B (IGNORE)  publisher's note from the state above
 * </pre>
 *
 * <p>Fanar cannot be handed tools, so there is no LLM-driven transfer anywhere: the chat models are
 * workflow steps and the non-chat models are custom agents that call the SDK facades directly
 * (ADR-030). The chat steps resolve their models by name through ADK's registry, registered once
 * below. The publisher runs under {@code UnsupportedFeaturePolicy.IGNORE} because the cover and the
 * narration put inline media into the conversation, which Fanar's chat endpoint cannot carry.</p>
 *
 * <p>Each run spends 3 chat calls, 1 image, 1 moderation, 1 translation and 1 speech call. On the
 * standard key, image and translation allow 20 calls per day and speech 20 per trailing 24 hours;
 * {@code -Dstudio.narrate=false} skips the speech call.</p>
 */
public final class StoryStudio {

    static {
        // Opt-in registry: after this, any agent may name a model as "fanar/<model id>".
        FanarLlm.register(SampleClient.CLIENT, FanarLlmOptions.defaults());
    }

    /** What ADK's dev UI looks for: a public static {@code ROOT_AGENT}. */
    public static final BaseAgent ROOT_AGENT = SequentialAgent.builder()
            .name("story_studio")
            .description("Turns an idea into an illustrated, checked, translated and narrated Arabic short story")
            .subAgents(
                    LlmAgent.builder()
                            .name("storyteller")
                            .description("Writes the story")
                            .model("fanar/Fanar-C-2-27B")
                            .instruction("""
                                    أنت كاتب قصص قصيرة للأطفال باللغة العربية الفصحى.
                                    اكتب قصة قصيرة من الفكرة التي يقدمها المستخدم: بين 120 و180 كلمة، فقرتان أو ثلاث،
                                    بشخصية رئيسية واحدة ونهاية دافئة. اكتب القصة فقط، بلا عنوان وبلا مقدمة أو تعليق.
                                    """)
                            .outputKey("story")
                            .build(),
                    LlmAgent.builder()
                            .name("editor")
                            .description("Titles the story and briefs the illustrator")
                            .model("fanar/Fanar-C-1-8.7B")
                            .instruction("""
                                    You are the editor. The story is:

                                    {story}

                                    Reply with exactly two lines and nothing else:
                                    TITLE: <a short Arabic title for the story>
                                    COVER: <one English sentence describing a children's book cover illustration for it: main character, setting, mood; no text in the image>
                                    """)
                            .outputKey("cover_brief")
                            .build(),
                    ParallelAgent.builder()
                            .name("production")
                            .description("Cover, moderation, translation and narration, all at once")
                            .subAgents(
                                    new CoverArtist(SampleClient.CLIENT),
                                    new Guardian(SampleClient.CLIENT),
                                    new Translator(SampleClient.CLIENT),
                                    new Narrator(SampleClient.CLIENT))
                            .build(),
                    LlmAgent.builder()
                            .name("publisher")
                            .description("Writes the publisher's note")
                            .model(new FanarLlm(SampleClient.CLIENT, ChatModel.FANAR_S_1_7B, FanarLlmOptions.builder()
                                    .unsupportedFeatures(UnsupportedFeaturePolicy.IGNORE)   // inline cover + audio are in the history
                                    .build()))
                            .instruction("""
                                    أنت الناشر. اكتب فقرة قصيرة واحدة بالعربية تقدم الكتاب للقراء:
                                    اذكر العنوان من هذا السطر: {cover_brief}
                                    ثم جملة تشويقية واحدة عن القصة، ثم اذكر بجملة واحدة نتيجة المراجعة: {moderation?}
                                    """)
                            .build())
            .build();

    private StoryStudio() {
        // ROOT_AGENT only
    }
}
