package qa.fanar.core.internal.transport;

/**
 * The typed Fanar error envelope: {@code {"error":{"code":"…","message":"…","status":N}}}.
 *
 * <p>Parsed by a small hand-rolled scanner rather than the {@link
 * qa.fanar.core.spi.FanarJsonCodec} SPI: codec implementations reflect over target types, and
 * this package is deliberately not exported (ADR-018), so a codec running as a JPMS module could
 * not access an envelope DTO defined here. The envelope is a three-field, spec-pinned shape; the
 * scanner is strict about JSON syntax but any deviation from the expected shape yields
 * {@code null}, letting the {@link ExceptionMapper} fall back to HTTP-status routing.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @param code    the wire value of the error code; never {@code null} (a parse without a code
 *                yields no envelope)
 * @param message the human-readable server message, or {@code null} when absent
 * @author Oussama Mahjoub
 */
record ErrorEnvelope(String code, String message) {

    /**
     * Parse an error-response body into an envelope.
     *
     * @param body the raw response body; may be anything (HTML error pages, plain text, blank)
     * @return the envelope, or {@code null} when {@code body} is not a well-formed envelope
     */
    static ErrorEnvelope tryParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return new Parser(body).parseEnvelope();
        } catch (MalformedException | NumberFormatException e) {
            return null;
        }
    }

    /** Signals any deviation from well-formed envelope JSON. Carries no stack trace. */
    private static final class MalformedException extends RuntimeException {
        MalformedException() {
            super(null, null, false, false);
        }
    }

    /** Single-pass, iterative (non-recursive) scanner over the envelope shape. */
    private static final class Parser {

        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        ErrorEnvelope parseEnvelope() {
            String code = null;
            String message = null;
            ws();
            expect('{');
            ws();
            if (!consumeIf('}')) {
                do {
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    if (key.equals("error")) {
                        expect('{');
                        ws();
                        if (!consumeIf('}')) {
                            do {
                                String errorKey = string();
                                ws();
                                expect(':');
                                ws();
                                switch (errorKey) {
                                    case "code" -> code = string();
                                    case "message" -> message = string();
                                    default -> skipValue();
                                }
                            } while (commaOrEnd('}'));
                        }
                    } else {
                        skipValue();
                    }
                } while (commaOrEnd('}'));
            }
            ws();
            if (i != s.length()) {
                throw new MalformedException();
            }
            return code == null ? null : new ErrorEnvelope(code, message);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new MalformedException();
            }
            return s.charAt(i);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new MalformedException();
            }
            i++;
        }

        private boolean consumeIf(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        /** After a member: consumes {@code ','} (more members follow) or {@code close} (done). */
        private boolean commaOrEnd(char close) {
            ws();
            if (consumeIf(',')) {
                ws();
                return true;
            }
            expect(close);
            return false;
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escape = peek();
                    i++;
                    switch (escape) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new MalformedException();
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new MalformedException();
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipValue() {
            switch (peek()) {
                case '"' -> string();
                case '{' -> skipContainer('{', '}');
                case '[' -> skipContainer('[', ']');
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                default -> number();
            }
        }

        /**
         * Skips a balanced container. Counting only {@code open}/{@code close} is sufficient even
         * for mixed nesting: the other bracket kind must balance internally, so it never affects
         * this pair's depth. Strings are skipped string-aware so brackets inside them don't count.
         */
        private void skipContainer(char open, char close) {
            expect(open);
            int depth = 1;
            while (depth > 0) {
                char c = peek();
                if (c == '"') {
                    string();
                    continue;
                }
                i++;
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                }
            }
        }

        private void literal(String expected) {
            if (!s.startsWith(expected, i)) {
                throw new MalformedException();
            }
            i += expected.length();
        }

        /** Consumes a number laxly (any run of number characters); this parser only skips them. */
        private void number() {
            int start = i;
            while (i < s.length() && isNumberChar(s.charAt(i))) {
                i++;
            }
            if (i == start) {
                throw new MalformedException();
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
        }
    }
}
