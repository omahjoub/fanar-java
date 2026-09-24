package qa.fanar.core.sadiq;

import java.util.List;

/**
 * One section of a {@link DeepResearchReport} — a heading in both languages, the section's
 * markdown content, the sources consulted and cited for it, and any sub-sections.
 *
 * <p>The spec declares no field of this schema required, so every scalar may be {@code null};
 * the lists are never {@code null} and are defensively copied.</p>
 *
 * @param heading      the section heading; may be {@code null}
 * @param headingAr    the Arabic heading; may be {@code null}
 * @param content      the section body, markdown; may be {@code null}
 * @param mode         the research mode that produced the section (e.g. {@code general}); may be
 *                     {@code null}
 * @param sources      sources consulted for the section; empty when absent
 * @param citedSources sources the section text cites; empty when absent
 * @param degraded     whether the server produced the section with reduced coverage; may be
 *                     {@code null}
 * @param children     sub-sections, recursively; empty when absent
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchSection(
        String heading,
        String headingAr,
        String content,
        String mode,
        List<DeepResearchSource> sources,
        List<DeepResearchSource> citedSources,
        Boolean degraded,
        List<DeepResearchSection> children
) {

    public DeepResearchSection {
        sources = sources == null ? List.of() : List.copyOf(sources);
        citedSources = citedSources == null ? List.of() : List.copyOf(citedSources);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
