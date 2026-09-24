package qa.fanar.core.sadiq;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Domain facade for the {@code /v1/sadiq/*} endpoints — quotation validation and deep research.
 * Returned by {@code FanarClient.sadiq()}.
 *
 * <p>Validation comes in two variants — same call, different waiting strategies:</p>
 * <ul>
 *   <li>{@link #validate(SadiqValidationRequest)} — blocking. On Java 21 virtual threads the block
 *       doesn't pin a carrier, so this is the right choice for most code.</li>
 *   <li>{@link #validateAsync(SadiqValidationRequest)} — returns a {@link CompletableFuture}. Thin
 *       sugar over the sync path on a virtual thread.</li>
 * </ul>
 *
 * <p>Deep research is a single long-lived HTTP call that <strong>takes minutes</strong> (see
 * {@link DeepResearchDepth}) and draws on a small daily quota that the server consumes the
 * moment it admits the request, so the SDK never retries it — a failed run surfaces at once,
 * and whether to spend another unit is the caller's decision (ADR-031). Three variants:</p>
 * <ul>
 *   <li>{@link #deepResearchStream(DeepResearchRequest)} — the stream the server recommends:
 *       a progress event per research pass, the draft as token deltas, then the finished report
 *       as a {@link ReportChunk}, then the terminal event. Cancel the subscription to abandon a
 *       run.</li>
 *   <li>{@link #deepResearch(DeepResearchRequest)} — blocking; collects the report from that
 *       same stream and discards the progress and draft events. Interrupting the waiting thread
 *       abandons the run.</li>
 *   <li>{@link #deepResearchAsync(DeepResearchRequest)} — the blocking variant on a virtual
 *       thread; cancelling the future abandons the run.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code SadiqClient} instance backs every call on a
 * given {@code FanarClient}.</p>
 *
 * @author Oussama Mahjoub
 */
public interface SadiqClient {

    /**
     * Verify the Qur'anic verses and hadith quoted inside a block of text.
     *
     * @param request the text to check plus the model to check it with; must not be {@code null}
     * @return the same text with verified quotations tagged and referenced
     */
    SadiqValidationResponse validate(SadiqValidationRequest request);

    /**
     * Same as {@link #validate} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<SadiqValidationResponse> validateAsync(SadiqValidationRequest request);

    /**
     * Research a topic and return the finished report. Blocks for the whole run — minutes, not
     * seconds — on the calling thread; the result is the report the server streams near the end
     * of the run, so it is exactly what {@link #deepResearchStream} delivers as a
     * {@link ReportChunk}.
     *
     * <p>The client's request timeout bounds only the wait for the server to admit the run (its
     * response headers); the run itself is not bounded. A received report is the result: once the
     * server has sent it, a later failure of the stream — a dropped connection, an error event —
     * does not fail the call (a transport failure is recorded on the call's observation; an error
     * event after the report is not). Only when no report arrived does the call fail: with the
     * typed {@code FanarException} of an in-stream error envelope, or with a
     * {@code FanarTransportException} for an error event, a transport failure, a stream that ends
     * without a report, or an interrupt of the calling thread (which also cancels the run). Never
     * retried, whatever the client's retry policy.</p>
     *
     * @param request the topic, the model, and optionally the depth and web-search switch; must
     *                not be {@code null}
     * @return the finished report
     */
    DeepResearchReport deepResearch(DeepResearchRequest request);

    /**
     * Same as {@link #deepResearch} but asynchronous, on a virtual thread. The future completes
     * with the report or exceptionally with a subtype of {@code FanarException}; cancelling it
     * cancels the run and closes the connection.
     */
    CompletableFuture<DeepResearchReport> deepResearchAsync(DeepResearchRequest request);

    /**
     * Research a topic as a stream: a {@code ProgressChunk} as each research pass completes, the
     * draft as {@code TokenChunk}s, the finished report as a {@link ReportChunk}, then the
     * terminal {@code DoneChunk} — the frame that ends the run, whose {@code metadata} summarises
     * it when the server sends any. Render the report, not the concatenated deltas. Errors mid-run
     * arrive as an {@code ErrorChunk}, or — for a run the server abandons after admitting it — as
     * {@code onError} with the typed {@code FanarException} of the error envelope it streams;
     * transport failures as {@code onError} too.
     *
     * <p>The request is sent when this method returns — subscribe exactly once, and cancel the
     * subscription to abandon the run. Never retried, whatever the client's retry policy.</p>
     *
     * @param request the topic, the model, and optionally the depth and web-search switch; must
     *                not be {@code null}
     * @return a single-subscriber publisher over the run's events
     */
    Flow.Publisher<DeepResearchEvent> deepResearchStream(DeepResearchRequest request);
}
