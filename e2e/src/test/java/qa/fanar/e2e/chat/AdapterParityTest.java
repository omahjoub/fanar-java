package qa.fanar.e2e.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.ToolCall;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.images.ImageGenerationItem;
import qa.fanar.core.images.ImageGenerationRequest;
import qa.fanar.core.images.ImageGenerationResponse;
import qa.fanar.core.images.ImageModel;
import qa.fanar.core.moderations.ModerationModel;
import qa.fanar.core.moderations.SafetyFilterRequest;
import qa.fanar.core.moderations.SafetyFilterResponse;
import qa.fanar.core.models.ModelsResponse;
import qa.fanar.core.poems.PoemGenerationRequest;
import qa.fanar.core.poems.PoemGenerationResponse;
import qa.fanar.core.poems.PoemModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.tokens.TokenizationRequest;
import qa.fanar.core.tokens.TokenizationResponse;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationPreprocessing;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;
import qa.fanar.e2e.CapturingInterceptor;
import qa.fanar.e2e.Probes;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapter parity: any wire payload one adapter can encode or decode, the other must too,
 * with byte-equivalent JSON shape and {@code .equals()} record output.
 *
 * <p>The two offline tests prove the structural property: same input → same output. The third
 * (live) test is the critical insurance against server drift — it captures the actual bytes
 * Fanar returns for a real call and decodes the same bytes through both adapters. Catches
 * regressions the canned-wire offline test silently misses if the server ever adds a field,
 * renames one, or changes a type.</p>
 */
class AdapterParityTest {

    private final Jackson2FanarJsonCodec jackson2 = new Jackson2FanarJsonCodec();
    private final Jackson3FanarJsonCodec jackson3 = new Jackson3FanarJsonCodec();

