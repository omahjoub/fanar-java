package qa.fanar.adk;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.testsupport.ScriptedHttpServer;

/** Shared fixtures: wire bodies in Fanar's shape and a client over the scripted server. */
final class TestSupport {

    static final RetryPolicy FAST_RETRY = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    /** One user tool: a retrieval the server already ran; one pending call awaiting a client. */
    static final String COMPLETION = """
            {"id":"resp-1","object":"chat.completion","created":1700000000,"model":"Fanar-Sadiq",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back",
               "references":[{"index":0,"number":1,"source":"Sahih al-Bukhari","content":"a quoted hadith"}],
               "tool_calls":[
                 {"id":"t1","name":"retrieve","arguments":{"q":"zakat"},"result":"three passages","is_error":false},
                 {"id":"t2","name":"lookup","arguments":{"q":"gold"},"result":null,"is_error":false}]}}],
             "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
            """;

    static final String PLAIN_COMPLETION = """
            {"id":"resp-2","object":"chat.completion","created":1700000000,"model":"Fanar",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    static final String TRUNCATED_COMPLETION = """
            {"id":"resp-3","object":"chat.completion","created":1700000000,"model":"Fanar",
             "choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant","content":""}}]}
            """;

    static String token(String text) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"Fanar\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":null,\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    static final String DONE = "data: {\"id\":\"c1\",\"created\":1700000000,\"model\":\"Fanar\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"delta\":{\"references\":"
            + "[{\"index\":0,\"number\":1,\"source\":\"Book\",\"content\":\"quote\"}]}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n";

    static final String ERROR_FRAME = "data: {\"id\":\"c1\",\"created\":1700000000,\"model\":\"Fanar\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"error\",\"delta\":{\"content\":\"boom\"}}]}\n\n";

    static final String DONE_SENTINEL = "data: [DONE]\n\n";

    private TestSupport() {
        // static only
    }

    /** No explicit codec: the runtime-scope Jackson 2 codec must be discoverable through ServiceLoader. */
    static FanarClient client(ScriptedHttpServer server, RetryPolicy policy) {
        return FanarClient.builder()
                .apiKey("test-key")
                .baseUrl(server.baseUri())
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .retryPolicy(policy)
                .build();
    }

    static FanarClient client(ScriptedHttpServer server) {
        return client(server, RetryPolicy.disabled());
    }

    static Content user(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    static Content model(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }

    static LlmRequest request(Content... contents) {
        return LlmRequest.builder().model("Fanar").contents(List.of(contents)).build();
    }

    static LlmRequest request(GenerateContentConfig config, Content... contents) {
        return LlmRequest.builder().model("Fanar").contents(List.of(contents)).config(config).build();
    }

    static Map<String, Object> args(String key, String value) {
        return Map.of(key, value);
    }
}
