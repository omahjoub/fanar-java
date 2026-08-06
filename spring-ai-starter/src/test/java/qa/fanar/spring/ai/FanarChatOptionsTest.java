package qa.fanar.spring.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.Source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanarChatOptionsTest {

    private static final BookName BOOK = BookName.KNOWN.iterator().next();

    @Test
    void builderRoundtripsAllFields() {
        FanarChatOptions o = FanarChatOptions.builder()
                .model("Fanar-Sadiq-2")
                .temperature(0.3)
                .topP(0.9)
                .topK(40)
                .maxTokens(64)
                .frequencyPenalty(0.1)
                .presencePenalty(0.2)
                .stopSequences(List.of("END"))
                .persona("Warm, patient teacher")
                .madhab(List.of(Madhab.HANAFI))
                .enableThinking(true)
                .restrictToIslamic(true)
                .bookNames(List.of(BOOK))
                .preferredSources(List.of(Source.QURAN))
                .excludeSources(List.of(Source.DORAR))
                .filterSources(List.of(Source.TAFSIR))
                .logitBias(Map.of("50256", -100.0))
                .logprobs(true)
                .topLogprobs(5)
                .n(2)
                .minP(0.05)
                .repetitionPenalty(1.1)
                .bestOf(3)
                .lengthPenalty(1.2)
                .earlyStopping(true)
                .stopTokenIds(List.of(50256))
                .ignoreEos(false)
                .minTokens(8)
                .skipSpecialTokens(true)
                .spacesBetweenSpecialTokens(false)
                .truncatePromptTokens(2048)
                .promptLogprobs(1)
                .build();

        assertThat(o.getModel()).isEqualTo("Fanar-Sadiq-2");
        assertThat(o.getTemperature()).isEqualTo(0.3);
        assertThat(o.getTopP()).isEqualTo(0.9);
        assertThat(o.getTopK()).isEqualTo(40);
        assertThat(o.getMaxTokens()).isEqualTo(64);
        assertThat(o.getFrequencyPenalty()).isEqualTo(0.1);
        assertThat(o.getPresencePenalty()).isEqualTo(0.2);
        assertThat(o.getStopSequences()).containsExactly("END");
        assertThat(o.getPersona()).isEqualTo("Warm, patient teacher");
        assertThat(o.getMadhab()).containsExactly(Madhab.HANAFI);
        assertThat(o.getEnableThinking()).isTrue();
        assertThat(o.getRestrictToIslamic()).isTrue();
        assertThat(o.getBookNames()).containsExactly(BOOK);
        assertThat(o.getPreferredSources()).containsExactly(Source.QURAN);
        assertThat(o.getExcludeSources()).containsExactly(Source.DORAR);
        assertThat(o.getFilterSources()).containsExactly(Source.TAFSIR);
        assertThat(o.getLogitBias()).containsEntry("50256", -100.0);
        assertThat(o.getLogprobs()).isTrue();
        assertThat(o.getTopLogprobs()).isEqualTo(5);
        assertThat(o.getN()).isEqualTo(2);
        assertThat(o.getMinP()).isEqualTo(0.05);
        assertThat(o.getRepetitionPenalty()).isEqualTo(1.1);
        assertThat(o.getBestOf()).isEqualTo(3);
        assertThat(o.getLengthPenalty()).isEqualTo(1.2);
        assertThat(o.getEarlyStopping()).isTrue();
        assertThat(o.getStopTokenIds()).containsExactly(50256);
        assertThat(o.getIgnoreEos()).isFalse();
        assertThat(o.getMinTokens()).isEqualTo(8);
        assertThat(o.getSkipSpecialTokens()).isTrue();
        assertThat(o.getSpacesBetweenSpecialTokens()).isFalse();
        assertThat(o.getTruncatePromptTokens()).isEqualTo(2048);
        assertThat(o.getPromptLogprobs()).isEqualTo(1);
    }

    @Test
    void unsetFieldsStayNull() {
        FanarChatOptions o = FanarChatOptions.builder().build();
        assertThat(o.getModel()).isNull();
        assertThat(o.getTemperature()).isNull();
        assertThat(o.getTopP()).isNull();
        assertThat(o.getTopK()).isNull();
        assertThat(o.getMaxTokens()).isNull();
        assertThat(o.getFrequencyPenalty()).isNull();
        assertThat(o.getPresencePenalty()).isNull();
        assertThat(o.getStopSequences()).isNull();
        assertThat(o.getPersona()).isNull();
        assertThat(o.getMadhab()).isNull();
        assertThat(o.getEnableThinking()).isNull();
        assertThat(o.getRestrictToIslamic()).isNull();
        assertThat(o.getBookNames()).isNull();
        assertThat(o.getPreferredSources()).isNull();
        assertThat(o.getExcludeSources()).isNull();
        assertThat(o.getFilterSources()).isNull();
        assertThat(o.getLogitBias()).isNull();
        assertThat(o.getLogprobs()).isNull();
        assertThat(o.getTopLogprobs()).isNull();
        assertThat(o.getN()).isNull();
        assertThat(o.getMinP()).isNull();
        assertThat(o.getRepetitionPenalty()).isNull();
        assertThat(o.getBestOf()).isNull();
        assertThat(o.getLengthPenalty()).isNull();
        assertThat(o.getEarlyStopping()).isNull();
        assertThat(o.getStopTokenIds()).isNull();
        assertThat(o.getIgnoreEos()).isNull();
        assertThat(o.getMinTokens()).isNull();
        assertThat(o.getSkipSpecialTokens()).isNull();
        assertThat(o.getSpacesBetweenSpecialTokens()).isNull();
        assertThat(o.getTruncatePromptTokens()).isNull();
        assertThat(o.getPromptLogprobs()).isNull();
    }

    @Test
    void collectionsAreDefensivelyCopiedAndUnmodifiable() {
        List<String> stop = new ArrayList<>(List.of("END"));
        List<Madhab> madhab = new ArrayList<>(List.of(Madhab.MALIKI));
        List<Integer> stopIds = new ArrayList<>(List.of(1));

        FanarChatOptions o = FanarChatOptions.builder()
                .stopSequences(stop)
                .madhab(madhab)
                .stopTokenIds(stopIds)
                .build();

        stop.add("MORE");
        madhab.add(Madhab.ALL);
        stopIds.add(2);

        assertThat(o.getStopSequences()).containsExactly("END");
        assertThat(o.getMadhab()).containsExactly(Madhab.MALIKI);
        assertThat(o.getStopTokenIds()).containsExactly(1);
        assertThatThrownBy(() -> o.getMadhab().add(Madhab.SHAFII))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutateRoundTripsFanarExtrasThroughTheBuilder() {
        // ChatClient rebuilds prompt options via mutate().build() — the Fanar extras must
        // survive that round trip or they'd be silently dropped in the fluent pipeline.
        FanarChatOptions original = FanarChatOptions.builder()
                .model("Fanar-Sadiq-2")
                .temperature(0.3)
                .stopSequences(List.of("END"))
                .persona("teacher")
                .madhab(List.of(Madhab.HANBALI))
                .restrictToIslamic(true)
                .build();

        Object rebuilt = original.mutate().build();

        assertThat(rebuilt).isInstanceOf(FanarChatOptions.class);
        FanarChatOptions o = (FanarChatOptions) rebuilt;
        assertThat(o.getModel()).isEqualTo("Fanar-Sadiq-2");
        assertThat(o.getTemperature()).isEqualTo(0.3);
        assertThat(o.getStopSequences()).containsExactly("END");
        assertThat(o.getPersona()).isEqualTo("teacher");
        assertThat(o.getMadhab()).containsExactly(Madhab.HANBALI);
        assertThat(o.getRestrictToIslamic()).isTrue();
    }

    @Test
    void combineWithMergesFanarExtrasNonNullWins() {
        FanarChatOptions.Builder base = FanarChatOptions.builder()
                .persona("base persona")
                .madhab(List.of(Madhab.HANAFI))
                .enableThinking(false)
                .restrictToIslamic(true)
                .bookNames(List.of(BOOK))
                .preferredSources(List.of(Source.QURAN))
                .excludeSources(List.of(Source.DORAR))
                .filterSources(List.of(Source.TAFSIR))
                .logitBias(Map.of("1", 1.0))
                .logprobs(false)
                .topLogprobs(1)
                .n(1)
                .minP(0.01)
                .repetitionPenalty(1.0)
                .bestOf(1)
                .lengthPenalty(1.0)
                .earlyStopping(false)
                .stopTokenIds(List.of(1))
                .ignoreEos(true)
                .minTokens(1)
                .skipSpecialTokens(false)
                .spacesBetweenSpecialTokens(true)
                .truncatePromptTokens(1)
                .promptLogprobs(0)
                .model("Fanar");

        FanarChatOptions.Builder override = FanarChatOptions.builder()
                .persona("override persona")
                .madhab(List.of(Madhab.MALIKI))
                .enableThinking(true)
                .restrictToIslamic(false)
                .bookNames(List.of(BOOK))
                .preferredSources(List.of(Source.SUNNAH))
                .excludeSources(List.of(Source.SHAMELA))
                .filterSources(List.of(Source.ISLAMWEB))
                .logitBias(Map.of("2", 2.0))
                .logprobs(true)
                .topLogprobs(2)
                .n(2)
                .minP(0.02)
                .repetitionPenalty(2.0)
                .bestOf(2)
                .lengthPenalty(2.0)
                .earlyStopping(true)
                .stopTokenIds(List.of(2))
                .ignoreEos(false)
                .minTokens(2)
                .skipSpecialTokens(true)
                .spacesBetweenSpecialTokens(false)
                .truncatePromptTokens(2)
                .promptLogprobs(1)
                .model("Fanar-Sadiq-2");

        FanarChatOptions merged = base.combineWith(override).build();

        assertThat(merged.getModel()).isEqualTo("Fanar-Sadiq-2");
        assertThat(merged.getPersona()).isEqualTo("override persona");
        assertThat(merged.getMadhab()).containsExactly(Madhab.MALIKI);
        assertThat(merged.getEnableThinking()).isTrue();
        assertThat(merged.getRestrictToIslamic()).isFalse();
        assertThat(merged.getPreferredSources()).containsExactly(Source.SUNNAH);
        assertThat(merged.getExcludeSources()).containsExactly(Source.SHAMELA);
        assertThat(merged.getFilterSources()).containsExactly(Source.ISLAMWEB);
        assertThat(merged.getLogitBias()).containsEntry("2", 2.0);
        assertThat(merged.getLogprobs()).isTrue();
        assertThat(merged.getTopLogprobs()).isEqualTo(2);
        assertThat(merged.getN()).isEqualTo(2);
        assertThat(merged.getMinP()).isEqualTo(0.02);
        assertThat(merged.getRepetitionPenalty()).isEqualTo(2.0);
        assertThat(merged.getBestOf()).isEqualTo(2);
        assertThat(merged.getLengthPenalty()).isEqualTo(2.0);
        assertThat(merged.getEarlyStopping()).isTrue();
        assertThat(merged.getStopTokenIds()).containsExactly(2);
        assertThat(merged.getIgnoreEos()).isFalse();
        assertThat(merged.getMinTokens()).isEqualTo(2);
        assertThat(merged.getSkipSpecialTokens()).isTrue();
        assertThat(merged.getSpacesBetweenSpecialTokens()).isFalse();
        assertThat(merged.getTruncatePromptTokens()).isEqualTo(2);
        assertThat(merged.getPromptLogprobs()).isEqualTo(1);
    }

    @Test
    void combineWithEmptyFanarBuilderKeepsBaseValues() {
        FanarChatOptions.Builder base = FanarChatOptions.builder()
                .persona("kept")
                .madhab(List.of(Madhab.SHAFII))
                .model("Fanar-Sadiq");

        FanarChatOptions merged = base.combineWith(FanarChatOptions.builder()).build();

        assertThat(merged.getPersona()).isEqualTo("kept");
        assertThat(merged.getMadhab()).containsExactly(Madhab.SHAFII);
        assertThat(merged.getModel()).isEqualTo("Fanar-Sadiq");
        assertThat(merged.getEnableThinking()).isNull();
    }

    @Test
    void combineWithPortableBuilderTouchesOnlyPortableFields() {
        FanarChatOptions.Builder base = FanarChatOptions.builder()
                .persona("kept");

        // A plain Spring AI builder carries no Fanar extras — the instanceof branch is skipped.
        FanarChatOptions merged = base
                .combineWith(org.springframework.ai.chat.prompt.ChatOptions.builder().temperature(0.7))
                .build();

        assertThat(merged.getPersona()).isEqualTo("kept");
        assertThat(merged.getTemperature()).isEqualTo(0.7);
    }
}
