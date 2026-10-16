package qa.fanar.sample.adk.studio;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import qa.fanar.core.FanarClient;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationRequest;

/** Renders the story in English with Fanar-Shaheen-MT-1, for the back cover. */
final class Translator extends FanarStep {

    Translator(Supplier<FanarClient> client) {
        super("translator", "Translates the story with Fanar-Shaheen-MT-1", client);
    }

    @Override
    protected Event produce(InvocationContext ctx) {
        String english = client().translations()
                .translate(TranslationRequest.of(TranslationModel.FANAR_SHAHEEN_MT_1, state(ctx, "story", idea(ctx)),
                        LanguagePair.AR_EN))
                .text();
        return event(ctx, "🌐 Fanar-Shaheen-MT-1, back-cover English:\n\n" + english, List.of(),
                Map.of("story_en", english), Map.of());
    }
}
