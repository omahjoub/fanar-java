package qa.fanar.sample.adk.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarException;

/**
 * A pipeline step that calls one of Fanar's non-chat models through the SDK facade and emits one
 * ADK event. Fanar cannot be handed tools, so the image, moderation, translation and speech
 * models are reached from custom agents like this one instead of from the chat model's tool
 * loop — the "workflow step" recipe of ADR-030. An error the Fanar API answers with (a
 * {@link FanarException}, a gated model for instance) becomes a visible event rather than a failed
 * run, so the rest of the studio still finishes; a missing API key is not one, and fails the run.
 */
abstract class FanarStep extends BaseAgent {

    private final Supplier<FanarClient> client;

    FanarStep(String name, String description, Supplier<FanarClient> client) {
        super(name, description, null, null, null);
        this.client = client;
    }

    protected FanarClient client() {
        return client.get();
    }

    /** Produce this step's single event; may throw {@link FanarException}. */
    protected abstract Event produce(InvocationContext ctx) throws Exception;

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.fromCallable(() -> {
            try {
                return produce(ctx);
            } catch (FanarException e) {
                return event(ctx, "⚠ " + name() + " skipped — " + e.getClass().getSimpleName()
                        + " (HTTP " + e.httpStatus() + "): " + e.getMessage(), List.of(), Map.of(), Map.of());
            }
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return runAsyncImpl(ctx);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** The user's idea: the text of the message that started this invocation. */
    protected static String idea(InvocationContext ctx) {
        return ctx.userContent().map(Content::text).filter(t -> !t.isBlank()).orElse("قصة قصيرة");
    }

    /** A value an earlier step left in session state (an LlmAgent's {@code outputKey}, or a delta). */
    protected static String state(InvocationContext ctx, String key, String fallback) {
        Object value = ctx.session().state().get(key);
        return value == null ? fallback : value.toString();
    }

    /** The remainder of the first line starting with {@code prefix} (case-insensitive), if any. */
    protected static Optional<String> line(String text, String prefix) {
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String rest = trimmed.substring(prefix.length()).strip();
                if (!rest.isEmpty()) {
                    return Optional.of(rest);
                }
            }
        }
        return Optional.empty();
    }

    protected int save(InvocationContext ctx, String filename, Part part) {
        return ctx.artifactService()
                .saveArtifact(ctx.appName(), ctx.userId(), ctx.session().id(), filename, part)
                .blockingGet();
    }

    protected Event event(InvocationContext ctx, String text, List<Part> extraParts,
                          Map<String, Object> stateDelta, Map<String, Integer> artifactDelta) {
        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText(text));
        parts.addAll(extraParts);
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId(ctx.invocationId())
                .author(name())
                .branch(ctx.branch().orElse(null))
                .timestamp(System.currentTimeMillis())
                .content(Content.builder().role("model").parts(parts).build())
                .actions(EventActions.builder()
                        .stateDelta(new ConcurrentHashMap<>(stateDelta))
                        .artifactDelta(new ConcurrentHashMap<>(artifactDelta))
                        .build())
                .build();
    }
}
