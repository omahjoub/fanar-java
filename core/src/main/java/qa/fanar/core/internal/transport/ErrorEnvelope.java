package qa.fanar.core.internal.transport;

/**
 * The typed Fanar error envelope:
 * {@code {"error":{"code":"…","message":"…","status":N,"param":"…","type":"…"}}}.
 *
 * <p>Parsed by a small hand-rolled scanner rather than the {@link
 * qa.fanar.core.spi.FanarJsonCodec} SPI: codec implementations reflect over target types, and
 * this package is deliberately not exported (ADR-018), so a codec running as a JPMS module could
 * not access an envelope DTO defined here. The envelope is a five-member, spec-pinned shape; the
 * scanner is strict about JSON syntax, and a body that is not a well-formed envelope — or carries
 * no usable {@code code} — yields {@code null}, letting the {@link ExceptionMapper} fall back to
 * HTTP-status routing. An individual member whose <em>type</em> is not what the spec declares is
 * read as absent rather than failing the parse; see {@code stringOrNull()}.</p>
 *
 * <p>Members are kept as their raw wire strings — mapping {@code code} to {@code ErrorCode} and
 * {@code type} to {@code ContentFilterType} is {@link ExceptionMapper}'s job, so that an unknown
 * value is a routing decision there rather than a parse failure here (ADR-015). {@code status} is
 * still skipped: HTTP already carries it, and the mapper's fallback reads it from the response.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @param code    the wire value of the error code; never {@code null} (a parse without a code
 *                yields no envelope)
 * @param message the human-readable server message, or {@code null} when absent
 * @param param   the request field the error is attributed to, or {@code null}; spec-nullable and
 *                parsed but not yet surfaced on the public API (ADR-006 amendment 2026-09-15)
 * @param type    the wire value of the content-filter subtype, or {@code null}; spec-nullable
 * @author Oussama Mahjoub
 */
record ErrorEnvelope(String code, String message, String param, String type) {

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
            String param = null;
            String type = null;
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
                                    case "code" -> code = stringOrNull();
                                    case "message" -> message = stringOrNull();
                                    case "param" -> param = stringOrNull();
                                    case "type" -> type = stringOrNull();
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
            return code == null ? null : new ErrorEnvelope(code, message, param, type);
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

        /**
         * A string member, or {@code null} when the value is anything else.
         *
         * <p>Every envelope member is read through this, because only {@code code} is load-bearing:
         * it picks the exception subtype (ADR-006), and it would be a poor trade to lose that
         * routing because an auxiliary member arrived with an unexpected JSON type. {@code param}
         * and {@code type} are declared nullable by the spec and were both observed {@code null}
         * on the wire (2026-09-15 403, WIRE_OBSERVATIONS) — reading those with {@link #string()}
         * throws, which discards the <em>whole</em> envelope and silently drops the response to
         * HTTP-status routing.</p>
         *
         * <p>Delegating the non-string case to {@link #skipValue()} keeps the scanner just as
         * strict about JSON <em>syntax</em> (an unbalanced container or a bad escape still fails
         * the parse) while tolerating a <em>type</em> we did not expect. A non-string {@code code}
         * still yields no envelope, so status routing takes over exactly as before.</p>
         */
        private String stringOrNull() {
            if (peek() == '"') {
                return string();
            }
            skipValue();
            return null;
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
