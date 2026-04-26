package qa.fanar.core.chat;

/**
 * One event in a streaming chat-completion response.
 *
 * <p>Sealed hierarchy over the six chunk shapes Fanar emits on its SSE stream. Consumers of
 * {@code Flow.Publisher<StreamEvent>} pattern-match exhaustively over the variants:</p>
 *
 * <pre>{@code
 * switch (event) {
 *     case TokenChunk      t  -> ui.appendToken(t.choices().get(0).content());
 *     case ToolCallChunk   c  -> ...
 *     case ToolResultChunk r  -> ...
 *     case ProgressChunk   p  -> ui.showProgress(p.message().en());
 *     case DoneChunk       d  -> ...
 *     case ErrorChunk      e  -> ...
 * }
 * }</pre>
 *
 * <p>Every event carries the same three top-level metadata fields: a completion {@link #id},
 * the server-side {@link #created} timestamp, and the {@link #model} used. The wire-protocol
 * field {@code object} (always {@code "chat.completion.chunk"}) is not modelled — the JSON
 * codec sets it on serialize.</p>
 *
 * @author Oussama Mahjoub
 */
public sealed interface StreamEvent
        permits TokenChunk, ToolCallChunk, ToolResultChunk, ProgressChunk, DoneChunk, ErrorChunk {

    /** Completion id — stable across all events of the same streaming response. */
    String id();

    /** Server-side creation timestamp, Unix seconds. */
    long created();

    /** Wire-format model id the server used. */
    String model();
}
