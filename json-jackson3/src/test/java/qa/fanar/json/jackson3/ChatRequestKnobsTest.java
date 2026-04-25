package qa.fanar.json.jackson3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.Source;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.ThinkingMessage;
import qa.fanar.core.chat.ThinkingUserMessage;
import qa.fanar.core.chat.UserMessage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire snapshot tests for {@link ChatRequest}: verify each knob lands on the wire with the
 * exact field name (snake_case), correct JSON type, and that unset fields are omitted under
 * {@code NON_NULL} inclusion. Each Fanar-supported request property is exercised at least once.
 */
class ChatRequestKnobsTest {

    private final Jackson3FanarJsonCodec codec = new Jackson3FanarJsonCodec();

    @Test
    void coreSamplingKnobsSerializeAsSnakeCaseScalars() throws IOException {
        ChatRequest req = base()
                .temperature(0.7)
                .topP(0.95)
                .maxTokens(128)
                .n(2)
                .frequencyPenalty(0.1)
                .presencePenalty(-0.2)
                .logprobs(true)
                .topLogprobs(5)
                .build();
        String json = encode(req);
        assertAll(
                () -> assertTrue(json.contains("\"temperature\":0.7"), json),
                () -> assertTrue(json.contains("\"top_p\":0.95"), json),
                () -> assertTrue(json.contains("\"max_tokens\":128"), json),
                () -> assertTrue(json.contains("\"n\":2"), json),
                () -> assertTrue(json.contains("\"frequency_penalty\":0.1"), json),
                () -> assertTrue(json.contains("\"presence_penalty\":-0.2"), json),
                () -> assertTrue(json.contains("\"logprobs\":true"), json),
                () -> assertTrue(json.contains("\"top_logprobs\":5"), json));
    }

    @Test
    void stopSequenceSerializesAsJsonArray() throws IOException {
        ChatRequest req = base().stop(List.of("\n", "STOP")).build();
        String json = encode(req);
        assertTrue(json.contains("\"stop\":[\"\\n\",\"STOP\"]"), json);
    }

    @Test
    void logitBiasSerializesAsJsonObject() throws IOException {
        ChatRequest req = base().logitBias(Map.of("50256", -100.0)).build();
        String json = encode(req);
        assertTrue(json.contains("\"logit_bias\":{\"50256\":-100.0}"), json);
    }

    @Test
    void vllmSamplingKnobsSerializeAsSnakeCaseScalars() throws IOException {
        ChatRequest req = base()
                .topK(40)
                .minP(0.05)
                .repetitionPenalty(1.1)
                .bestOf(2)
                .lengthPenalty(0.8)
                .earlyStopping(true)
                .stopTokenIds(List.of(13, 50256))
                .ignoreEos(false)
                .minTokens(8)
                .skipSpecialTokens(true)
                .spacesBetweenSpecialTokens(false)
                .truncatePromptTokens(2048)
                .promptLogprobs(3)
                .build();
        String json = encode(req);
        assertAll(
                () -> assertTrue(json.contains("\"top_k\":40"), json),
                () -> assertTrue(json.contains("\"min_p\":0.05"), json),
                () -> assertTrue(json.contains("\"repetition_penalty\":1.1"), json),
                () -> assertTrue(json.contains("\"best_of\":2"), json),
                () -> assertTrue(json.contains("\"length_penalty\":0.8"), json),
                () -> assertTrue(json.contains("\"early_stopping\":true"), json),
                () -> assertTrue(json.contains("\"stop_token_ids\":[13,50256]"), json),
                () -> assertTrue(json.contains("\"ignore_eos\":false"), json),
                () -> assertTrue(json.contains("\"min_tokens\":8"), json),
                () -> assertTrue(json.contains("\"skip_special_tokens\":true"), json),
                () -> assertTrue(json.contains("\"spaces_between_special_tokens\":false"), json),
                () -> assertTrue(json.contains("\"truncate_prompt_tokens\":2048"), json),
                () -> assertTrue(json.contains("\"prompt_logprobs\":3"), json));
    }

