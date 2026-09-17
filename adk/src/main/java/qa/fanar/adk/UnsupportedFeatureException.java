package qa.fanar.adk;

import java.util.List;
import java.util.Objects;

/**
 * Thrown, through the model's {@code Flowable}, when a request carries features Fanar cannot
 * honour and the model's {@link UnsupportedFeaturePolicy} is {@link UnsupportedFeaturePolicy#REJECT}.
 *
 * <p>This is deliberately <em>not</em> a {@code qa.fanar.core.FanarException}: that hierarchy is
 * sealed and describes errors that came back over the wire, whereas this request was refused
 * before being sent. Core signals pre-wire misuse with plain JDK exceptions, and this type follows
 * that convention while staying catchable on its own.</p>
 */
public final class UnsupportedFeatureException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    private final List<String> features;

    /**
     * @param features the items Fanar cannot honour, in the order they were found; must not be
     *                 {@code null} or empty
     */
    public UnsupportedFeatureException(List<String> features) {
        super(message(features));
        this.features = List.copyOf(features);
    }

    private static String message(List<String> features) {
        Objects.requireNonNull(features, "features");
        if (features.isEmpty()) {
            throw new IllegalArgumentException("features must not be empty");
        }
        return "Fanar cannot honour " + String.join(", ", features)
                + " - set UnsupportedFeaturePolicy.IGNORE on FanarLlmOptions to drop them and send the rest";
    }

    /** The items Fanar cannot honour, as named in the message. Never empty. */
    public List<String> features() {
        return features;
    }
}
