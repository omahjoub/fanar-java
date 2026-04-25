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
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.CapturingInterceptor;
import qa.fanar.e2e.Probes;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        // A canned wire response — the same shape Fanar returned in our smoke test.
        String wire = "{\"id\":\"c_1\",\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"content\":\"pong\",\"role\":\"assistant\","
                + "\"references\":null,\"tool_calls\":[]}}],"
                + "\"created\":1700000000,\"model\":\"Fanar-C-2-27B\","
                + "\"usage\":{\"completion_tokens\":2,\"prompt_tokens\":10,\"total_tokens\":12}}";

        ChatResponse decoded2 = jackson2.decode(bytes(wire), ChatResponse.class);
        ChatResponse decoded3 = jackson3.decode(bytes(wire), ChatResponse.class);
        assertEquals(decoded2, decoded3,
                "ChatResponse decoded by both adapters must be record-equal");
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
