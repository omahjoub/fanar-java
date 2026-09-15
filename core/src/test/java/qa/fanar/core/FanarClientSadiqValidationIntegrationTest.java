package qa.fanar.core;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Qur'an + hadith quotation validation through the <em>public</em> API:
 * {@code FanarClient.builder()} → {@code sadiq().validate()} → interceptor chain → real JDK
 * transport → a local {@link ScriptedHttpServer}. The seam-crossing proof ADR-028 promises.
 *
 * <p>What only a test at this level can show: the request actually lands on
 * {@code POST /v1/sadiq/validate} with the bearer token and the model on the wire, the tagged
 * response text survives decoding <em>verbatim</em> (the SDK deliberately does not parse it), an
 * unverified quotation comes back untagged, and the endpoint's authorization gate surfaces as a
 * typed exception routed by the envelope's {@code code} rather than by HTTP status. Both gate codes
 * Fanar is known to use are scripted below: the <strong>403</strong> {@code invalid_authorization}
 * this endpoint actually answers (observed 2026-09-15) and the <strong>422</strong>
 * {@code unprocessable} the same model's <em>chat</em> gate answers (2026-08-06). They are distinct
 * mechanisms — endpoint authorization versus model authorization — and the endpoint check
 * short-circuits, so a key granted one may still fail the other.</p>
 *
 * <p>The server is {@code @AutoClose}d after each test, which also fails the test if a scripted
 * reply was never requested or an unscripted request arrived — every hit count below is exact.</p>
 */
@Tag("integration")
class FanarClientSadiqValidationIntegrationTest {

    private static final String TAGGED =
            "<quran_start>x [45](https://quran.com/29/45)<quran_end> "
                    + "<hadith_start>y<hadith_end> [riyadussalihin:1](https://sunnah.com/riyadussalihin:1)";

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void validateReachesTheSadiqEndpointAndReturnsTheTaggedTextVerbatim() {
        server.enqueue(Reply.json(200, "{\"id\":\"v_1\",\"text\":\"" + TAGGED + "\"}"));

        SadiqValidationResponse response;
        try (FanarClient client = client()) {
            response = client.sadiq().validate(
                    SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "a quoted verse"));
        }

        assertEquals("v_1", response.id());
        assertEquals(TAGGED, response.text(), "the wire text must survive decoding unparsed");

        ScriptedHttpServer.Received sent = server.lastReceived();
        assertEquals("POST", sent.method());
        assertEquals("/v1/sadiq/validate", sent.path());
        assertEquals("Bearer sk_test", sent.header("Authorization"));
        assertEquals("application/json", sent.header("Content-Type"));
        assertTrue(sent.bodyAsString().contains("\"model\":\"Fanar-Sadiq-2\""),
                "model must reach the wire as its wire value, was: " + sent.bodyAsString());
        assertTrue(sent.bodyAsString().contains("\"text\":\"a quoted verse\""),
                "input text must reach the wire, was: " + sent.bodyAsString());
        assertEquals(1, server.hits());
    }

    @Test
    void quotationsThatCannotBeVerifiedComeBackUntagged() {
        server.enqueue(Reply.json(200, "{\"id\":\"v_2\",\"text\":\"an unverifiable quote\"}"));

        SadiqValidationResponse response;
        try (FanarClient client = client()) {
            response = client.sadiq().validate(
                    SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "an unverifiable quote"));
        }

        assertEquals("an unverifiable quote", response.text());
        assertTrue(!response.text().contains("<quran_start>") && !response.text().contains("<hadith_start>"),
                "absence of tags is the caller's signal that nothing was verified");
        assertEquals(1, server.hits());
    }

    @Test
    void theEndpointGateSurfacesAsAuthorizationException() {
        // The endpoint gate: observed live 2026-09-15, matching the spec.
        server.enqueue(Reply.json(403,
                "{\"error\":{\"code\":\"invalid_authorization\",\"message\":\"Invalid authorization\",\"status\":403}}"));
        try (FanarClient client = client()) {
            assertThrows(FanarAuthorizationException.class, () -> client.sadiq().validate(probe()));
        }
        assertEquals(1, server.hits());
    }

    @Test
    void theModelGateSurfacesUnprocessableAsObservedOnChat() {
        // The model gate, a different mechanism: Fanar-Sadiq-2 answers this on chat (2026-08-06).
        // Unreachable today because the endpoint gate fires first; scripted so a change is covered.
        server.enqueue(Reply.json(422,
                "{\"error\":{\"code\":\"unprocessable\",\"message\":\"Model not authorized\",\"status\":422}}"));
        try (FanarClient client = client()) {
            FanarUnprocessableException ex = assertThrows(FanarUnprocessableException.class,
                    () -> client.sadiq().validate(probe()));
            assertEquals(ErrorCode.UNPROCESSABLE, ex.code(), "routed by envelope code, not HTTP status");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void validateAsyncCrossesTheSameSeam() throws Exception {
        server.enqueue(Reply.json(200, "{\"id\":\"v_3\",\"text\":\"" + TAGGED + "\"}"));

        try (FanarClient client = client()) {
            SadiqValidationResponse response = client.sadiq()
                    .validateAsync(probe())
                    .get(10, TimeUnit.SECONDS);
            assertEquals("v_3", response.id());
            assertEquals(TAGGED, response.text());
        }
        assertEquals("/v1/sadiq/validate", server.lastReceived().path());
        assertEquals(1, server.hits());
    }

    @Test
    void validateAsyncCompletesExceptionallyWithTheTypedException() {
        server.enqueue(Reply.json(403,
                "{\"error\":{\"code\":\"invalid_authorization\",\"message\":\"Invalid authorization\",\"status\":403}}"));

        try (FanarClient client = client()) {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> client.sadiq().validateAsync(probe()).get(10, TimeUnit.SECONDS));
            assertInstanceOf(FanarAuthorizationException.class, ex.getCause());
        }
        assertEquals(1, server.hits());
    }

    // --- helpers

    private FanarClient client() {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(wireCodec())
                .retryPolicy(RetryPolicy.disabled())
                .build();
    }

    private static SadiqValidationRequest probe() {
        return SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "a quoted verse");
    }

    /**
     * Minimal real-JSON codec. Core has zero runtime dependencies and no Jackson on its test
     * classpath, so the adapters cannot be used here; this emits and reads the actual wire shape so
     * the body assertions above mean something. Cross-adapter agreement on these two records is
     * proved separately by {@code AdapterParityTest} in the {@code e2e} module.
     */
    private static FanarJsonCodec wireCodec() {
        return new FanarJsonCodec() {
            @Override
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return type.cast(new SadiqValidationResponse(field(json, "id"), field(json, "text")));
            }

            @Override
            public void encode(OutputStream out, Object value) throws IOException {
                SadiqValidationRequest r = (SadiqValidationRequest) value;
                out.write(("{\"model\":\"" + r.model().wireValue() + "\",\"text\":\"" + r.text() + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
            }

            /** Reads a flat string field. Sufficient for the payloads this test scripts. */
            private static String field(String json, String name) {
                String key = "\"" + name + "\":\"";
                int start = json.indexOf(key);
                if (start < 0) {
                    return null;
                }
                start += key.length();
                return json.substring(start, json.indexOf('"', start));
            }
        };
    }
}
