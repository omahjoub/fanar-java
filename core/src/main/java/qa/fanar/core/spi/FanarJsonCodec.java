package qa.fanar.core.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JSON serialization contract for the Fanar SDK.
 *
 * <p>The core module does not depend on any JSON library. Instead, callers install a codec via
 * {@code FanarClient.Builder.jsonCodec(...)} or via the {@code ServiceLoader} mechanism. Two
 * adapter modules are shipped: {@code fanar-json-jackson2} for Spring Boot 3 / Jackson 2 stacks,
 * and {@code fanar-json-jackson3} for Spring Boot 4 / Jackson 3 stacks. Users may also supply a
 * custom implementation (for example, a reflection-free GraalVM-native-image codec).</p>
 *
 * <p>Implementations must be thread-safe — the SDK calls {@link #decode} and {@link #encode}
 * concurrently from multiple threads against a single codec instance.</p>
 *
 * <p>Implementations must not consume or close the supplied streams themselves beyond what JSON
 * parsing requires; stream lifecycle is the SDK's responsibility.</p>
 */
public interface FanarJsonCodec {

    /**
     * Read a JSON value from the input stream and deserialize it into an instance of {@code type}.
     *
     * @param stream the input stream containing a JSON value; the codec reads until end-of-stream
     *               or end-of-value (implementation-defined). Must not be {@code null}.
     * @param type   the target type. Must not be {@code null}.
     * @param <T>    the deserialized type, bound to {@code type}
     * @return the deserialized value; never {@code null}
     * @throws IOException if stream access fails or the JSON is malformed
     */
    <T> T decode(InputStream stream, Class<T> type) throws IOException;

    /**
     * Serialize {@code value} to JSON and write it to the output stream.
     *
     * @param stream the destination output stream; the codec writes the JSON body and does not
     *               close the stream. Must not be {@code null}.
     * @param value  the value to serialize. Must not be {@code null}.
     * @throws IOException if stream access fails or serialization fails
     */
    void encode(OutputStream stream, Object value) throws IOException;
}
