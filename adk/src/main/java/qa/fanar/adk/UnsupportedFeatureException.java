package qa.fanar.adk;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thrown, through the model's {@code Flowable}, when a request carries features Fanar cannot
 * honour and the model's {@link UnsupportedFeaturePolicy} is {@link UnsupportedFeaturePolicy#REJECT},
 * or when dropping them under {@link UnsupportedFeaturePolicy#IGNORE} would leave nothing to send.
 *
 * <p>This is deliberately <em>not</em> a {@code qa.fanar.core.FanarException}: that hierarchy is
 * sealed and describes errors that came back over the wire, whereas this request was refused
 * before being sent. Core signals pre-wire problems with plain JDK exceptions; this type is
 * rooted in {@link UnsupportedOperationException} because the request is well-formed but asks
 * for a capability this model does not have, and it stays catchable on its own.</p>
 */
public final class UnsupportedFeatureException extends UnsupportedOperationException {

    private static final long serialVersionUID = 2L;

    private final List<UnsupportedFeature> features;
    private final UnsupportedFeaturePolicy policy;

    /**
     * @param features the items Fanar cannot honour, in the order they were found; must not be
     *                 {@code null} or empty
     * @param policy   the policy in force when the request was refused: under {@code REJECT} the
     *                 message points at {@code IGNORE}; under {@code IGNORE} it says that nothing
     *                 sendable remained once the items were dropped
     */
    public UnsupportedFeatureException(List<UnsupportedFeature> features, UnsupportedFeaturePolicy policy) {
        super(message(features, Objects.requireNonNull(policy, "policy")));
        this.features = List.copyOf(features);
        this.policy = policy;
    }

    private static String message(List<UnsupportedFeature> features, UnsupportedFeaturePolicy policy) {
        Objects.requireNonNull(features, "features");
        if (features.isEmpty()) {
            throw new IllegalArgumentException("features must not be empty");
        }
        String items = features.stream().map(UnsupportedFeature::detail).collect(Collectors.joining(", "));
        return policy == UnsupportedFeaturePolicy.REJECT
                ? "Fanar cannot honour " + items
                        + " - set UnsupportedFeaturePolicy.IGNORE on FanarLlmOptions to drop them and send the rest"
                : "Fanar cannot honour " + items + " - and nothing sendable remains once they are dropped";
    }

    /** The items Fanar cannot honour, as named in the message. Never empty. */
    public List<UnsupportedFeature> features() {
        return features;
    }

    /** The policy in force when the request was refused. */
    public UnsupportedFeaturePolicy policy() {
        return policy;
    }
}
