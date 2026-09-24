package qa.fanar.core.sadiq;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finished deep-research report — a titled overview, hierarchical
 * {@link DeepResearchSection sections}, the sources drawn on, run metadata, and the whole report
 * rendered as markdown.
 *
 * <p>Returned by {@code SadiqClient.deepResearch(...)} and carried by the {@link ReportChunk}
 * of a streamed run. The spec declares no field of this schema required, so every scalar may be
 * {@code null}; the lists and the map are never {@code null} and are defensively copied.</p>
 *
 * <p>{@code metadata} is the server's free-form run summary ({@code additionalProperties: true}
 * in the spec) — the example carries {@code depth}, {@code elapsed_seconds},
 * {@code sections_count}, {@code total_sources}, {@code total_queries}, {@code web_search_used}
 * and similar keys. Values may be {@code null}.</p>
 *
 * @param topic    the topic as the server understood it; may be {@code null}
 * @param language the report language code; may be {@code null}
 * @param title    the report title; may be {@code null}
 * @param titleAr  the Arabic title; may be {@code null}
 * @param overview the introductory summary; may be {@code null}
 * @param sections top-level sections; empty when absent
 * @param sources  every source the report drew on; empty when absent
 * @param metadata the server's run summary; empty when absent
 * @param markdown the whole report rendered as markdown; may be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchReport(
        String topic,
        String language,
        String title,
        String titleAr,
        String overview,
        List<DeepResearchSection> sections,
        List<DeepResearchSource> sources,
        Map<String, Object> metadata,
        String markdown
) {

    public DeepResearchReport {
        sections = sections == null ? List.of() : List.copyOf(sections);
        sources = sources == null ? List.of() : List.copyOf(sources);
        // Not Map.copyOf: the server's summary may carry null values, which that copy rejects.
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
