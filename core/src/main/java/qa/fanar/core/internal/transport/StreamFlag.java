package qa.fanar.core.internal.transport;

import java.nio.charset.StandardCharsets;

import qa.fanar.core.FanarTransportException;

/**
 * Injects {@code "stream":true} into a serialized request body.
 *
 * <p>Request records deliberately do not model the wire field {@code stream} — buffered vs.
 * streamed delivery is a call-site choice on the domain facade (chat {@code send} vs.
 * {@code stream}, audio {@code speech} vs. {@code speechStream}), so the flag is spliced into
 * the already-encoded JSON instead.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class StreamFlag {

    private StreamFlag() {
        // not instantiable
    }

    /**
     * Inject {@code "stream":true} as the first property of the serialized JSON object.
     * Handles both {@code {}} (no comma needed) and {@code {"k":v,...}} (comma between the
     * injected flag and the existing first key).
     *
     * @param src the codec-serialized request body; must be a JSON object
     * @return a new array with the flag injected
     * @throws FanarTransportException if {@code src} is not a JSON object
     */
    public static byte[] inject(byte[] src) {
        if (src.length < 2 || src[0] != '{') {
            throw new FanarTransportException(
                    "JSON codec produced an unexpected body shape (non-object or empty)");
        }
        byte[] prefix = "{\"stream\":true".getBytes(StandardCharsets.UTF_8);
        boolean emptyObject = src.length == 2; // "{}"
        int rest = src.length - 1; // everything after the opening '{'
        int resultLen = prefix.length + (emptyObject ? 0 : 1) + rest;
        byte[] result = new byte[resultLen];
        int pos = 0;
        System.arraycopy(prefix, 0, result, pos, prefix.length);
        pos += prefix.length;
        if (!emptyObject) {
            result[pos++] = ',';
        }
        System.arraycopy(src, 1, result, pos, rest);
        return result;
    }
}
