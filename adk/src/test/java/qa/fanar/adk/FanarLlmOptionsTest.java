package qa.fanar.adk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FanarLlmOptionsTest {

    @Test
    void defaultsRejectAndSetNoKnob() {
        FanarLlmOptions o = FanarLlmOptions.defaults();

        assertEquals(UnsupportedFeaturePolicy.REJECT, o.unsupportedFeatures());
        assertNull(o.persona());
        assertNull(o.madhab());
        assertNull(o.logitBias());
        assertNull(o.stopTokenIds());
        assertEquals(UnsupportedFeaturePolicy.IGNORE, UnsupportedFeaturePolicy.valueOf("IGNORE"));
        assertEquals(2, UnsupportedFeaturePolicy.values().length);
    }

    @Test
    void builderSetsEveryKnobAndCopiesCollections() {
        List<Madhab> madhab = new ArrayList<>(List.of(Madhab.HANAFI));
        Map<String, Double> bias = new HashMap<>(Map.of("42", 1.5));
        FanarLlmOptions o = FanarLlmOptions.builder()
                .unsupportedFeatures(UnsupportedFeaturePolicy.IGNORE)
                .persona("scholar")
                .madhab(madhab)
                .enableThinking(true)
                .restrictToIslamic(false)
                .bookNames(List.of(BookName.of("Sahih al-Bukhari")))
                .preferredSources(List.of(Source.of("quran")))
                .excludeSources(List.of(Source.of("web")))
                .filterSources(List.of(Source.of("hadith")))
                .logitBias(bias)
                .minP(0.1)
                .repetitionPenalty(1.1)
                .bestOf(2)
                .lengthPenalty(0.9)
                .earlyStopping(true)
                .stopTokenIds(List.of(7))
                .ignoreEos(false)
                .minTokens(1)
                .skipSpecialTokens(true)
                .spacesBetweenSpecialTokens(false)
                .truncatePromptTokens(100)
                .promptLogprobs(2)
                .build();
        madhab.add(Madhab.MALIKI);
        bias.put("43", 2.0);

        assertEquals(UnsupportedFeaturePolicy.IGNORE, o.unsupportedFeatures());
        assertEquals("scholar", o.persona());
        assertEquals(List.of(Madhab.HANAFI), o.madhab(), "defensively copied");
        assertEquals(Map.of("42", 1.5), o.logitBias(), "defensively copied");
        assertEquals(true, o.enableThinking());
        assertEquals(false, o.restrictToIslamic());
        assertEquals(List.of(BookName.of("Sahih al-Bukhari")), o.bookNames());
        assertEquals(List.of(Source.of("quran")), o.preferredSources());
        assertEquals(List.of(Source.of("web")), o.excludeSources());
        assertEquals(List.of(Source.of("hadith")), o.filterSources());
        assertEquals(0.1, o.minP());
        assertEquals(1.1, o.repetitionPenalty());
        assertEquals(2, o.bestOf());
        assertEquals(0.9, o.lengthPenalty());
        assertEquals(true, o.earlyStopping());
        assertEquals(List.of(7), o.stopTokenIds());
        assertEquals(false, o.ignoreEos());
        assertEquals(1, o.minTokens());
        assertEquals(true, o.skipSpecialTokens());
        assertEquals(false, o.spacesBetweenSpecialTokens());
        assertEquals(100, o.truncatePromptTokens());
        assertEquals(2, o.promptLogprobs());
    }

    @Test
    void policyIsRequired() {
        assertThrows(NullPointerException.class, () -> FanarLlmOptions.builder().unsupportedFeatures(null).build());
    }

    @Test
    void toBuilderCarriesEveryKnobIntoAVariant() {
        FanarLlmOptions base = FanarLlmOptions.builder()
                .unsupportedFeatures(UnsupportedFeaturePolicy.IGNORE).persona("p").madhab(List.of(Madhab.HANAFI))
                .enableThinking(true).restrictToIslamic(true).bookNames(List.of(BookName.of("b")))
                .preferredSources(List.of(Source.of("q"))).excludeSources(List.of(Source.of("w")))
                .filterSources(List.of(Source.of("h"))).logitBias(Map.of("1", 1.0)).minP(0.1).repetitionPenalty(1.1)
                .bestOf(2).lengthPenalty(0.9).earlyStopping(true).stopTokenIds(List.of(7)).ignoreEos(false)
                .minTokens(1).skipSpecialTokens(true).spacesBetweenSpecialTokens(false).truncatePromptTokens(100)
                .promptLogprobs(2).build();

        FanarLlmOptions variant = base.toBuilder().persona("other").build();

        assertEquals("other", variant.persona());
        assertEquals(UnsupportedFeaturePolicy.IGNORE, variant.unsupportedFeatures());
        assertEquals(List.of(Madhab.HANAFI), variant.madhab());
        assertEquals(true, variant.enableThinking());
        assertEquals(true, variant.restrictToIslamic());
        assertEquals(List.of(BookName.of("b")), variant.bookNames());
        assertEquals(List.of(Source.of("q")), variant.preferredSources());
        assertEquals(List.of(Source.of("w")), variant.excludeSources());
        assertEquals(List.of(Source.of("h")), variant.filterSources());
        assertEquals(Map.of("1", 1.0), variant.logitBias());
        assertEquals(0.1, variant.minP());
        assertEquals(1.1, variant.repetitionPenalty());
        assertEquals(2, variant.bestOf());
        assertEquals(0.9, variant.lengthPenalty());
        assertEquals(true, variant.earlyStopping());
        assertEquals(List.of(7), variant.stopTokenIds());
        assertEquals(false, variant.ignoreEos());
        assertEquals(1, variant.minTokens());
        assertEquals(true, variant.skipSpecialTokens());
        assertEquals(false, variant.spacesBetweenSpecialTokens());
        assertEquals(100, variant.truncatePromptTokens());
        assertEquals(2, variant.promptLogprobs());
        assertEquals("p", base.persona(), "the base value is untouched");
    }
}
