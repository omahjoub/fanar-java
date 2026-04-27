package qa.fanar.spring.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link FanarChatModel}. We spin up a real {@link HttpServer} and a real
 * {@link FanarClient} against it, then drive the adapter through Spring AI's {@link Prompt}.
 * This exercises the full request-mapping + response-mapping path without mocks — the same
 * code paths that fire in production.
 */
class FanarChatModelTest {

    private HttpServer server;
    private FanarClient client;
    private String capturedRequestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(null);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callMapsPromptOntoChatRequestAndReturnsAssistantText() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {
                      "id":"resp-1",
                      "model":"Fanar",
                      "created":1700000000,
                      "choices":[
                        {"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatResponse response = model.call(new Prompt(List.of(new UserMessage("hello"))));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("hello back");
        assertThat(response.getMetadata().getId()).isEqualTo("resp-1");
        assertThat(response.getMetadata().getModel()).isEqualTo("Fanar");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
        assertThat(capturedRequestBody).contains("\"role\":\"user\"").contains("\"content\":\"hello\"");
    }

    @Test
    void mixedRoleMessagesAllForwarded() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        model.call(new Prompt(List.of(
                new SystemMessage("you are concise"),
                new UserMessage("hi"),
                new AssistantMessage("hi back"),
                new UserMessage("again"))));

        assertThat(capturedRequestBody)
                .contains("\"role\":\"system\"")
                .contains("you are concise")
                .contains("\"role\":\"assistant\"")
                .contains("hi back");
    }

    @Test
    void chatOptionsForwardSamplingKnobs() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatOptions options = ChatOptions.builder()
                .temperature(0.3)
                .topP(0.9)
                .maxTokens(64)
                .stopSequences(List.of("END"))
                .build();
        model.call(new Prompt(List.of(new UserMessage("x")), options));

        assertThat(capturedRequestBody)
                .contains("\"temperature\":0.3")
                .contains("\"top_p\":0.9")
                .contains("\"max_tokens\":64")
                .contains("\"stop\":[\"END\"]");
    }

    @Test
    void chatOptionsModelOverridesDefault() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        // Default is FANAR; per-prompt overrides to Fanar-S-1-7B.
        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatOptions options = ChatOptions.builder().model("Fanar-S-1-7B").build();
        model.call(new Prompt(List.of(new UserMessage("x")), options));

