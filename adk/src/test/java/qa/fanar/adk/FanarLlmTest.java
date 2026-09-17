package qa.fanar.adk;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FanarLlmTest {

    @Test
    void exposesTheWireIdTheModelAndTheOptions() {
        try (FanarClient client = FanarClient.builder().apiKey("k").build()) {
            FanarLlmOptions options = FanarLlmOptions.builder().persona("p").build();

            assertEquals("Fanar-C-2-27B", new FanarLlm(client, ChatModel.FANAR_C_2_27B).model());
            assertSame(ChatModel.FANAR, new FanarLlm(() -> client, ChatModel.FANAR).chatModel());
            assertSame(options, new FanarLlm(client, ChatModel.FANAR, options).options());
            assertEquals(UnsupportedFeaturePolicy.REJECT,
                    new FanarLlm(() -> client, ChatModel.FANAR).options().unsupportedFeatures());
        }
    }

    @Test
    void rejectsMissingArguments() {
        try (FanarClient client = FanarClient.builder().apiKey("k").build()) {
            assertThrows(NullPointerException.class, () -> new FanarLlm((FanarClient) null, ChatModel.FANAR));
            assertThrows(NullPointerException.class, () -> new FanarLlm(client, null));
            assertThrows(NullPointerException.class, () -> new FanarLlm(client, ChatModel.FANAR, null));
            assertThrows(NullPointerException.class, () -> new FanarLlm((java.util.function.Supplier<FanarClient>) null,
                    ChatModel.FANAR, FanarLlmOptions.defaults()));
            assertThrows(NullPointerException.class, () -> new FanarLlm(client, ChatModel.FANAR).generateContent(null, false));
            assertThrows(NullPointerException.class, () -> FanarLlm.register((FanarClient) null));
            assertThrows(NullPointerException.class, () -> FanarLlm.register(null, FanarLlmOptions.defaults()));
            assertThrows(NullPointerException.class, () -> FanarLlm.register(() -> client, null));
        }
    }

    @Test
    void liveConnectionsAreNotSupported() {
        try (FanarClient client = FanarClient.builder().apiKey("k").build()) {
            FanarLlm model = new FanarLlm(client, ChatModel.FANAR);
            assertThrows(UnsupportedOperationException.class, () -> model.connect(null));
        }
    }
}
