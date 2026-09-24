package qa.fanar.core.sadiq;

import java.util.Objects;

/**
 * The streaming event that carries the finished {@link DeepResearchReport}. Emitted once, near
 * the end of a {@code POST /v1/sadiq/deep-research} stream, after the token deltas and before the
 * terminal {@code DoneChunk}.
 *
 * <p>Render this, not the concatenated token deltas: the deltas are the prose as it was drafted,
 * the report is the structured, cited result (the spec's own guidance).</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; may be {@code null} — the spec marks it required, but the
 *                same stream's first progress event already arrives without one
 * @param report  the completed report; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ReportChunk(
        String id,
        long created,
        String model,
        DeepResearchReport report
) implements DeepResearchEvent {

    public ReportChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(report, "report");
    }
}
