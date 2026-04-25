package qa.fanar.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Helper that writes binary live-test responses (TTS audio, generated images) to
 * {@code e2e/target/<dir>/} so a developer running the suite can play / view the
 * actual server output. {@code mvn clean} removes the directories.
 */
public final class LiveOutputs {

    private LiveOutputs() {
        // not instantiable
    }

    /**
     * Write {@code bytes} to {@code target/<subdir>/<prefix>-<uuid>.<extension>} relative to
     * the e2e module's working directory, and return the absolute path. Failures are wrapped
     * as {@link UncheckedIOException} — the live test caller should still see the I/O reason
     * if the local filesystem misbehaves.
     */
    public static Path write(String subdir, String prefix, String extension, byte[] bytes) {
        Path dir = Paths.get("target", subdir);
        try {
            Files.createDirectories(dir);
            String shortId = UUID.randomUUID().toString().substring(0, 8);
            Path file = dir.resolve(prefix + "-" + shortId + "." + extension);
            Files.write(file, bytes);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + dir + " output", e);
        }
    }

    /**
     * Sniff a few common image-format magic bytes and return the right file extension.
     * Falls back to {@code "bin"} when the prefix doesn't match anything known.
     */
    public static String detectImageExtension(byte[] bytes) {
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        if (bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return "gif";
        }
        return "bin";
    }
}
