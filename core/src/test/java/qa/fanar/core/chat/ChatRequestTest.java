package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatRequestTest {

    // --- required fields ------------------------------------------------------------------

    @Test
    void canonicalConstructorWithOnlyRequiredFields() {
        ChatRequest r = new ChatRequest(
                List.of(UserMessage.of("hi")), ChatModel.FANAR,
                null, null, null, null, null,
                null, null, null, null, null,
                null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertEquals(1, r.messages().size());
        assertEquals(ChatModel.FANAR, r.model());
        assertNull(r.temperature());
        assertNull(r.restrictToIslamic());
    }

    @Test
    void rejectsNullMessages() {
        assertThrows(NullPointerException.class, () -> buildWithMessages(null));
    }

    @Test
    void rejectsEmptyMessages() {
        assertThrows(IllegalArgumentException.class, () -> buildWithMessages(List.of()));
    }

    @Test
    void rejectsNullMessageInList() {
        List<Message> withNull = new ArrayList<>();
        withNull.add(UserMessage.of("x"));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> buildWithMessages(withNull));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class, () -> ChatRequest.builder()
                .addMessage(UserMessage.of("x"))
                .build());
    }

    // --- numeric range validation ---------------------------------------------------------

    @Test
    void temperatureBoundaries() {
        base().temperature(0.0).build();
        base().temperature(2.0).build();
        assertThrows(IllegalArgumentException.class, () -> base().temperature(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> base().temperature(2.01).build());
    }

    @Test
    void topPBoundaries() {
        base().topP(0.0).build();
        base().topP(1.0).build();
        assertThrows(IllegalArgumentException.class, () -> base().topP(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> base().topP(1.1).build());
    }

    @Test
    void maxTokensBoundaries() {
        base().maxTokens(1).build();
        assertThrows(IllegalArgumentException.class, () -> base().maxTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().maxTokens(-1).build());
    }

    @Test
    void nBoundaries() {
        base().n(1).build();
        assertThrows(IllegalArgumentException.class, () -> base().n(0).build());
    }

    @Test
    void frequencyPenaltyBoundaries() {
        base().frequencyPenalty(-2.0).build();
        base().frequencyPenalty(2.0).build();
        assertThrows(IllegalArgumentException.class, () -> base().frequencyPenalty(-2.1).build());
        assertThrows(IllegalArgumentException.class, () -> base().frequencyPenalty(2.1).build());
    }

    @Test
    void presencePenaltyBoundaries() {
        base().presencePenalty(-2.0).build();
        base().presencePenalty(2.0).build();
        assertThrows(IllegalArgumentException.class, () -> base().presencePenalty(-2.1).build());
        assertThrows(IllegalArgumentException.class, () -> base().presencePenalty(2.1).build());
    }

    @Test
    void topLogprobsBoundaries() {
        base().topLogprobs(0).build();
        base().topLogprobs(20).build();
        assertThrows(IllegalArgumentException.class, () -> base().topLogprobs(-1).build());
        assertThrows(IllegalArgumentException.class, () -> base().topLogprobs(21).build());
    }

    @Test
    void minTokensBoundaries() {
        base().minTokens(0).build();
        assertThrows(IllegalArgumentException.class, () -> base().minTokens(-1).build());
    }

    @Test
    void topKBoundaries() {
        base().topK(1).build();
        assertThrows(IllegalArgumentException.class, () -> base().topK(0).build());
    }

    @Test
    void minPBoundaries() {
        base().minP(0.0).build();
        base().minP(1.0).build();
        assertThrows(IllegalArgumentException.class, () -> base().minP(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> base().minP(1.1).build());
    }

    @Test
    void bestOfBoundaries() {
        base().bestOf(1).build();
        assertThrows(IllegalArgumentException.class, () -> base().bestOf(0).build());
    }

    @Test
    void repetitionPenaltyMustBeStrictlyPositive() {
        base().repetitionPenalty(0.01).build();
        base().repetitionPenalty(1.1).build();
        assertThrows(IllegalArgumentException.class, () -> base().repetitionPenalty(0.0).build());
        assertThrows(IllegalArgumentException.class, () -> base().repetitionPenalty(-0.5).build());
    }

    @Test
    void truncatePromptTokensBoundaries() {
        base().truncatePromptTokens(1).build();
        assertThrows(IllegalArgumentException.class, () -> base().truncatePromptTokens(0).build());
    }

    @Test
    void promptLogprobsBoundaries() {
        base().promptLogprobs(0).build();
        assertThrows(IllegalArgumentException.class, () -> base().promptLogprobs(-1).build());
    }

    @Test
    void stopMaxFourEntries() {
        base().stop(List.of("a", "b", "c", "d")).build();
        assertThrows(IllegalArgumentException.class, () ->
                base().stop(List.of("a", "b", "c", "d", "e")).build());
    }

    // --- defensive copies + unmodifiable accessors -----------------------------------------

    @Test
    void messagesAreDefensivelyCopiedAndUnmodifiable() {
        List<Message> src = new ArrayList<>();
        src.add(UserMessage.of("x"));
        ChatRequest r = base().messages(src).build();
        src.add(UserMessage.of("y"));
        assertEquals(1, r.messages().size());
        assertThrows(UnsupportedOperationException.class, () ->
                r.messages().add(UserMessage.of("z")));
    }

    @Test
    void stopIsDefensivelyCopiedAndUnmodifiable() {
        List<String> src = new ArrayList<>(Arrays.asList("a", "b"));
        ChatRequest r = base().stop(src).build();
        src.add("c");
        assertEquals(2, r.stop().size());
        assertThrows(UnsupportedOperationException.class, () -> r.stop().add("c"));
    }

    @Test
    void logitBiasIsDefensivelyCopiedAndUnmodifiable() {
        Map<String, Double> src = new HashMap<>();
        src.put("1", 1.0);
        ChatRequest r = base().logitBias(src).build();
        src.put("2", 2.0);
        assertEquals(1, r.logitBias().size());
        assertThrows(UnsupportedOperationException.class, () -> r.logitBias().put("3", 3.0));
    }

    @Test
    void stopTokenIdsIsDefensivelyCopiedAndUnmodifiable() {
        List<Integer> src = new ArrayList<>(List.of(1, 2));
        ChatRequest r = base().stopTokenIds(src).build();
        src.add(3);
        assertEquals(2, r.stopTokenIds().size());
        assertThrows(UnsupportedOperationException.class, () -> r.stopTokenIds().add(3));
    }

    @Test
    void bookNamesIsDefensivelyCopiedAndUnmodifiable() {
        List<BookName> known = sampleBooks();
        List<BookName> src = new ArrayList<>(List.of(known.get(0)));
        ChatRequest r = base().bookNames(src).build();
        src.add(known.get(1));
        assertEquals(1, r.bookNames().size());
        assertThrows(UnsupportedOperationException.class, () -> r.bookNames().add(known.get(1)));
    }

    /** First two books from the {@link BookName#KNOWN} catalogue — stable test fixtures. */
    private static List<BookName> sampleBooks() {
        return BookName.KNOWN.stream().limit(2).toList();
    }

    @Test
    void preferredSourcesIsDefensivelyCopiedAndUnmodifiable() {
        List<Source> src = new ArrayList<>(List.of(Source.QURAN));
        ChatRequest r = base().preferredSources(src).build();
        src.add(Source.SUNNAH);
        assertEquals(1, r.preferredSources().size());
        assertThrows(UnsupportedOperationException.class, () ->
                r.preferredSources().add(Source.TAFSIR));
    }

    @Test
    void excludeSourcesIsDefensivelyCopiedAndUnmodifiable() {
        List<Source> src = new ArrayList<>(List.of(Source.DORAR));
        ChatRequest r = base().excludeSources(src).build();
        src.add(Source.ISLAMONLINE);
        assertEquals(1, r.excludeSources().size());
        assertThrows(UnsupportedOperationException.class, () ->
                r.excludeSources().add(Source.SHAMELA));
    }

    @Test
    void filterSourcesIsDefensivelyCopiedAndUnmodifiable() {
        List<Source> src = new ArrayList<>(List.of(Source.ISLAMWEB));
        ChatRequest r = base().filterSources(src).build();
        src.add(Source.ISLAMWEB_FATWA);
        assertEquals(1, r.filterSources().size());
        assertThrows(UnsupportedOperationException.class, () ->
                r.filterSources().add(Source.ISLAMWEB_LIBRARY));
    }

    @Test
    void defensiveCopyReplacesReferenceForMessages() {
        List<Message> src = new ArrayList<>();
        src.add(UserMessage.of("x"));
        ChatRequest r = new ChatRequest(
                src, ChatModel.FANAR,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertNotSame(src, r.messages());
    }

    // --- null-optional fields are null-through --------------------------------------------

    @Test
    void nullOptionalCollectionsStayNull() {
        ChatRequest r = base().build();
        assertNull(r.stop());
        assertNull(r.logitBias());
        assertNull(r.stopTokenIds());
        assertNull(r.bookNames());
        assertNull(r.preferredSources());
        assertNull(r.excludeSources());
        assertNull(r.filterSources());
    }

    // --- Builder -------------------------------------------------------------------------

    @Test
    void builderReturnsFreshInstance() {
        assertNotSame(ChatRequest.builder(), ChatRequest.builder());
    }

    @Test
    void builderAddMessageAccumulates() {
        ChatRequest r = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("first"))
                .addMessage(AssistantMessage.of("second"))
                .addMessage(UserMessage.of("third"))
                .build();
        assertEquals(3, r.messages().size());
    }

    @Test
    void builderMessagesReplacesAccumulation() {
        ChatRequest r = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("dropped"))
                .messages(List.of(UserMessage.of("kept")))
                .build();
        assertEquals(1, r.messages().size());
    }

    @Test
    void builderAddMessageRejectsNull() {
        assertThrows(NullPointerException.class, () ->
                ChatRequest.builder().addMessage(null));
    }

    @Test
    void builderMessagesRejectsNullList() {
        assertThrows(NullPointerException.class, () ->
                ChatRequest.builder().messages(null));
    }

    @Test
    void builderMessagesRejectsNullElement() {
        List<Message> withNull = new ArrayList<>();
        withNull.add(UserMessage.of("x"));
        withNull.add(null);
        assertThrows(NullPointerException.class, () ->
                ChatRequest.builder().messages(withNull));
    }

    @Test
    void builderAllFieldsRoundtrip() {
        ChatRequest r = ChatRequest.builder()
                .addMessage(SystemMessage.of("sys"))
                .addMessage(UserMessage.of("user"))
                .model(ChatModel.FANAR_C_2_27B)
                .temperature(0.7)
                .topP(0.9)
                .maxTokens(128)
                .n(2)
                .stop(List.of("\n\n"))
                .frequencyPenalty(0.1)
                .presencePenalty(0.2)
                .logitBias(Map.of("50256", -100.0))
                .logprobs(true)
                .topLogprobs(5)
                .enableThinking(true)
                .topK(40)
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
                .bookNames(sampleBooks())
                .preferredSources(List.of(Source.QURAN, Source.TAFSIR))
                .excludeSources(List.of(Source.DORAR))
                .filterSources(List.of(Source.ISLAMWEB))
                .restrictToIslamic(true)
                .build();

        assertEquals(2, r.messages().size());
        assertEquals(ChatModel.FANAR_C_2_27B, r.model());
        assertEquals(0.7, r.temperature());
        assertEquals(0.9, r.topP());
        assertEquals(128, r.maxTokens());
        assertEquals(2, r.n());
        assertEquals(List.of("\n\n"), r.stop());
        assertEquals(0.1, r.frequencyPenalty());
        assertEquals(0.2, r.presencePenalty());
        assertEquals(Map.of("50256", -100.0), r.logitBias());
        assertEquals(true, r.logprobs());
        assertEquals(5, r.topLogprobs());
        assertEquals(true, r.enableThinking());
        assertEquals(40, r.topK());
        assertEquals(0.05, r.minP());
        assertEquals(1.1, r.repetitionPenalty());
        assertEquals(3, r.bestOf());
        assertEquals(1.2, r.lengthPenalty());
        assertEquals(true, r.earlyStopping());
        assertEquals(List.of(50256), r.stopTokenIds());
        assertEquals(false, r.ignoreEos());
        assertEquals(8, r.minTokens());
        assertEquals(true, r.skipSpecialTokens());
        assertEquals(false, r.spacesBetweenSpecialTokens());
        assertEquals(2048, r.truncatePromptTokens());
        assertEquals(1, r.promptLogprobs());
        assertEquals(sampleBooks(), r.bookNames());
        assertEquals(List.of(Source.QURAN, Source.TAFSIR), r.preferredSources());
        assertEquals(List.of(Source.DORAR), r.excludeSources());
        assertEquals(List.of(Source.ISLAMWEB), r.filterSources());
        assertEquals(true, r.restrictToIslamic());
    }

    @Test
    void builderValidationDelegatesToCanonicalConstructor() {
        assertThrows(IllegalArgumentException.class, () ->
                base().temperature(10.0).build());
    }

    // --- helpers --------------------------------------------------------------------------

    /** Minimal builder with only the required fields filled. */
    private static ChatRequest.Builder base() {
        return ChatRequest.builder()
                .addMessage(UserMessage.of("x"))
                .model(ChatModel.FANAR);
    }

    private static ChatRequest buildWithMessages(List<Message> msgs) {
        return new ChatRequest(
                msgs, ChatModel.FANAR,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
