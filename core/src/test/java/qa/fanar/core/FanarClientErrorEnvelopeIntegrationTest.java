package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Fanar error envelope through the <em>public</em> API: {@code FanarClient.builder()} →
 * {@code chat().send()} → interceptor chain → real JDK transport → a local
 * {@link ScriptedHttpServer} scripting the error responses.
 *
 * <p>This is the seam that hid a dead public API for four releases. {@code ExceptionMapperTest}
 * asserts the exception <em>class</em> for each envelope code and {@code FanarExceptionTest}
 * builds {@code FanarContentFilterException} directly with its 2-arg constructor — so both were
 * green while {@code ErrorEnvelope} dropped the envelope's {@code type} member and every exception
 * the SDK produced came back with {@code filterType() == null}. A unit test that hands the unit the
 * outcome it expects proves the unit, not the wiring (ADR-006 amendment 2026-09-15).</p>
 *
 * <p>Asserted here: the envelope's {@code type} reaches {@link FanarContentFilterException#filterType()}
 * on both construction sites — the typed-code route and the HTTP-400 fallback an unknown code falls
 * through to; an unknown {@code type} value decodes permissively rather than breaking the envelope
 * (ADR-015); and a JSON {@code null} in a spec-nullable member leaves typed-code routing intact.
 * That last one is a regression guard, not a hypothetical: {@code "type":null} and
 * {@code "param":null} are on the wire today (2026-09-15 403, recorded in
 * {@code docs/WIRE_OBSERVATIONS.md} under "Error envelope shape"), and reading them with the
 * parser's plain string reader throws, which nulls the whole envelope and silently drops the
 * response to HTTP-status routing.</p>
 *
 * <p>The server is {@code @AutoClose}d after each test, which also fails the test if a scripted
 * reply was never requested or an unscripted request arrived — every hit count below is exact.</p>
 */
@Tag("integration")
class FanarClientErrorEnvelopeIntegrationTest {

    /** Content filtering is never retried (ADR-014); short delays keep a regression from stalling. */
    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void contentFilterEnvelopeCarriesItsTypeThroughThePublicApi() {
        server.enqueue(Reply.json(400, """
                {"error":{"code":"content_filter","message":"blocked","status":400,\
                "param":null,"type":"safety"}}"""));

        try (FanarClient client = client()) {
            FanarContentFilterException e =
                    assertThrows(FanarContentFilterException.class, () -> client.chat().send(ping()));

            assertEquals(ContentFilterType.SAFETY, e.filterType(),
                    "the envelope's type member must reach filterType()");
            assertEquals("blocked", e.getMessage());
            assertEquals(ErrorCode.CONTENT_FILTER, e.code());
        }
        assertEquals(1, server.hits(), "content filtering is not retryable");
    }

    @Test
    void unknownCodeOn400StillCarriesTheTypeThroughTheStatusFallback() {
        // An envelope this SDK version cannot route by code falls through to HTTP-status routing,
        // which also builds a FanarContentFilterException — the second construction site.
        server.enqueue(Reply.json(400, """
                {"error":{"code":"flux_capacitor","message":"blocked","status":400,\
                "param":null,"type":"blocklist"}}"""));

        try (FanarClient client = client()) {
            FanarContentFilterException e =
                    assertThrows(FanarContentFilterException.class, () -> client.chat().send(ping()));

            assertEquals(ContentFilterType.BLOCKLIST, e.filterType(),
                    "the status-routed 400 must carry the type too");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void unknownFilterTypeDecodesPermissively() {
        // ADR-015: a wire value the SDK ships no constant for must decode, never break the envelope.
        server.enqueue(Reply.json(400, """
                {"error":{"code":"content_filter","message":"blocked","status":400,\
                "type":"policy_violation"}}"""));

        try (FanarClient client = client()) {
            FanarContentFilterException e =
                    assertThrows(FanarContentFilterException.class, () -> client.chat().send(ping()));

            assertEquals(ContentFilterType.of("policy_violation"), e.filterType());
            assertFalse(ContentFilterType.KNOWN.contains(e.filterType()),
                    "an unknown value must not masquerade as a bundled constant");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void absentTypeLeavesTheFilterTypeNull() {
        server.enqueue(Reply.json(400,
                "{\"error\":{\"code\":\"content_filter\",\"message\":\"blocked\",\"status\":400}}"));

        try (FanarClient client = client()) {
            FanarContentFilterException e =
                    assertThrows(FanarContentFilterException.class, () -> client.chat().send(ping()));

            assertNull(e.filterType(), "no type on the wire means no filter type");
        }
        assertEquals(1, server.hits());
    }

    @Test
    void jsonNullInASpecNullableMemberLeavesTypedCodeRoutingIntact() {
        // The regression guard. Status 418 is deliberately one the mapper does not know, so only
        // the envelope's code can produce a FanarConflictException: if the null literals broke the
        // parse, this would arrive as FanarInternalServerException("HTTP 418: …") instead.
        server.enqueue(Reply.json(418, """
                {"error":{"code":"conflict","message":"duplicate voice","status":418,\
                "param":null,"type":null}}"""));

        try (FanarClient client = client()) {
            FanarException e = assertThrows(FanarException.class, () -> client.chat().send(ping()));

            assertInstanceOf(FanarConflictException.class, e,
                    "a JSON null in param/type must not collapse the envelope to status routing");
            assertEquals("duplicate voice", e.getMessage());
        }
        assertEquals(1, server.hits());
    }

    // --- helpers -----------------------------------------------------------------------------

    private FanarClient client() {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(encodeOnlyCodec())
                .retryPolicy(FAST)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }

    /**
     * Every response here is an error, which {@code ExceptionMapper} parses by hand rather than
     * through the codec SPI (ADR-018: the envelope record lives in a package no codec can read).
     * So the codec only ever encodes — a decode call would mean the seam moved.
     */
    private static FanarJsonCodec encodeOnlyCodec() {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                throw new AssertionError("no 2xx is scripted in this test; decode must never run");
            }

            public void encode(OutputStream out, Object value) throws IOException {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
