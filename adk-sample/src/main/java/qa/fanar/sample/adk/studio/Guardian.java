package qa.fanar.sample.adk.studio;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import qa.fanar.core.FanarClient;
import qa.fanar.core.moderations.ModerationModel;
import qa.fanar.core.moderations.SafetyFilterRequest;
import qa.fanar.core.moderations.SafetyFilterResponse;

/** Scores the idea/story pair with Fanar-Guard-2; the verdict goes to state for the publisher. */
final class Guardian extends FanarStep {

    /** The server scores roughly on [0, 5], 5 best; the spec pins no scale, so the bar is ours. */
    private static final double BAR = 3.0;

    Guardian(Supplier<FanarClient> client) {
        super("guardian", "Checks the story with Fanar-Guard-2", client);
    }

    @Override
    protected Event produce(InvocationContext ctx) {
        String story = state(ctx, "story", "");
        SafetyFilterResponse scores = client().moderations()
                .score(SafetyFilterRequest.of(ModerationModel.FANAR_GUARD_2, idea(ctx), story));

        boolean approved = scores.safety() >= BAR && scores.culturalAwareness() >= BAR;
        String verdict = approved ? "approved for publication" : "needs an editor's review";
        String text = String.format("🛡 Fanar-Guard-2 — safety %.2f / 5, cultural awareness %.2f / 5 → %s",
                scores.safety(), scores.culturalAwareness(), verdict);
        return event(ctx, text, List.of(), Map.of("moderation", text), Map.of());
    }
}
