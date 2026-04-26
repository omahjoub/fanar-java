/**
 * Server-Sent Events parser for Fanar's streaming chat-completion endpoint.
 *
 * <p>Implements the strategy in ADR-017: line-oriented accumulation of {@code data:} payloads
 * into frames, shape-based routing of each payload into the right
 * {@link qa.fanar.core.chat.StreamEvent} subtype, and delivery to the caller's
 * {@link java.util.concurrent.Flow.Publisher}. See {@code SseStreamPublisher}.</p>
 *
 * <p>Internal per ADR-018 — nothing in this package is exported.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.core.internal.sse;