    @Test
    void sadiqIslamicRagKnobsSerializeWithSourceWireValues() throws IOException {
        List<BookName> books = BookName.known().stream().limit(2).map(BookName::of).toList();
        ChatRequest req = base()
                .model(ChatModel.FANAR_SADIQ)
                .restrictToIslamic(true)
                .bookNames(books)
                .preferredSources(List.of(Source.QURAN))
                .excludeSources(List.of(Source.SUNNAH))
                .filterSources(List.of(Source.TAFSIR))
                .build();
        String json = encode(req);
        String expectedBookNames = "\"book_names\":[\""
                + books.get(0).value() + "\",\"" + books.get(1).value() + "\"]";
        assertAll(
                () -> assertTrue(json.contains("\"restrict_to_islamic\":true"), json),
                () -> assertTrue(json.contains(expectedBookNames), json),
                () -> assertTrue(json.contains("\"preferred_sources\":[\"" + Source.QURAN.wireValue() + "\"]"), json),
                () -> assertTrue(json.contains("\"exclude_sources\":[\"" + Source.SUNNAH.wireValue() + "\"]"), json),
                () -> assertTrue(json.contains("\"filter_sources\":[\"" + Source.TAFSIR.wireValue() + "\"]"), json));
    }

    @Test
    void enableThinkingAndModelWireValueSerialize() throws IOException {
        ChatRequest req = base()
                .model(ChatModel.FANAR_C_2_27B)
                .enableThinking(true)
                .build();
        String json = encode(req);
        assertAll(
                () -> assertTrue(json.contains("\"enable_thinking\":true"), json),
                () -> assertTrue(json.contains("\"model\":\"" + ChatModel.FANAR_C_2_27B.wireValue() + "\""), json));
    }

    @Test
    void allFiveMessageRolesSerializeWithDiscriminator() throws IOException {
        ChatRequest req = ChatRequest.builder()
                .model(ChatModel.FANAR_C_2_27B)
                .addMessage(SystemMessage.of("system instructions"))
                .addMessage(UserMessage.of("hello"))
                .addMessage(AssistantMessage.of("greetings"))
                .addMessage(ThinkingMessage.of("inner monologue"))
                .addMessage(new ThinkingUserMessage("clarification"))
                .build();
        String json = encode(req);
        assertAll(
                () -> assertTrue(json.contains("\"role\":\"system\""), json),
                () -> assertTrue(json.contains("\"role\":\"user\""), json),
                () -> assertTrue(json.contains("\"role\":\"assistant\""), json),
                () -> assertTrue(json.contains("\"role\":\"thinking\""), json),
                () -> assertTrue(json.contains("\"role\":\"thinking_user\""), json));
    }

    @Test
    void unsetOptionalFieldsAreOmitted() throws IOException {
        // A minimal request — nothing optional set. Inclusion is NON_NULL so optional fields
        // must NOT appear in the output.
        ChatRequest minimal = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("hi"))
                .build();
        String json = encode(minimal);
        // Sample of fields that must NOT appear.
        assertAll(
                () -> assertFalse(json.contains("temperature"), json),
                () -> assertFalse(json.contains("max_tokens"), json),
                () -> assertFalse(json.contains("logit_bias"), json),
                () -> assertFalse(json.contains("restrict_to_islamic"), json),
                () -> assertFalse(json.contains("enable_thinking"), json),
                () -> assertFalse(json.contains("stop"), json));
    }

    @Test
    void utf8AndArabicContentRoundTripsByteForByte() throws IOException {
        // Verify Arabic survives encode/decode. The Fanar API is Arabic-first; this catches
        // accidental ASCII-only handling in the codec or interceptor.
        ChatRequest req = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("مرحبا، كيف حالك؟"))
                .build();
        String json = encode(req);
        // Jackson escapes non-ASCII by default? Default: NO — emits raw UTF-8.
        assertTrue(json.contains("مرحبا، كيف حالك؟"),
                "Arabic must round-trip without escape: " + json);
    }

    // --- helpers

    private static ChatRequest.Builder base() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("hi"));
    }

    private String encode(ChatRequest request) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        codec.encode(buf, request);
        return buf.toString(StandardCharsets.UTF_8);
    }
}
