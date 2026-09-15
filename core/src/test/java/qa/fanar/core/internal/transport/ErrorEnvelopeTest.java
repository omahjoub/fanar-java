package qa.fanar.core.internal.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErrorEnvelopeTest {

    // --- well-formed envelopes

    @Test
    void parsesCodeMessageAndIgnoresStatus() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"error\":{\"code\":\"conflict\",\"message\":\"duplicate voice\",\"status\":409}}");
        assertEquals("conflict", e.code());
        assertEquals("duplicate voice", e.message());
    }

    @Test
    void messageIsOptional() {
        ErrorEnvelope e = ErrorEnvelope.tryParse("{\"error\":{\"code\":\"timeout\"}}");
        assertEquals("timeout", e.code());
        assertNull(e.message());
    }

    @Test
    void toleratesArbitraryWhitespace() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                " {\n\t\"error\" : { \"code\" : \"overloaded\" , \"message\" : \"busy\" }\r\n} ");
        assertEquals("overloaded", e.code());
        assertEquals("busy", e.message());
    }

    @Test
    void skipsForeignKeysOfEveryJsonType() {
        // string, number (with sign/exponent), object, array, booleans, null — before and after "error".
        ErrorEnvelope e = ErrorEnvelope.tryParse("""
                {"a":"x","b":-1.5e+10,"c":{"n":{"deep":[1,2]}},"d":[{"k":"v"},[3],"s"],
                 "error":{"code":"conflict","status":409,"extra":{"why":"dup"}},
                 "e":true,"f":false,"g":null,"h":2E8}""");
        assertEquals("conflict", e.code());
        assertNull(e.message());
    }

    @Test
    void skipsStringsContainingBracketsAndEscapedQuotes() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"noise\":{\"s\":\"}]\\\"{[\"},\"error\":{\"code\":\"timeout\"}}");
        assertEquals("timeout", e.code());
    }

    @Test
    void skipsEmptyContainers() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"a\":{},\"b\":[],\"error\":{\"code\":\"conflict\"}}");
        assertEquals("conflict", e.code());
    }

    @Test
    void decodesEveryEscapeSequence() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"error\":{\"code\":\"conflict\",\"message\":\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u0041\"}}");
        assertEquals("\" \\ / \b \f \n \r \t A", e.message());
    }

    @Test
    void lastDuplicateKeyWins() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"error\":{\"code\":\"timeout\",\"code\":\"conflict\"}}");
        assertEquals("conflict", e.code());
    }

    @Test
    void wireValueWithSpaceSurvives() {
        // ErrorCode.NOT_FOUND's wire value is literally "Not found".
        assertEquals("Not found", ErrorEnvelope.tryParse("{\"error\":{\"code\":\"Not found\"}}").code());
    }

    // --- the spec's nullable members (ADR-006 amendment 2026-09-15)

    @Test
    void parsesParamAndType() {
        ErrorEnvelope e = ErrorEnvelope.tryParse("""
                {"error":{"code":"content_filter","message":"blocked","status":400,\
                "param":"messages","type":"safety"}}""");
        assertEquals("content_filter", e.code());
        assertEquals("blocked", e.message());
        assertEquals("messages", e.param());
        assertEquals("safety", e.type());
    }

    @Test
    void parsesTheLive403ErrorObject() {
        // Observed 2026-09-15 (WIRE_OBSERVATIONS): all five spec members present, param and type
        // both null. This is the shape that makes stringOrNull() load-bearing — read with string(),
        // it fails the parse and silently drops a typed 403 to HTTP-status routing.
        //
        // The members are verbatim; the {"error":{…}} wrapper is ADR-006's documented envelope, NOT
        // something the captured body confirmed — the bug report quoted the inner object alone.
        // Confirming the wrapper is an open question for the live probe (WIRE_OBSERVATIONS).
        ErrorEnvelope e = ErrorEnvelope.tryParse("""
                {"error":{"code":"invalid_authorization","message":"Invalid authorization",\
                "status":403,"param":null,"type":null}}""");
        assertEquals("invalid_authorization", e.code());
        assertEquals("Invalid authorization", e.message());
        assertNull(e.param());
        assertNull(e.type());
    }

    @Test
    void jsonNullReadsAsAbsentInEveryMember() {
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"error\":{\"code\":\"conflict\",\"message\":null,\"param\":null,\"type\":null}}");
        assertEquals("conflict", e.code());
        assertNull(e.message());
        assertNull(e.param());
        assertNull(e.type());
    }

    @Test
    void anUnexpectedTypeInAnAuxiliaryMemberCostsThatMemberOnly() {
        // Only `code` is load-bearing — it picks the exception subtype. A message/param/type that
        // arrives as a number, object or array reads as absent rather than discarding the envelope
        // and dropping the response to HTTP-status routing.
        ErrorEnvelope e = ErrorEnvelope.tryParse(
                "{\"error\":{\"code\":\"conflict\",\"message\":123,\"param\":[1,2],\"type\":{\"a\":\"b\"}}}");
        assertEquals("conflict", e.code());
        assertNull(e.message());
        assertNull(e.param());
        assertNull(e.type());
    }

    @Test
    void jsonNullCodeYieldsNoEnvelope() {
        // Tolerated by the reader, then rejected by the same rule that rejects a missing code —
        // so the outcome is unchanged, the parse just no longer throws to get there.
        assertNull(ErrorEnvelope.tryParse("{\"error\":{\"code\":null,\"message\":\"m\"}}"));
    }

    // --- shape deviations → null (mapper falls back to status routing)

    @ParameterizedTest(name = "[{index}] {0}")
    @NullSource
    @ValueSource(strings = {
            "",                                          // blank
            "   \n ",                                    // blank
            "teapot",                                    // not JSON
            "[]",                                        // top-level array
            "42",                                        // top-level number
            "\"error\"",                                 // top-level string
            "{}",                                        // no "error" member
            "{\"error\":{}}",                            // no code
            "{\"error\":{\"message\":\"m\"}}",           // no code
            "{\"error\":\"nope\"}",                      // error not an object
            "{\"error\":{\"code\":123}}",                // code not a string
            "{\"error\":{\"code\":\"x\"",                // truncated before closes
            "{\"error\":{\"code\":\"x\"}}trailing",      // trailing garbage
            "{\"error\":{\"code\":\"x\"};",              // wrong member separator
            "{\"error\" {\"code\":\"x\"}}",              // missing colon
            "{\"error\":{\"code\":\"unterminated",       // unterminated string
            "{\"error\":{\"code\":\"\\q\"}}",            // unknown escape
            "{\"error\":{\"code\":\"\\uZZZZ\"}}",        // non-hex unicode escape
            "{\"error\":{\"code\":\"\\u12",              // unicode escape hits end of input
            "{\"a\":tru,\"error\":{\"code\":\"x\"}}",    // bad literal
            "{\"a\":?,\"error\":{\"code\":\"x\"}}",      // no value at all
            "{\"a\":123",                                // number runs to end of input
            "{\"a\":[1,2,\"error\":{\"code\":\"x\"}}",   // unbalanced container runs to end of input
    })
    void malformedOrForeignShapesYieldNull(String body) {
        assertNull(ErrorEnvelope.tryParse(body));
    }
}
