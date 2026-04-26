/**
 * Jackson 2.x JSON codec adapter for the Fanar Java SDK.
 *
 * <p>Binds the {@code qa.fanar.core.spi.FanarJsonCodec} SPI to Jackson 2 (the
 * {@code com.fasterxml.jackson.*} package family, as shipped by Spring Boot 3.x). The Jackson
 * dependency is declared {@code provided} so the consuming application supplies the concrete
 * runtime.</p>
 *
 * @author Oussama Mahjoub
 */
module qa.fanar.json.jackson2 {
    requires qa.fanar.core;
    // jackson-databind transitively re-exports com.fasterxml.jackson.core and
    // com.fasterxml.jackson.annotation.
    requires com.fasterxml.jackson.databind;

    exports qa.fanar.json.jackson2;

    // Databind uses reflection to instantiate our package-private serializers / deserializers
    // via their no-arg constructors. The opens directive grants just databind the deep
    // reflection access it needs, without exposing the types publicly.
    opens qa.fanar.json.jackson2 to com.fasterxml.jackson.databind;

    provides qa.fanar.core.spi.FanarJsonCodec
            with qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
}
