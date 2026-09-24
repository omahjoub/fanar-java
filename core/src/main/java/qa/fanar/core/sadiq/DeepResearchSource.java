package qa.fanar.core.sadiq;

/**
 * One source a deep-research report drew on — a URL and the citation tag the report text uses
 * for it, with the passage that was retrieved.
 *
 * <p>The spec types {@code sources} and {@code cited_sources} as untyped arrays; this shape is the
 * one every example in the spec and its code samples show ({@code source}, {@code citation_tag},
 * {@code quote}, {@code was_cited}), recorded as a spec claim in the wire-observations ledger until
 * a live report confirms it. Every field is nullable for that reason.</p>
 *
 * @param source      the source URL; may be {@code null}
 * @param citationTag the tag the report uses to cite it, e.g. {@code [إسلام ويب:228949]}; may
 *                    be {@code null}
 * @param quote       the passage the report drew on; may be {@code null}
 * @param wasCited    whether the report text actually cites the source; may be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchSource(String source, String citationTag, String quote, Boolean wasCited) {
}