        assertThat(capturedRequestBody).contains("\"model\":\"Fanar-S-1-7B\"");
    }

    @Test
    void streamProducesOneChatResponsePerTokenChunkPlusDone() {
        server.createContext("/v1/chat/completions", exchange -> {
            // Fanar wire format for streaming: SSE with `data: {...}` frames terminated by
            // `data: [DONE]`. Stream events are discriminated by shape (see StreamEventDecoder):
            // top-level `usage` or `metadata` → DoneChunk; `delta.content` → TokenChunk.
            String sse = """
                    data: {"id":"r1","model":"Fanar","created":1,"choices":[{"index":0,"delta":{"content":"hello"}}]}

                    data: {"id":"r1","model":"Fanar","created":1,"choices":[{"index":0,"delta":{"content":" world"}}]}

                    data: {"id":"r1","model":"Fanar","created":1,"choices":[{"index":0,"finish_reason":"stop"}],"metadata":{}}

                    data: [DONE]

                    """;
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        Flux<ChatResponse> flux = model.stream(new Prompt(List.of(new UserMessage("x"))));
        List<ChatResponse> chunks = flux.collectList().block(Duration.ofSeconds(5));

        assertThat(chunks).hasSize(3);              // 2 token chunks + 1 done chunk
        assertThat(chunks.get(0).getResult().getOutput().getText()).isEqualTo("hello");
        assertThat(chunks.get(1).getResult().getOutput().getText()).isEqualTo(" world");
        assertThat(chunks.get(2).getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    }

    @Test
    void toolMessagesAreSilentlyDropped() {
        // Spring AI's tool-callback advisor stack injects ToolResponseMessages into the prompt
        // when a tool fires. Fanar's wire format rejects user-supplied tools, so the adapter
        // skips them — the rest of the conversation still goes through.
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        org.springframework.ai.chat.messages.ToolResponseMessage toolMessage =
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                        .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                                "tool-id", "lookup", "tool-output")))
                        .build();
        model.call(new Prompt(List.of(new UserMessage("ping"), toolMessage)));

        // Only the user message survives; the tool response is dropped.
        assertThat(capturedRequestBody).contains("\"role\":\"user\"").contains("ping");
        assertThat(capturedRequestBody).doesNotContain("tool-output");
    }

    @Test
    void topKAndPenaltyOptionsForwarded() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatOptions options = ChatOptions.builder()
                .topK(40)
                .frequencyPenalty(0.5)
                .presencePenalty(0.4)
                .build();
        model.call(new Prompt(List.of(new UserMessage("x")), options));

        assertThat(capturedRequestBody)
                .contains("\"top_k\":40")
                .contains("\"frequency_penalty\":0.5")
                .contains("\"presence_penalty\":0.4");
    }

    @Test
    void emptyOrBlankModelOptionFallsBackToDefault() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR_S_1_7B);
        // Blank model string should not override the constructor default.
        ChatOptions options = ChatOptions.builder().model("   ").build();
        model.call(new Prompt(List.of(new UserMessage("x")), options));

        assertThat(capturedRequestBody).contains("\"model\":\"Fanar-S-1-7B\"");
    }

    @Test
    void emptyStopSequencesAreNotForwarded() {
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = okResponse("ok");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatOptions options = ChatOptions.builder().stopSequences(List.of()).build();
        model.call(new Prompt(List.of(new UserMessage("x")), options));

        // Empty list should be skipped — sending [] would set the wire field to an empty array.
        assertThat(capturedRequestBody).doesNotContain("\"stop\"");
    }

    @Test
    void emptyAssistantContentReturnsEmptyReply() {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "id":"r","model":"Fanar","created":1,
                      "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":[]}}]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        ChatResponse response = model.call(new Prompt(List.of(new UserMessage("x"))));

        assertThat(response.getResult().getOutput().getText()).isEmpty();
    }

    @Test
    void streamMapsErrorChunkAndDropsProgressChunks() {
        // Token + progress (dropped) + error. Tool-call / tool-result variants are exercised
        // by a separate unit test (toSpringAiChunkHandlesAllStreamEventVariants) that drives
        // the projector directly — their wire format is fiddly to hand-craft and the value here
        // is just confirming the switch covers each StreamEvent subtype.
        server.createContext("/v1/chat/completions", exchange -> {
            String sse = """
                    data: {"id":"r","model":"Fanar","created":1,"choices":[{"index":0,"delta":{"content":"hi"}}]}

                    data: {"id":"r","model":"Fanar","created":1,"progress":{"message":{"en":"searching","ar":"بحث"}}}

                    data: {"id":"r","model":"Fanar","created":1,"choices":[{"index":0,"finish_reason":"error","delta":{"content":"boom"}}]}

                    data: [DONE]

                    """;
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = clientFor(server);

        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        List<ChatResponse> chunks = model.stream(new Prompt(List.of(new UserMessage("x"))))
                .collectList().block(Duration.ofSeconds(5));

        // 3 events in → 2 ChatResponses out (token + error). Progress dropped.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getResult().getOutput().getText()).isEqualTo("hi");
        assertThat(chunks.get(1).getResult().getMetadata().getFinishReason()).isEqualTo("error");
    }

    @Test
    void toSpringAiChunkHandlesAllStreamEventVariants() {
        // Drive the projector directly with synthetic StreamEvents, avoiding Fanar's SSE wire
        // format. ToolCallChunk and ToolResultChunk should map to null (filtered by stream()'s
        // mapNotNull); the others have already been covered through the SSE path.
        var toolCall = new qa.fanar.core.chat.ToolCallChunk(
                "r", 1L, "Fanar",
                List.of(new qa.fanar.core.chat.ChoiceToolCall(0, null,
                        List.of(new qa.fanar.core.chat.ToolCallData(
                                0, "tc-1", "function",
                                new qa.fanar.core.chat.FunctionData("f", "{}"))))));
        var toolResult = new qa.fanar.core.chat.ToolResultChunk(
                "r", 1L, "Fanar",
                List.of(new qa.fanar.core.chat.ChoiceToolResult(0, null,
                        new qa.fanar.core.chat.ToolResultData(
                                "tc-1", "f", java.util.Map.of(), "{\"x\":1}", null, false))));

        assertThat(FanarChatModel.toSpringAiChunk(toolCall)).isNull();
        assertThat(FanarChatModel.toSpringAiChunk(toolResult)).isNull();
    }

    @Test
    void nullPromptIsRejected() {
        client = clientFor(server);
        FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
        // Cast disambiguates against ChatModel's default `call(Message...)` / `stream(Message...)`
        // overloads — without it, javac can't tell which overload `null` is meant for.
        assertThat(catchThrowable(() -> model.call((Prompt) null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> model.stream((Prompt) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNulls() {
        client = clientFor(server);
        assertThat(catchThrowable(() -> new FanarChatModel(null, ChatModel.FANAR)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new FanarChatModel(client, null)))
                .isInstanceOf(NullPointerException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }

    private static byte[] okResponse(String content) {
        return ("""
                {
                  "id":"resp",
                  "model":"Fanar",
                  "created":1700000000,
                  "choices":[
                    {"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}
                  ]
                }
                """).formatted(content).getBytes(StandardCharsets.UTF_8);
    }

    private static FanarClient clientFor(HttpServer server) {
        URI base = URI.create("http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort());
        return FanarClient.builder()
                .apiKey("test-key")
                .baseUrl(base)
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(2))
                .retryPolicy(RetryPolicy.disabled())
                .jsonCodec(new Jackson3FanarJsonCodec())
                .build();
    }
}
