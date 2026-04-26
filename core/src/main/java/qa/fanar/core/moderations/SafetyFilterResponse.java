package qa.fanar.core.moderations;

/**
 * Scores returned from {@code POST /v1/moderations} — two floating-point measures of the
 * prompt/response pair's safety profile.
 *
 * <p>Higher means safer / more culturally aware. Empirically the server emits values in
 * roughly the {@code [0, 5]} range, with {@code 5} the best score; the spec does not pin the
 * scale, so treat both as raw measurements and apply your own thresholds at the call site.</p>
 *
 * @param safety            general safety score covering toxicity, violence, self-harm,
 *                          and similar dimensions
 * @param culturalAwareness score for cultural-awareness dimensions including stereotypes,
 *                          insensitive content, and bias
 * @param id                request-correlation identifier — undocumented in the OpenAPI spec
 *                          but consistently emitted by the live server. {@code null} when
 *                          the server omits it (e.g. older deployments).
 *
 * @author Oussama Mahjoub
 */
public record SafetyFilterResponse(double safety, double culturalAwareness, String id) {
}
