/**
 * Jackson 2.x JSON codec adapter for the Fanar Java SDK.
 *
 * <p>Binds the {@code qa.fanar.core.spi.FanarJsonCodec} SPI to Jackson 2 (the {@code com.fasterxml.jackson.*}
 * package family, as shipped by Spring Boot 3.x). The Jackson dependency is declared {@code provided} so the
 * consuming application's Spring Boot supplies the concrete runtime.</p>
 */
module qa.fanar.json.jackson2 {
    requires qa.fanar.core;
    // requires com.fasterxml.jackson.databind;  // added when the adapter is implemented

    exports qa.fanar.json.jackson2;

    // provides qa.fanar.core.spi.FanarJsonCodec
    //     with qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
}