    @Test
    void chatRequestJsonShapeIsIdenticalAcrossAdapters() throws IOException {
        ChatRequest request = ChatRequest.builder()
                .model(ChatModel.FANAR_C_2_27B)
                .addMessage(SystemMessage.of("system instructions"))
                .addMessage(UserMessage.of("hello"))
                .addMessage(AssistantMessage.of("greetings"))
                .temperature(0.7)
                .topP(0.95)
                .maxTokens(64)
                .stop(List.of("\n"))
                .logprobs(true)
                .topLogprobs(3)
                .enableThinking(true)
                .build();

        Map<?, ?> shape2 = parseAsMap(encode(jackson2, request));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, request));
        assertEquals(shape2, shape3, "Jackson 2 and Jackson 3 must emit the same JSON shape");
    }

    @Test
    void chatResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        // A canned wire response — same shape Fanar emits in production, including the
        // undocumented `stop_reason` on each choice and Sadiq's retrieval accounting fields
        // (`successful_requests`, `total_cost`) inside `usage`.
        String wire = "{\"id\":\"c_1\",\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"content\":\"pong\",\"role\":\"assistant\","
                + "\"references\":null,\"tool_calls\":[]},"
                + "\"stop_reason\":\"<end_of_turn>\"}],"
                + "\"created\":1700000000,\"model\":\"Fanar-C-2-27B\","
                + "\"usage\":{\"completion_tokens\":2,\"prompt_tokens\":10,\"total_tokens\":12,"
                + "\"successful_requests\":1,\"total_cost\":0.0}}";

        ChatResponse decoded2 = jackson2.decode(bytes(wire), ChatResponse.class);
        ChatResponse decoded3 = jackson3.decode(bytes(wire), ChatResponse.class);
        assertEquals(decoded2, decoded3,
                "ChatResponse decoded by both adapters must be record-equal");
        assertEquals("<end_of_turn>", decoded3.choices().getFirst().stopReason(),
                "stop_reason field on the wire must map to ChatChoice.stopReason");
        assertEquals(1, decoded3.usage().successfulRequests(),
                "successful_requests on the wire must map to CompletionUsage.successfulRequests");
        assertEquals(0.0, decoded3.usage().totalCost(),
                "total_cost on the wire must map to CompletionUsage.totalCost");
    }

    /**
     * Tool-call wire decoding parity. Fanar's chat-completion endpoint does not accept user-defined
     * {@code tools}/{@code tool_choice} on the request, so we cannot drive a real round-trip
     * against the live API — but the response shape is well-defined and Sadiq's internal RAG
     * retriever surfaces tool calls on the way back. This test pins the response-side decoding
     * (id / name / arguments / result / structured_content / is_error) for both adapters via a
     * canned wire payload synthesized from the OpenAPI {@code ChatCompletionToolCall} schema.
     */
    @Test
    void chatResponseWithToolCallsDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"c_2\",\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"content\":\"Searching the corpus.\",\"role\":\"assistant\","
                + "\"references\":null,\"tool_calls\":["
                + "{\"id\":\"call_abc\",\"name\":\"retrieve\","
                + "\"arguments\":{\"query\":\"Al-Fatihah\"},"
                + "\"result\":\"Found 4 references\","
                + "\"structured_content\":null,\"is_error\":false}]}}],"
                + "\"created\":1700000000,\"model\":\"Fanar-Sadiq\","
                + "\"usage\":{\"completion_tokens\":0,\"prompt_tokens\":0,\"total_tokens\":0}}";

        ChatResponse decoded2 = jackson2.decode(bytes(wire), ChatResponse.class);
        ChatResponse decoded3 = jackson3.decode(bytes(wire), ChatResponse.class);
        assertEquals(decoded2, decoded3,
                "ChatResponse with tool_calls must decode equivalently across adapters");

        List<ToolCall> calls = decoded3.choices().getFirst().message().toolCalls();
        assertEquals(1, calls.size(), "expected one tool call on the message");
        ToolCall call = calls.getFirst();
        assertEquals("call_abc", call.id());
        assertEquals("retrieve", call.name());
        assertEquals("Al-Fatihah", call.arguments().get("query"));
        assertEquals("Found 4 references", call.result());
        assertFalse(call.isError());
    }

    @Test
    void tokenizationRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        TokenizationRequest req = TokenizationRequest.of("hello", ChatModel.FANAR_S_1_7B);
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "TokenizationRequest must encode to the same JSON shape via both adapters");
        assertEquals("hello", shape3.get("content"));
        assertEquals("Fanar-S-1-7B", shape3.get("model"));
    }

    @Test
    void tokenizationResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_1\",\"tokens\":7,\"max_request_tokens\":4096}";
        TokenizationResponse decoded2 = jackson2.decode(bytes(wire), TokenizationResponse.class);
        TokenizationResponse decoded3 = jackson3.decode(bytes(wire), TokenizationResponse.class);
        assertEquals(decoded2, decoded3,
                "TokenizationResponse decoded by both adapters must be record-equal");
        assertEquals(7, decoded3.tokens());
        assertEquals(4096, decoded3.maxRequestTokens());
    }

    @Test
    void safetyFilterRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        SafetyFilterRequest req = SafetyFilterRequest.of(
                ModerationModel.FANAR_GUARD_2, "ping", "pong");
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "SafetyFilterRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Guard-2", shape3.get("model"));
        assertEquals("ping", shape3.get("prompt"));
        assertEquals("pong", shape3.get("response"));
    }

    @Test
    void safetyFilterResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        // Live server emits `id` even though the OpenAPI spec doesn't declare it; we capture
        // it on the record for correlation/debugging.
        String wire = "{\"safety\":0.95,\"cultural_awareness\":0.88,\"id\":\"req_xyz\"}";
        SafetyFilterResponse decoded2 = jackson2.decode(bytes(wire), SafetyFilterResponse.class);
        SafetyFilterResponse decoded3 = jackson3.decode(bytes(wire), SafetyFilterResponse.class);
        assertEquals(decoded2, decoded3,
                "SafetyFilterResponse decoded by both adapters must be record-equal");
        assertEquals(0.95, decoded3.safety());
        assertEquals(0.88, decoded3.culturalAwareness());
        assertEquals("req_xyz", decoded3.id());
    }

    @Test
    void translationRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        // Verifies the langPair → "langpair" wire-naming override applies through both adapters
        // and that the optional preprocessing field round-trips when set.
        TranslationRequest req = new TranslationRequest(
                TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.EN_AR,
                TranslationPreprocessing.PRESERVE_HTML);
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "TranslationRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Shaheen-MT-1", shape3.get("model"));
        assertEquals("hello", shape3.get("text"));
        assertEquals("en-ar", shape3.get("langpair"));
        assertEquals("preserve_html", shape3.get("preprocessing"));
    }

    @Test
    void translationRequestOmitsNullPreprocessingOnWire() throws IOException {
        TranslationRequest req = TranslationRequest.of(
                TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.AR_EN);
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        // NON_NULL inclusion strips the field when null; spec says server applies its default.
        assertFalse(shape3.containsKey("preprocessing"),
                "null preprocessing must not appear on the wire");
    }

    @Test
    void translationResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_1\",\"text\":\"مرحبا\"}";
        TranslationResponse decoded2 = jackson2.decode(bytes(wire), TranslationResponse.class);
        TranslationResponse decoded3 = jackson3.decode(bytes(wire), TranslationResponse.class);
        assertEquals(decoded2, decoded3,
                "TranslationResponse decoded by both adapters must be record-equal");
        assertEquals("req_1", decoded3.id());
        assertEquals("مرحبا", decoded3.text());
    }

    @Test
    void poemGenerationRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        PoemGenerationRequest req = PoemGenerationRequest.of(
                PoemModel.FANAR_DIWAN, "Write a poem about the sea");
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "PoemGenerationRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Diwan", shape3.get("model"));
        assertEquals("Write a poem about the sea", shape3.get("prompt"));
    }

    @Test
    void poemGenerationResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_1\",\"poem\":\"البحر يهدر بأمواجه\"}";
        PoemGenerationResponse decoded2 = jackson2.decode(bytes(wire), PoemGenerationResponse.class);
        PoemGenerationResponse decoded3 = jackson3.decode(bytes(wire), PoemGenerationResponse.class);
        assertEquals(decoded2, decoded3,
                "PoemGenerationResponse decoded by both adapters must be record-equal");
        assertEquals("req_1", decoded3.id());
        assertEquals("البحر يهدر بأمواجه", decoded3.poem());
    }

    @Test
    void imageGenerationRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        ImageGenerationRequest req = ImageGenerationRequest.of(
                ImageModel.FANAR_ORYX_IG_2, "A futuristic cityscape at sunset");
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "ImageGenerationRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Oryx-IG-2", shape3.get("model"));
        assertEquals("A futuristic cityscape at sunset", shape3.get("prompt"));
    }

    @Test
    void imageGenerationResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        // Wire shape mirrors the spec: id, created, data[].b64_json (snake-case maps to b64Json).
        String wire = "{\"id\":\"req_1\",\"created\":1700000000,"
                + "\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}";
        ImageGenerationResponse decoded2 = jackson2.decode(bytes(wire), ImageGenerationResponse.class);
        ImageGenerationResponse decoded3 = jackson3.decode(bytes(wire), ImageGenerationResponse.class);
        assertEquals(decoded2, decoded3,
                "ImageGenerationResponse decoded by both adapters must be record-equal");
        assertEquals("req_1", decoded3.id());
        assertEquals(1_700_000_000L, decoded3.created());
        assertEquals(1, decoded3.data().size());
        ImageGenerationItem item = decoded3.data().getFirst();
        assertEquals("aGVsbG8=", item.b64Json());
    }

    @Test
    void modelsResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        // A canned shape mirroring what the live /v1/models endpoint emits, including the
        // discriminator field "object" (always "model") that we keep for wire fidelity.
        String wire = "{\"id\":\"req_1\",\"models\":["
                + "{\"id\":\"Fanar\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"fanar\"},"
                + "{\"id\":\"Fanar-Sadiq\",\"object\":\"model\",\"created\":1700000001,\"owned_by\":\"fanar\"}"
                + "]}";

        ModelsResponse decoded2 = jackson2.decode(bytes(wire), ModelsResponse.class);
        ModelsResponse decoded3 = jackson3.decode(bytes(wire), ModelsResponse.class);
        assertEquals(decoded2, decoded3,
                "ModelsResponse decoded by both adapters must be record-equal");
        assertEquals(2, decoded3.models().size());
        assertEquals("fanar", decoded3.models().getFirst().ownedBy());
    }

    /**
     * Live counterpart to the canned-wire test above: hit the real API once via Jackson 3,
     * capture the raw response bytes through a {@link CapturingInterceptor}, and decode the
     * same bytes via both adapters. If the server ever drifts (new field, renamed field, type
     * change), this test fails before the divergence reaches downstream users.
     */
    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
    void liveResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        CapturingInterceptor capture = new CapturingInterceptor();
        try (FanarClient client = FanarClient.builder()
                .apiKey(TestClients.apiKey().orElseThrow())
                .jsonCodec(jackson3)
                .addInterceptor(capture)
                .build()) {
            client.chat().send(Probes.ping());
        }

        byte[] wire = capture.lastResponseBody();
        assertNotNull(wire, "interceptor must have captured a response body");

        ChatResponse decoded2 = jackson2.decode(new ByteArrayInputStream(wire), ChatResponse.class);
        ChatResponse decoded3 = jackson3.decode(new ByteArrayInputStream(wire), ChatResponse.class);
        assertEquals(decoded2, decoded3,
                "Live wire response must decode identically via both adapters");
    }

    // --- helpers

    /**
     * Parse JSON bytes into a generic {@link Map} via Jackson 2 — used purely as a structural
     * comparator (order-independent, type-faithful).
     */
    private Map<?, ?> parseAsMap(String json) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper plain =
                new com.fasterxml.jackson.databind.ObjectMapper();
        return plain.readValue(json, Map.class);
    }

    private static String encode(FanarJsonCodec codec, Object value) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        codec.encode(buf, value);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
