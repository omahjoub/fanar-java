package qa.fanar.core.sadiq;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/sadiq/validate} endpoint. Returned by
 * {@code FanarClient.sadiq()}.
 *
 * <p>Two variants — same call, different waiting strategies:</p>
 * <ul>
 *   <li>{@link #validate(SadiqValidationRequest)} — blocking. On Java 21 virtual threads the block
 *       doesn't pin a carrier, so this is the right choice for most code.</li>
 *   <li>{@link #validateAsync(SadiqValidationRequest)} — returns a {@link CompletableFuture}. Thin
 *       sugar over the sync path on a virtual thread.</li>
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
}
