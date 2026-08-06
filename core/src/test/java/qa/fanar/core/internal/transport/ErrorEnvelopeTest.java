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
