package qa.fanar.sample.adk.studio;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Part;
import qa.fanar.core.FanarClient;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;

/**
 * Reads the opening of the story aloud with Fanar-Aura-TTS-2. Mind the budget: on the standard key
 * that model allows 20 requests per trailing 24 hours, so switch the narration off with
 * {@code -Dstudio.narrate=false} when you need the calls for something else.
 */
final class Narrator extends FanarStep {

    private static final int EXCERPT_CHARS = 400;

    Narrator(Supplier<FanarClient> client) {
        super("narrator", "Reads the opening aloud with Fanar-Aura-TTS-2", client);
    }

    @Override
    protected Event produce(InvocationContext ctx) {
        if (!Boolean.parseBoolean(System.getProperty("studio.narrate", "true"))) {
            return event(ctx, "🔇 narration skipped (-Dstudio.narrate=false)", List.of(), Map.of(), Map.of());
        }
        String story = state(ctx, "story", idea(ctx));
        String excerpt = story.length() <= EXCERPT_CHARS ? story : story.substring(0, EXCERPT_CHARS);

        byte[] mp3 = client().audio().speech(TextToSpeechRequest.builder()
                .model(TtsModel.FANAR_AURA_TTS_2)
                .input(excerpt)
                .voice(Voice.HUDA)
                .responseFormat(TtsResponseFormat.MP3)
                .build());
        int version = save(ctx, "story.mp3", Part.fromBytes(mp3, "audio/mpeg"));

        return event(ctx, "🔊 Fanar-Aura-TTS-2 (voice Huda) read the opening: " + mp3.length / 1024
                        + " KB of MP3, artifact story.mp3 v" + version + ".",
                List.of(Part.fromBytes(mp3, "audio/mpeg")), Map.of(), Map.of("story.mp3", version));
    }
}
