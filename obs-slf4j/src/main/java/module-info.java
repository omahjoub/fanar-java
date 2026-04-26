/**
 * SLF4J binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Emits one structured log line per SDK operation through SLF4J — the line is at
 * {@code DEBUG} on success and {@code ERROR} on failure, and carries the operation name,
 * duration in milliseconds, and every attribute the SDK attached. Logger names mirror the
 * operation namespace (for example {@code fanar.chat.send}, {@code fanar.audio.speech}) so
 * users can scope their logging configuration at any prefix granularity.</p>
 */
module qa.fanar.obs.slf4j {
    requires qa.fanar.core;
    requires org.slf4j;

    exports qa.fanar.obs.slf4j;
}
