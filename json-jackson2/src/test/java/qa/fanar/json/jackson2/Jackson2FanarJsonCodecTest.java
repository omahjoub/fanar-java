package qa.fanar.json.jackson2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jackson2FanarJsonCodecTest {

    @Test
    void encodesChatRequestUsingSnakeCase() throws IOException {
        ChatRequest request = ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("hi"))
                .maxTokens(64)
                .build();

        String json = encode(new Jackson2FanarJsonCodec(), request);

        assertTrue(json.contains("\"max_tokens\":64"), "snake-case naming for max_tokens: " + json);
        assertTrue(json.contains("\"model\":\"Fanar\""), "model wire value: " + json);
        // Unset optional fields must not appear with `null` — inclusion is NON_NULL.
        assertFalse(json.contains("null"), "no null fields should leak: " + json);
    }

    @Test
    void encodeDecodeChatResponseRoundTrip() throws IOException {
        Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();
        ChatMessage msg = new ChatMessage(List.of(), List.of(), List.of());
        ChatChoice choice = new ChatChoice(FinishReason.STOP, 0, msg, null);
        ChatResponse original = new ChatResponse("c_1", List.of(choice), 1_700_000_000L, "Fanar", null, null);

        String wire = encode(codec, original);
        ChatResponse round = codec.decode(bytes(wire), ChatResponse.class);

        assertEquals(original.id(), round.id());
        assertEquals(original.model(), round.model());
        assertEquals(1, round.choices().size());
        assertEquals(FinishReason.STOP, round.choices().getFirst().finishReason());
    }

    @Test
    void ignoresUnknownPropertiesOnDecode() throws IOException {
        Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();
        String wire = "{\"id\":\"c_9\",\"choices\":[],\"created\":0,\"model\":\"Fanar\","
                + "\"some_future_field\":{\"nested\":42}}";
        ChatResponse response = codec.decode(bytes(wire), ChatResponse.class);
        assertEquals("c_9", response.id());
    }

    @Test
    void decodeWrapsJacksonFailureAsIoException() {
        Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();
        IOException ex = assertThrows(IOException.class,
                () -> codec.decode(bytes("{not valid json"), ChatResponse.class));
        assertTrue(ex.getMessage().contains("ChatResponse"), "message: " + ex.getMessage());
    }

    @Test
    void encodeWrapsJacksonFailureAsIoException() {
        Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();
        OutputStream broken = new OutputStream() {
            public void write(int b) throws IOException { throw new IOException("broken"); }
        };
        ChatRequest minimal = ChatRequest.builder()
                .model(ChatModel.FANAR).addMessage(UserMessage.of("x")).build();
        IOException ex = assertThrows(IOException.class, () -> codec.encode(broken, minimal));
        assertTrue(ex.getMessage().contains("encode"), "message: " + ex.getMessage());
    }

    @Test
    void defaultMapperIsPreConfigured() throws IOException {
        ObjectMapper mapper = Jackson2FanarJsonCodec.defaultMapper();
        Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec(mapper);

        ChatResponse original = new ChatResponse("c_b", List.of(), 0L, "Fanar", null, null);
        String wire = encode(codec, original);
        ChatResponse round = codec.decode(bytes(wire), ChatResponse.class);

        assertEquals("c_b", round.id());
    }

    @Test
    void publicFlatteningModuleIsReusable() {
        // Bare mapper + fanarFlatteningModule must be a well-formed construction path.
        ObjectMapper bare = new ObjectMapper();
        bare.registerModule(Jackson2FanarJsonCodec.fanarFlatteningModule());
        assertNotNull(bare);
    }

    @Test
    void rejectsNullMapper() {
        assertThrows(NullPointerException.class, () -> new Jackson2FanarJsonCodec(null));
    }

    @Test
    void classpathServiceDescriptorPointsAtThisCodec() throws IOException {
        try (InputStream in = Jackson2FanarJsonCodec.class.getResourceAsStream(
                "/META-INF/services/qa.fanar.core.spi.FanarJsonCodec")) {
            assertNotNull(in, "META-INF/services descriptor must ship in the jar");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals("qa.fanar.json.jackson2.Jackson2FanarJsonCodec", content);
        }
    }

    // --- helpers

    private static String encode(FanarJsonCodec codec, Object value) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        codec.encode(buf, value);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
