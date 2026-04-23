/**
 * Jackson 3.x JSON codec adapter for the Fanar Java SDK.
 *
 * <p>Binds the {@code qa.fanar.core.spi.FanarJsonCodec} SPI to Jackson 3 (the {@code tools.jackson.*}
 * package family, as shipped by Spring Boot 4.x). The Jackson dependency is declared {@code provided} so the
 * consuming application's Spring Boot supplies the concrete runtime.</p>
 */
module qa.fanar.json.jackson3 {
    requires qa.fanar.core;
    // requires tools.jackson.databind;  // added when the adapter is implemented

    exports qa.fanar.json.jackson3;

    // provides qa.fanar.core.spi.FanarJsonCodec
    //     with qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
}
