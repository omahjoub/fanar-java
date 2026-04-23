/**
 * Fanar Java core SDK — typed, pluggable transport over the Fanar API.
 *
 * <p>This module has zero runtime dependencies. It uses only {@code java.base} and {@code java.net.http}.
 * The top-level package {@code qa.fanar.core} and the {@code qa.fanar.core.spi} subpackage form the public API;
 * everything under {@code qa.fanar.core.internal} is implementation detail and is never exported.</p>
 */
module qa.fanar.core {
    requires java.net.http;

    exports qa.fanar.core;

    // Additional exports added as domain subpackages and the .spi subpackage are populated:
    //   exports qa.fanar.core.chat;
    //   exports qa.fanar.core.audio;
    //   exports qa.fanar.core.images;
    //   exports qa.fanar.core.translations;
    //   exports qa.fanar.core.poems;
    //   exports qa.fanar.core.moderation;
    //   exports qa.fanar.core.tokens;
    //   exports qa.fanar.core.models;
    //   exports qa.fanar.core.spi;
    //   uses qa.fanar.core.spi.FanarJsonCodec;
}
