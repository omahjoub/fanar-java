/**
 * Fanar Java core SDK — typed, pluggable transport over the Fanar API.
 *
 * <p>This module has zero runtime dependencies. It uses only {@code java.base} and {@code java.net.http}.
 * The top-level package {@code qa.fanar.core} and the {@code qa.fanar.core.spi} subpackage form the public API;
 * everything under {@code qa.fanar.core.internal} is implementation detail and is never exported.</p>
 */
module qa.fanar.core {
    requires java.net.http;

    // Compile-time only: DefaultHttpTransportTest spins up a local com.sun.net.httpserver.HttpServer.
    // Not required at runtime.
    requires static jdk.httpserver;

    exports qa.fanar.core;
    exports qa.fanar.core.spi;
    exports qa.fanar.core.chat;
    exports qa.fanar.core.models;
    exports qa.fanar.core.tokens;

    // Additional exports added as remaining domain subpackages are populated:
    //   exports qa.fanar.core.audio;
    //   exports qa.fanar.core.images;
    //   exports qa.fanar.core.translations;
    //   exports qa.fanar.core.poems;
    //   exports qa.fanar.core.moderation;

    // ServiceLoader contract: FanarClient discovers a FanarJsonCodec implementation at runtime
    // unless the caller passes one via FanarClient.Builder.jsonCodec(...). The two shipped
    // adapter modules (fanar-json-jackson2, fanar-json-jackson3) will provide the service.
    uses qa.fanar.core.spi.FanarJsonCodec;
}
