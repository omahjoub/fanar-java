package qa.fanar.core.sadiq;

import java.util.Objects;

/**
 * Why a deep-research background job ended {@linkplain JobStatus#INCOMPLETE incomplete}.
 *
 * @param reason the reason; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record IncompleteDetails(IncompleteReason reason) {

    public IncompleteDetails {
        Objects.requireNonNull(reason, "reason");
    }
}
