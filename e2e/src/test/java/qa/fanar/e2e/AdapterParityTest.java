package qa.fanar.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import qa.fanar.core.audio.AvailableVoice;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.audio.VoiceResponse;
import qa.fanar.core.audio.VoiceType;
import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.ProgressChunk;
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
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.DeepResearchSection;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.tokens.TokenizationRequest;
import qa.fanar.core.tokens.TokenizationResponse;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationPreprocessing;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapter parity across every domain: any wire payload one adapter encodes or decodes, the
 * other must too — same JSON shape on encode, record-equal output on decode.
 *
 * <p>Each test pins a canned wire payload synthesized from the OpenAPI spec; the suite is fully
 * offline and exercises the structural property only. Server drift (renamed / added / retyped
 * fields) is not caught here and must be picked up by the per-domain {@code Live*Test} classes.</p>
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
                .persona("Warm, patient teacher")
                .madhab(List.of(Madhab.HANAFI))
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
    void sadiqValidationRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        SadiqValidationRequest req = SadiqValidationRequest.of(
                ChatModel.FANAR_SADIQ_2, "قال الله تعالى");
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "SadiqValidationRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Sadiq-2", shape3.get("model"));
        assertEquals("قال الله تعالى", shape3.get("text"));
    }

    @Test
    void sadiqValidationResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_1\",\"text\":\"<quran_start>x<quran_end> "
                + "[45](https://quran.com/29/45) <hadith_start>y<hadith_end>\"}";
        SadiqValidationResponse decoded2 = jackson2.decode(bytes(wire), SadiqValidationResponse.class);
        SadiqValidationResponse decoded3 = jackson3.decode(bytes(wire), SadiqValidationResponse.class);
        assertEquals(decoded2, decoded3,
                "SadiqValidationResponse decoded by both adapters must be record-equal");
        assertEquals("req_1", decoded3.id());
        assertTrue(decoded3.text().contains("<quran_start>"), "tags must survive decoding unparsed");
        assertTrue(decoded3.text().contains("<hadith_start>"), "hadith tags must survive too");
    }

    @Test
    void deepResearchRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        DeepResearchRequest req = new DeepResearchRequest(
                ChatModel.FANAR_SADIQ_2, "The importance of seeking knowledge in Islam",
                DeepResearchDepth.QUICK, true);
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "DeepResearchRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Sadiq-2", shape3.get("model"));
        assertEquals("The importance of seeking knowledge in Islam", shape3.get("input"));
        assertEquals("quick", shape3.get("depth"),
                "DeepResearchDepth must serialize as its wire string through WireValueModule");
        assertEquals(true, shape3.get("web_search"),
                "webSearch must reach the wire as snake-case web_search");
        // No `stream`: the record leaves it out and the facade splices it in per call site.
        assertEquals(Set.of("model", "input", "depth", "web_search"), shape3.keySet(),
                "the request must carry exactly its four modelled fields");
    }

    @Test
    void deepResearchRequestOmitsUnsetKnobsOnWire() throws IOException {
        DeepResearchRequest req = DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic");
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "DeepResearchRequest with server defaults must encode identically via both adapters");
        // NON_NULL inclusion strips both knobs; the server applies its defaults (standard, no web).
        assertFalse(shape3.containsKey("depth"), "null depth must not appear on the wire");
        assertFalse(shape3.containsKey("web_search"), "null webSearch must not appear on the wire");
        assertEquals(Set.of("model", "input"), shape3.keySet());
    }

    @Test
    void deepResearchReportDecodesIdenticallyAcrossAdapters() throws IOException {
        // Modelled on the spec's DeepResearchReport example: bilingual title and heading, a
        // recursive section tree, the source shape every spec sample shows, and the free-form
        // metadata summary — here with one JSON-null value the record must keep.
        String wire = """
                {
                  "topic": "ما أهمية طلب العلم في الإسلام؟",
                  "language": "ar",
                  "title": "The Importance of Seeking Knowledge in Islam",
                  "title_ar": "أهمية طلب العلم في الإسلام",
                  "overview": "Why seeking knowledge is an obligation, in three sections.",
                  "sections": [
                    {
                      "heading": "The religious basis for seeking knowledge",
                      "heading_ar": "الأساس الديني لطلب العلم",
                      "mode": "general",
                      "content": "The Quran and the Sunnah stress seeking knowledge [إسلام ويب:228949].",
                      "cited_sources": [
                        {
                          "source": "https://islamweb.net/ar/article/228949/",
                          "citation_tag": "[إسلام ويب:228949]",
                          "quote": "العِلْمُ نورٌ للعُقولِ، وضِياءٌ للمجتمعات",
                          "was_cited": true
                        }
                      ],
                      "children": [
                        {"heading": "child", "children": []}
                      ]
                    }
                  ],
                  "sources": [
                    {
                      "source": "https://fiqh.islamonline.net/knowledge/",
                      "citation_tag": "[إسلام أون لاين:943042]",
                      "quote": "طلب العلم: أهميته وطرق تحصيله",
                      "was_cited": true
                    },
                    {
                      "source": "https://islamweb.net/ar/article/228949/",
                      "citation_tag": "[إسلام ويب:228949]",
                      "quote": "العِلْمُ نورٌ للعُقولِ",
                      "was_cited": true
                    }
                  ],
                  "markdown": "# The Importance of Seeking Knowledge in Islam",
                  "metadata": {
                    "depth": "quick",
                    "mode": "deep_research",
                    "elapsed_seconds": 267.32,
                    "sections_count": 4,
                    "total_sources": 28,
                    "web_search_used": false,
                    "web_sources_count": null,
                    "adaptive_planning_used": true
                  }
                }
                """;
        DeepResearchReport decoded2 = jackson2.decode(bytes(wire), DeepResearchReport.class);
        DeepResearchReport decoded3 = jackson3.decode(bytes(wire), DeepResearchReport.class);
        assertEquals(decoded2, decoded3,
                "DeepResearchReport decoded by both adapters must be record-equal");
        assertEquals("The Importance of Seeking Knowledge in Islam", decoded3.title());
        assertEquals("أهمية طلب العلم في الإسلام", decoded3.titleAr(),
                "title_ar on the wire must map to DeepResearchReport.titleAr");
        assertEquals(1, decoded3.sections().size());
        DeepResearchSection section = decoded3.sections().getFirst();
        assertEquals("child", section.children().getFirst().heading(),
                "sections must nest recursively through children");
        assertEquals("[إسلام ويب:228949]", section.citedSources().getFirst().citationTag(),
                "cited_sources / citation_tag on the wire must map to citedSources / citationTag");
        assertTrue(section.sources().isEmpty(), "absent per-section sources must decode to an empty list");
        assertEquals(2, decoded3.sources().size());
        assertEquals("quick", decoded3.metadata().get("depth"));
        assertTrue(decoded3.metadata().containsKey("web_sources_count"),
                "a JSON-null metadata value must survive as a key with a null value");
        assertNull(decoded3.metadata().get("web_sources_count"));
        Number elapsed = assertInstanceOf(Number.class, decoded3.metadata().get("elapsed_seconds"),
                "a fractional metadata value must decode as a Number");
        assertEquals(267.32, elapsed.doubleValue());
    }

    @Test
    void deepResearchReportWithNothingSetDecodesToEmptyCollections() throws IOException {
        // The spec declares no field of the report required; the record normalises absent lists
        // and the metadata map to empty rather than null.
        DeepResearchReport decoded2 = jackson2.decode(bytes("{}"), DeepResearchReport.class);
        DeepResearchReport decoded3 = jackson3.decode(bytes("{}"), DeepResearchReport.class);
        assertEquals(decoded2, decoded3,
                "an empty DeepResearchReport must decode identically via both adapters");
        assertNull(decoded3.title(), "absent title must decode to null");
        assertTrue(decoded3.sections().isEmpty(), "absent sections must decode to an empty list");
        assertTrue(decoded3.sources().isEmpty(), "absent sources must decode to an empty list");
        assertTrue(decoded3.metadata().isEmpty(), "absent metadata must decode to an empty map");
    }

    @Test
    void reportChunkDecodesIdenticallyAcrossAdapters() throws IOException {
        // The spec's stream example: the finished report rides a chat.completion.chunk envelope
        // in place of `choices`; `object` is not modelled and both adapters must ignore it.
        String wire = """
                {
                  "id": "chatcmpl-a46c47",
                  "object": "chat.completion.chunk",
                  "created": 1789988223,
                  "model": "Fanar-Sadiq-2",
                  "report": {"title": "أهمية طلب العلم في الإسلام", "sections": [], "sources": []}
                }
                """;
        ReportChunk decoded2 = jackson2.decode(bytes(wire), ReportChunk.class);
        ReportChunk decoded3 = jackson3.decode(bytes(wire), ReportChunk.class);
        assertEquals(decoded2, decoded3,
                "ReportChunk decoded by both adapters must be record-equal");
        assertEquals("chatcmpl-a46c47", decoded3.id());
        assertEquals(1_789_988_223L, decoded3.created());
        assertEquals("Fanar-Sadiq-2", decoded3.model());
        assertEquals("أهمية طلب العلم في الإسلام", decoded3.report().title());
        assertTrue(decoded3.report().sections().isEmpty());
    }

    @Test
    void progressChunkWithoutAModelDecodesIdenticallyAcrossAdapters() throws IOException {
        // The spec's deep-research stream example opens with a progress event whose `model` is
        // JSON null; ProgressChunk allows that, and both flattening deserializers must map it
        // to null rather than the string "null".
        String wire = """
                {
                  "id": "chatcmpl-a46c47",
                  "object": "chat.completion.chunk",
                  "created": 1789987956,
                  "model": null,
                  "progress": {
                    "message": {
                      "en": "Analyzing topic and planning report structure...",
                      "ar": "تحليل الموضوع وتخطيط هيكل التقرير..."
                    }
                  }
                }
                """;
        ProgressChunk decoded2 = jackson2.decode(bytes(wire), ProgressChunk.class);
        ProgressChunk decoded3 = jackson3.decode(bytes(wire), ProgressChunk.class);
        assertEquals(decoded2, decoded3,
                "ProgressChunk without a model must decode identically via both adapters");
        assertNull(decoded3.model(), "a JSON-null model must decode to null, not the string \"null\"");
        assertEquals("Analyzing topic and planning report structure...", decoded3.message().en());
        assertEquals("تحليل الموضوع وتخطيط هيكل التقرير...", decoded3.message().ar());
        assertEquals(1_789_987_956L, decoded3.created());
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
        // Wire shape mirrors the spec: id, created, data[].{b64_json, revised, revised_prompt}
        // (all three required per the 2026-08 spec).
        String wire = "{\"id\":\"req_1\",\"created\":1700000000,"
                + "\"data\":[{\"b64_json\":\"aGVsbG8=\",\"revised\":true,"
                + "\"revised_prompt\":\"a refined sunset\"}]}";
        ImageGenerationResponse decoded2 = jackson2.decode(bytes(wire), ImageGenerationResponse.class);
        ImageGenerationResponse decoded3 = jackson3.decode(bytes(wire), ImageGenerationResponse.class);
        assertEquals(decoded2, decoded3,
                "ImageGenerationResponse decoded by both adapters must be record-equal");
        assertEquals("req_1", decoded3.id());
        assertEquals(1_700_000_000L, decoded3.created());
        assertEquals(1, decoded3.data().size());
        ImageGenerationItem item = decoded3.data().getFirst();
        assertEquals("aGVsbG8=", item.b64Json());
        assertTrue(item.revised());
        assertEquals("a refined sunset", item.revisedPrompt());
    }

    @Test
    void speechToTextResponseTextVariantDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_1\",\"text\":\"hello world\"}";
        SpeechToTextResponse decoded2 = jackson2.decode(bytes(wire), SpeechToTextResponse.class);
        SpeechToTextResponse decoded3 = jackson3.decode(bytes(wire), SpeechToTextResponse.class);
        assertEquals(decoded2, decoded3,
                "SpeechToTextResponse.Text must decode identically via both adapters");
        assertInstanceOf(SpeechToTextResponse.Text.class, decoded3);
        SpeechToTextResponse.Text text = (SpeechToTextResponse.Text) decoded3;
        assertEquals("req_1", text.id());
        assertEquals("hello world", text.text());
    }

    @Test
    void speechToTextResponseSrtVariantDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_2\",\"srt\":\"1\\n00:00:00,000 --> 00:00:01,000\\nhi\\n\"}";
        SpeechToTextResponse decoded2 = jackson2.decode(bytes(wire), SpeechToTextResponse.class);
        SpeechToTextResponse decoded3 = jackson3.decode(bytes(wire), SpeechToTextResponse.class);
        assertEquals(decoded2, decoded3,
                "SpeechToTextResponse.Srt must decode identically via both adapters");
        assertInstanceOf(SpeechToTextResponse.Srt.class, decoded3);
        SpeechToTextResponse.Srt srt = (SpeechToTextResponse.Srt) decoded3;
        assertEquals("req_2", srt.id());
        assertTrue(srt.srt().contains("hi"));
    }

    @Test
    void speechToTextResponseJsonVariantDecodesIdenticallyAcrossAdapters() throws IOException {
        String wire = "{\"id\":\"req_3\",\"json\":{\"segments\":["
                + "{\"speaker\":\"speaker_0\",\"start_time\":0.0,\"end_time\":1.5,"
                + "\"duration\":1.5,\"text\":\"hello\"}"
                + "]}}";
        SpeechToTextResponse decoded2 = jackson2.decode(bytes(wire), SpeechToTextResponse.class);
        SpeechToTextResponse decoded3 = jackson3.decode(bytes(wire), SpeechToTextResponse.class);
        assertEquals(decoded2, decoded3,
                "SpeechToTextResponse.Json must decode identically via both adapters");
        assertInstanceOf(SpeechToTextResponse.Json.class, decoded3);
        SpeechToTextResponse.Json json = (SpeechToTextResponse.Json) decoded3;
        assertEquals("req_3", json.id());
        assertEquals(1, json.segments().size());
        assertEquals("speaker_0", json.segments().getFirst().speaker());
        assertEquals(0.0, json.segments().getFirst().startTime());
        assertEquals(1.5, json.segments().getFirst().endTime());
        assertEquals(1.5, json.segments().getFirst().duration());
        assertEquals("hello", json.segments().getFirst().text());
    }

    @Test
    void textToSpeechRequestEncodesIdenticallyAcrossAdapters() throws IOException {
        TextToSpeechRequest req = TextToSpeechRequest.builder()
                .model(TtsModel.FANAR_AURA_TTS_2)
                .input("hello")
                .voice(Voice.RADWA)
                .responseFormat(TtsResponseFormat.WAV)
                .withEmotion(true)
                .build();
        Map<?, ?> shape2 = parseAsMap(encode(jackson2, req));
        Map<?, ?> shape3 = parseAsMap(encode(jackson3, req));
        assertEquals(shape2, shape3,
                "TextToSpeechRequest must encode to the same JSON shape via both adapters");
        assertEquals("Fanar-Aura-TTS-2", shape3.get("model"));
        assertEquals("Radwa", shape3.get("voice"));
        assertEquals("wav", shape3.get("response_format"));
        assertEquals(true, shape3.get("with_emotion"));
    }

    @Test
    void voiceResponseDecodesIdenticallyAcrossAdapters() throws IOException {
        // Wire shape mirrors the 2026-08 spec: rich voice objects with snake-case name_ar and
        // the public/personal type discriminator.
        String wire = "{\"voices\":["
                + "{\"name\":\"Amelia\",\"name_ar\":\"أميليا\",\"gender\":\"Female\","
                + "\"accent\":\"British\",\"languages\":[\"en\"],\"type\":\"public\",\"emotion\":false},"
                + "{\"name\":\"MyVoice\",\"languages\":[],\"type\":\"personal\",\"emotion\":false}"
                + "]}";
        VoiceResponse decoded2 = jackson2.decode(bytes(wire), VoiceResponse.class);
        VoiceResponse decoded3 = jackson3.decode(bytes(wire), VoiceResponse.class);
        assertEquals(decoded2, decoded3,
                "VoiceResponse decoded by both adapters must be record-equal");
        assertEquals(2, decoded3.voices().size());
        AvailableVoice amelia = decoded3.voices().getFirst();
        assertEquals("Amelia", amelia.name());
        assertEquals("أميليا", amelia.nameAr());
        assertEquals("Female", amelia.gender());
        assertEquals("British", amelia.accent());
        assertEquals(VoiceType.PUBLIC, amelia.type());
        AvailableVoice personal = decoded3.voices().get(1);
        assertEquals(VoiceType.PERSONAL, personal.type());
        assertTrue(personal.languages().isEmpty());
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
