package qa.fanar.adk;

import java.util.List;
import java.util.Map;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleMaps;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolCodeExecution;
import com.google.genai.types.ToolResponse;
import org.junit.jupiter.api.Test;

import qa.fanar.adk.UnsupportedFeature.Kind;
import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ImagePart;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.TextPart;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.chat.VideoPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static qa.fanar.adk.TestSupport.model;
import static qa.fanar.adk.TestSupport.request;
import static qa.fanar.adk.TestSupport.user;

class RequestMapperTest {

    private static final FanarLlmOptions REJECT = FanarLlmOptions.defaults();
    private static final FanarLlmOptions IGNORE = FanarLlmOptions.builder()
            .unsupportedFeatures(UnsupportedFeaturePolicy.IGNORE).build();

    /** A static no-arg tool, the shape {@code FunctionTool.create(Class, String)} accepts. */
    public static Map<String, Object> lookup() {
        return Map.of();
    }

    private static ChatRequest map(LlmRequest request, FanarLlmOptions options) {
        return RequestMapper.toChatRequest(request, ChatModel.FANAR, options);
    }

    private static UnsupportedFeature part(String detail) {
        return new UnsupportedFeature(Kind.PART, detail);
    }

    // --- messages ------------------------------------------------------------------------------

    @Test
    void systemInstructionRolesAndTextPartsMap() {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText("Be brief"), Part.fromText("and kind")))
                .build();
        Content twoParts = Content.builder().role("user")
                .parts(List.of(Part.fromText("line one"), Part.fromText("line two"))).build();
        Content assistant = Content.builder().role("assistant").parts(List.of(Part.fromText("pong"))).build();
        Content noRole = Content.builder().parts(List.of(Part.fromText("no role"))).build();

        ChatRequest chat = map(request(config, twoParts, model("earlier"), assistant, noRole), REJECT);

        assertEquals(ChatModel.FANAR, chat.model());
        assertEquals(List.of(
                SystemMessage.of("Be brief\nand kind"),
                UserMessage.of("line one\nline two"),
                AssistantMessage.of("earlier"),
                AssistantMessage.of("pong"),
                UserMessage.of("no role")), chat.messages(), "one joining rule: text parts become lines");
    }

    @Test
    void blankSystemInstructionAndEmptyTurnsAreSkipped() {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.builder().build()))
                .build();
        Content emptyUser = Content.builder().role("user").parts(List.of(Part.builder().build())).build();
        Content thoughtOnly = Content.builder().role("model")
                .parts(List.of(Part.builder().text("thinking").thought(true).build())).build();
        Content noParts = Content.builder().role("model").build();

        ChatRequest chat = map(request(config, emptyUser, thoughtOnly, noParts, user("hi")), REJECT);

        assertEquals(List.of(UserMessage.of("hi")), chat.messages());
    }

    @Test
    void fileDataMapsImagesAndVideosByUrl() {
        Content content = Content.builder().role("user").parts(List.of(
                Part.fromText("look"),
                Part.fromUri("https://x/img.png", "image/png"),
                Part.fromUri("https://x/clip.mp4", "video/mp4"))).build();

        ChatRequest chat = map(request(content), REJECT);

        assertEquals(List.of(new UserMessage(List.of(
                new TextPart("look"), ImagePart.of("https://x/img.png"), new VideoPart("https://x/clip.mp4")), null)),
                chat.messages());
    }

    @Test
    void mediaOnlyTurnStillMaps() {
        Content content = Content.builder().role("user")
                .parts(List.of(Part.fromUri("https://x/img.png", "image/png"))).build();

        ChatRequest chat = map(request(content), REJECT);

        assertEquals(List.of(new UserMessage(List.of(ImagePart.of("https://x/img.png")), null)), chat.messages());
    }

    @Test
    void partsWithoutAFanarMappingAreRejectedByKindAndName() {
        Content content = Content.builder().role("user").parts(List.of(
                Part.fromUri("https://x/doc.pdf", "application/pdf"),
                Part.builder().fileData(FileData.builder().mimeType("image/png").build()).build(),
                Part.fromBytes(new byte[] {1}, "image/png"),
                Part.builder().inlineData(Blob.builder().data(new byte[] {1}).build()).build(),
                Part.fromFunctionCall("f", Map.of()),
                Part.fromFunctionResponse("f", Map.of()),
                Part.builder().toolCall(ToolCall.builder().build()).build(),
                Part.builder().toolResponse(ToolResponse.builder().build()).build(),
                Part.builder().executableCode(ExecutableCode.builder().code("1").build()).build(),
                Part.builder().codeExecutionResult(CodeExecutionResult.builder().output("1").build()).build(),
                Part.fromText("text"))).build();
        Content modelMedia = Content.builder().role("model")
                .parts(List.of(Part.fromText("see"), Part.fromUri("https://x/img.png", "image/png"))).build();

        UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class,
                () -> map(request(content, modelMedia), REJECT));

        assertEquals(List.of(
                part("file data of type 'application/pdf'"),
                part("file data without a URI"),
                part("inline data of type 'image/png'"),
                part("inline data of type 'unknown'"),
                part("a function call part"),
                part("a function response part"),
                part("a tool call part"),
                part("a tool response part"),
                part("a code execution part"),
                part("media in a model turn")), ex.features());
    }

    @Test
    void ignoreDropsUnsupportedPartsAndKeepsTheRest() {
        Content content = Content.builder().role("user").parts(List.of(
                Part.fromBytes(new byte[] {1}, "image/png"), Part.fromText("text"))).build();
        Content modelMedia = Content.builder().role("model")
                .parts(List.of(Part.fromText("see"), Part.fromUri("https://x/img.png", "image/png"))).build();

        ChatRequest chat = map(request(content, modelMedia), IGNORE);

        assertEquals(List.of(UserMessage.of("text"), AssistantMessage.of("see")), chat.messages());
    }

    @Test
    void ignoreStillRefusesWhenNothingSendableIsLeft() {
        Content imageOnly = Content.builder().role("user")
                .parts(List.of(Part.fromBytes(new byte[] {1}, "image/png"))).build();

        UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class,
                () -> map(request(imageOnly), IGNORE));

        assertEquals(List.of(part("inline data of type 'image/png'")), ex.features());
        assertEquals(UnsupportedFeaturePolicy.IGNORE, ex.policy());
        assertEquals("Fanar cannot honour inline data of type 'image/png' - and nothing sendable remains once they are dropped",
                ex.getMessage());
    }

    // --- tools and structured output -----------------------------------------------------------

    @Test
    void declaredAndConfiguredToolsAreRejectedByName() {
        BaseTool tool = FunctionTool.create(RequestMapperTest.class, "lookup");
        GenerateContentConfig config = GenerateContentConfig.builder()
                .tools(List.of(Tool.builder().functionDeclarations(List.of(
                        FunctionDeclaration.builder().name("search").build(),
                        FunctionDeclaration.builder().name("lookup").build(),
                        FunctionDeclaration.builder().build())).build()))
                .build();
        LlmRequest request = LlmRequest.builder().model("Fanar").contents(List.of(user("hi")))
                .config(config).tools(Map.of("lookup", tool)).build();

        UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class, () -> map(request, REJECT));

        assertEquals(List.of(new UnsupportedFeature(Kind.TOOL, "tool 'lookup'"),
                new UnsupportedFeature(Kind.TOOL, "tool 'search'")), ex.features());
        assertEquals(List.of(UserMessage.of("hi")), map(request, IGNORE).messages());
    }

    @Test
    void builtInToolsWithoutDeclarationsAreRejectedBySlot() {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .tools(List.of(
                        Tool.builder().codeExecution(ToolCodeExecution.builder().build()).build(),
                        Tool.builder().googleMaps(GoogleMaps.builder().build()).build(),
                        Tool.builder().build()))
                .build();

        UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class,
                () -> map(request(config, user("hi")), REJECT));

        assertEquals(List.of(
                new UnsupportedFeature(Kind.TOOL, "a built-in tool (codeExecution)"),
                new UnsupportedFeature(Kind.TOOL, "a built-in tool (googleMaps)"),
                new UnsupportedFeature(Kind.TOOL, "a built-in tool (unknown)")), ex.features());
    }

    @Test
    void outputSchemasAndNonTextMimeTypesAreRejected() {
        GenerateContentConfig schema = GenerateContentConfig.builder()
                .responseSchema(Schema.builder().type("OBJECT").build())
                .responseMimeType("application/json")
                .build();
        GenerateContentConfig jsonSchema = GenerateContentConfig.builder()
                .responseJsonSchema(Map.of("type", "object"))
                .build();
        GenerateContentConfig mimeOnly = GenerateContentConfig.builder().responseMimeType("application/json").build();
        GenerateContentConfig plain = GenerateContentConfig.builder().responseMimeType("text/plain").build();
        UnsupportedFeature outputSchema = new UnsupportedFeature(Kind.OUTPUT_SCHEMA, "an output schema");

        assertEquals(List.of(outputSchema), assertThrows(UnsupportedFeatureException.class,
                () -> map(request(schema, user("hi")), REJECT)).features());
        assertEquals(List.of(outputSchema), assertThrows(UnsupportedFeatureException.class,
                () -> map(request(jsonSchema, user("hi")), REJECT)).features());
        assertEquals(List.of(new UnsupportedFeature(Kind.OUTPUT_SCHEMA, "response MIME type 'application/json'")),
                assertThrows(UnsupportedFeatureException.class,
                        () -> map(request(mimeOnly, user("hi")), REJECT)).features());
        assertEquals(List.of(UserMessage.of("hi")), map(request(plain, user("hi")), REJECT).messages());
        assertEquals(List.of(UserMessage.of("hi")), map(request(schema, user("hi")), IGNORE).messages());
    }

    // --- knobs ---------------------------------------------------------------------------------

    @Test
    void configKnobsAreForwardedWithDecimalFloatsExceptCandidateCount() {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.7f).topP(0.9f).topK(40f).maxOutputTokens(16).candidateCount(2)
                .stopSequences(List.of("END")).presencePenalty(0.3f).frequencyPenalty(0.1f)
                .responseLogprobs(true).logprobs(3)
                .build();

        ChatRequest chat = map(request(config, user("hi")), REJECT);

        assertEquals(0.7, chat.temperature(), "0.7f arrives as 0.7, not 0.699999988079071");
        assertEquals(0.9, chat.topP());
        assertEquals(40, chat.topK());
        assertEquals(16, chat.maxTokens());
        assertNull(chat.n(), "ADK carries one candidate, so candidateCount is not forwarded");
        assertEquals(List.of("END"), chat.stop());
        assertEquals(0.3, chat.presencePenalty());
        assertEquals(0.1, chat.frequencyPenalty());
        assertEquals(true, chat.logprobs());
        assertEquals(3, chat.topLogprobs());
        assertEquals(0.7, RequestMapper.decimal(0.7f));
    }

    @Test
    void absentConfigAndEmptyStopSequencesSetNothing() {
        ChatRequest noConfig = map(request(user("hi")), REJECT);
        ChatRequest emptyStop = map(request(GenerateContentConfig.builder().stopSequences(List.of()).build(),
                user("hi")), REJECT);

        assertNull(noConfig.temperature());
        assertNull(noConfig.stop());
        assertNull(emptyStop.stop());
        assertNull(emptyStop.persona());
    }

    @Test
    void optionsAreAppliedWhenSet() {
        FanarLlmOptions options = FanarLlmOptions.builder()
                .persona("scholar").madhab(List.of(Madhab.HANAFI)).enableThinking(true).restrictToIslamic(true)
                .bookNames(List.of(qa.fanar.core.chat.BookName.of("Sahih Muslim")))
                .preferredSources(List.of(qa.fanar.core.chat.Source.of("quran")))
                .excludeSources(List.of(qa.fanar.core.chat.Source.of("web")))
                .filterSources(List.of(qa.fanar.core.chat.Source.of("hadith")))
                .logitBias(Map.of("1", 0.5)).minP(0.2).repetitionPenalty(1.2).bestOf(2).lengthPenalty(0.8)
                .earlyStopping(true).stopTokenIds(List.of(9)).ignoreEos(true).minTokens(1)
                .skipSpecialTokens(true).spacesBetweenSpecialTokens(true).truncatePromptTokens(50).promptLogprobs(1)
                .build();

        ChatRequest chat = map(request(user("hi")), options);

        assertEquals("scholar", chat.persona());
        assertEquals(List.of(Madhab.HANAFI), chat.madhab());
        assertEquals(true, chat.enableThinking());
        assertEquals(true, chat.restrictToIslamic());
        assertEquals(List.of(qa.fanar.core.chat.BookName.of("Sahih Muslim")), chat.bookNames());
        assertEquals(List.of(qa.fanar.core.chat.Source.of("quran")), chat.preferredSources());
        assertEquals(List.of(qa.fanar.core.chat.Source.of("web")), chat.excludeSources());
        assertEquals(List.of(qa.fanar.core.chat.Source.of("hadith")), chat.filterSources());
        assertEquals(Map.of("1", 0.5), chat.logitBias());
        assertEquals(0.2, chat.minP());
        assertEquals(1.2, chat.repetitionPenalty());
        assertEquals(2, chat.bestOf());
        assertEquals(0.8, chat.lengthPenalty());
        assertEquals(true, chat.earlyStopping());
        assertEquals(List.of(9), chat.stopTokenIds());
        assertEquals(true, chat.ignoreEos());
        assertEquals(1, chat.minTokens());
        assertEquals(true, chat.skipSpecialTokens());
        assertEquals(true, chat.spacesBetweenSpecialTokens());
        assertEquals(50, chat.truncatePromptTokens());
        assertEquals(1, chat.promptLogprobs());
    }
}
