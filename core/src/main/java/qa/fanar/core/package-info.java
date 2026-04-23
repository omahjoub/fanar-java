/**
 * Public API of the Fanar Java core SDK.
 *
 * <p>The entry point is {@code FanarClient}, built via {@code FanarClient.builder()}. Request and response
 * types are grouped by Fanar domain in the subpackages {@code chat}, {@code audio}, {@code images},
 * {@code translations}, {@code poems}, {@code moderation}, {@code tokens}, and {@code models}.</p>
 *
 * <p>Extension interfaces (JSON codec, interceptor, observability) live in the {@code spi} subpackage.
 * Implementation details live in {@code internal} and are not exported.</p>
 */
package qa.fanar.core;
