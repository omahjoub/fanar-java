package qa.fanar.e2e.audio;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Generate a minimal valid WAV file in-memory for live audio tests.
 *
 * <p>Returns a 1-second 16 kHz mono 16-bit PCM silence clip with a standard 44-byte
 * RIFF/WAVE header. The point is to give {@code createVoice} something the SDK can
 * serialize as a multipart upload — Fanar may still reject silent audio at the model
 * layer, which is fine: we want the typed exception to surface, not be papered over.</p>
 */
final class SilenceWav {

    private SilenceWav() {
        // not instantiable
    }

    static byte[] bytes() {
        return bytes(1);
    }

    static byte[] bytes(int seconds) {
        int sampleRate = 16_000;
        int numSamples = sampleRate * seconds;
        int dataSize = numSamples * 2;        // 16-bit mono
        int riffChunkSize = 36 + dataSize;    // total - 8 (RIFF/size header)

        ByteArrayOutputStream baos = new ByteArrayOutputStream(44 + dataSize);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, riffChunkSize);
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));

            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, 16);                // fmt chunk size
            writeShortLE(out, (short) 1);       // PCM
            writeShortLE(out, (short) 1);       // mono
            writeIntLE(out, sampleRate);
            writeIntLE(out, sampleRate * 2);    // byte rate
            writeShortLE(out, (short) 2);       // block align
            writeShortLE(out, (short) 16);      // bits per sample

            out.write("data".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, dataSize);
            // Silence: zero samples
            for (int i = 0; i < numSamples; i++) {
                out.writeShort(0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("WAV synthesis failed", e);
        }
        return baos.toByteArray();
    }

    private static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.writeInt(Integer.reverseBytes(v));
    }

    private static void writeShortLE(DataOutputStream out, short v) throws IOException {
        out.writeShort(Short.reverseBytes(v));
    }
}
