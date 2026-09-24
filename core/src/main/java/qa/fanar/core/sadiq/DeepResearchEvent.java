package qa.fanar.core.sadiq;

import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.TokenChunk;

/**
 * One event in a streamed deep-research run ({@code SadiqClient.deepResearchStream}).
 *
 * <p>The endpoint streams the chat SSE format, so four of the five event kinds are the chat
 * records themselves — the same {@link TokenChunk}, {@link ProgressChunk}, {@link DoneChunk} and
 * {@link ErrorChunk} a chat stream emits — and the fifth, {@link ReportChunk}, carries the finished
 * report. Deep research never emits chat's tool-call events, and chat never emits a report, which
 * is why each endpoint has its own sealed union over the shared records rather than one union for
 * both (ADR-031). Consumers pattern-match exhaustively:</p>
 *
 * <pre>{@code
 * switch (event) {
 *     case ProgressChunk p -> ui.showProgress(p.message().en());   // one per research pass
 *     case TokenChunk    t -> ui.appendDraft(t.choices().get(0).content());
 *     case ReportChunk   r -> ui.render(r.report());               // the result
 *     case DoneChunk     d -> ui.showRunSummary(d.metadata());
 *     case ErrorChunk    e -> ui.showError(e.choices().get(0).content());
 * }
 * }</pre>
 *
 * @author Oussama Mahjoub
 */
public sealed interface DeepResearchEvent
        permits TokenChunk, ProgressChunk, ReportChunk, DoneChunk, ErrorChunk {

    /** Completion id — stable across all events of the same run. */
    String id();

    /** Server-side creation timestamp, Unix seconds. */
    long created();

    /**
     * Wire-format model id the server used, or {@code null} when the server omitted it — on any
     * of the five event kinds; an informational field never fails a minutes-long run at decode.
     */
    String model();
}
