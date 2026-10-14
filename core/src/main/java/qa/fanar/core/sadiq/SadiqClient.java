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
 * <p>A deep-research run <strong>takes minutes</strong> (see {@link DeepResearchDepth}) and spends a
 * unit of a small daily quota when the server admits it, so the SDK never retries a call that
 * starts one — a failed start surfaces at once, and whether to spend another unit is the caller's
 * decision (ADR-031). A run is either held on one connection for its whole length — three
 * variants — or started as a background job that outlives the connection, which the spec
 * recommends for runs this long (ADR-032). The synchronous variants:</p>
 * <ul>
 *   <li>{@link #deepResearchStream(DeepResearchRequest)} — the stream: a progress event per
 *       research pass, the draft as token deltas, then the finished report
 *       as a {@link ReportChunk}, then the terminal event. Cancel the subscription to abandon a
 *       run.</li>
 *   <li>{@link #deepResearch(DeepResearchRequest)} — blocking; collects the report from that
 *       same stream and discards the progress and draft events. Interrupting the waiting thread
 *       abandons the run.</li>
 *   <li>{@link #deepResearchAsync(DeepResearchRequest)} — the blocking variant on a virtual
 *       thread; cancelling the future abandons the run.</li>
 * </ul>
 *
 * <p>The background job:</p>
 * <ul>
 *   <li>{@link #startDeepResearch(DeepResearchRequest)} — starts the run and returns its
 *       {@link DeepResearchJob} at once.</li>
 *   <li>{@link #deepResearchJobStream(String)} — follows the job's events, the same ones the
 *       synchronous stream delivers, resuming across dropped and closed connections.</li>
 *   <li>{@link #retrieveDeepResearchJob(String)} — reads the job: its status and, once completed,
 *       its report. Poll until {@link JobStatus#isTerminal()}.</li>
 *   <li>{@link #cancelDeepResearchJob(String)} — asks the server to stop it.</li>
 * </ul>
 *
 * <p>Starting a job is never retried; reading, following and cancelling one spend no quota and use
 * the client's retry policy.</p>
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

    /**
     * Start a deep-research run as a background job and return the job at once — as a rule
     * {@link JobStatus#QUEUED}. The run no longer depends on this connection or this process: follow
     * it with {@link #deepResearchJobStream}, or poll {@link #retrieveDeepResearchJob} until its
     * status {@linkplain JobStatus#isTerminal() is terminal}, and take the report from
     * {@link DeepResearchJob#result()}.
     *
     * <p>Starting a job spends a unit of the endpoint's daily quota, so the call is never retried,
     * whatever the client's retry policy — a retry would start a second job. A call that fails after
     * the server admitted it (a timeout waiting for the response, a dropped connection) can leave a
     * running job whose id the caller never learns, since no endpoint lists jobs; a response that
     * arrives but cannot be decoded names the job's {@code X-Id} header in its
     * {@code FanarTransportException}.</p>
     *
     * @param request the topic, the model, and optionally the depth and web-search switch; must
     *                not be {@code null}
     * @return the new job
     */
    DeepResearchJob startDeepResearch(DeepResearchRequest request);

    /**
     * Same as {@link #startDeepResearch} but asynchronous, on a virtual thread. The future completes
     * with the job or exceptionally with a subtype of {@code FanarException}; cancelling it stops
     * neither the request nor a job the server has already started.
     */
    CompletableFuture<DeepResearchJob> startDeepResearchAsync(DeepResearchRequest request);

    /**
     * Read a background job: where it stands, and once it is {@link JobStatus#COMPLETED}, its
     * report. Reading spends no quota, so the client's retry policy applies. The server keeps a job
     * for 24 hours after it ends; an unknown or expired job, or another key's, surfaces as
     * {@code FanarNotFoundException}.
     *
     * @param jobId the job's id, {@link DeepResearchJob#id()}; must not be {@code null} or blank
     * @return the job as it stands
     */
    DeepResearchJob retrieveDeepResearchJob(String jobId);

    /**
     * Same as {@link #retrieveDeepResearchJob} but asynchronous, on a virtual thread. The future
     * completes with the job or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<DeepResearchJob> retrieveDeepResearchJobAsync(String jobId);

    /**
     * Ask the server to cancel a background job, and return the job as it then stands. Cancellation
     * is asynchronous — the job reads {@link JobStatus#CANCELLED} once it lands, and a job that is
     * finishing may complete anyway — so poll {@link #retrieveDeepResearchJob} for the outcome. A job
     * that already ended is returned unchanged. The unit the job spent is not refunded. Cancelling is
     * idempotent, so the client's retry policy applies.
     *
     * @param jobId the job's id, {@link DeepResearchJob#id()}; must not be {@code null} or blank
     * @return the job after the cancel request
     */
    DeepResearchJob cancelDeepResearchJob(String jobId);

    /**
     * Same as {@link #cancelDeepResearchJob} but asynchronous, on a virtual thread. The future
     * completes with the job or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<DeepResearchJob> cancelDeepResearchJobAsync(String jobId);

    /**
     * Follow a background job's events from its first one, whether it is still running or has
     * ended: the events {@link #deepResearchStream} delivers — progress, the draft as token deltas,
     * the {@link ReportChunk}, the terminal {@code DoneChunk}. The stream completes when the job's
     * events end. The server may close a response before then, and a connection may drop; either
     * way the publisher asks for the events after the last one it delivered, through the client's
     * retry policy, so the subscriber sees each event once. Three responses in a row that end
     * without completing an event fail the stream with {@code FanarTransportException}; a job that
     * is gone by the time the publisher reconnects fails it with {@code FanarNotFoundException}.
     *
     * <p>A job the server restarts after a failure replays its events from the start: render the
     * report, not the concatenated deltas. An error event in the stream is held — events that follow
     * it supersede it — and fails the stream with its typed {@code FanarException} only if the job's
     * events end there. The job is the authoritative outcome: read it with
     * {@link #retrieveDeepResearchJob} after the stream ends to learn whether it completed, failed,
     * was cancelled or was stopped by a content filter.</p>
     *
     * <p>The first request is sent when this method returns, so an unknown job throws from the call;
     * subscribe exactly once. Cancelling the subscription stops following and closes the connection;
     * it does not cancel the job — {@link #cancelDeepResearchJob} does.</p>
     *
     * @param jobId the job's id, {@link DeepResearchJob#id()}; must not be {@code null} or blank
     * @return a single-subscriber publisher over the job's events
     */
    Flow.Publisher<DeepResearchEvent> deepResearchJobStream(String jobId);
}
