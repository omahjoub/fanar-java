/**
 * Jackson 3.x JSON codec adapter for the Fanar Java SDK.
 *
 * <p>Binds the {@code qa.fanar.core.spi.FanarJsonCodec} SPI to Jackson 3 (the
 * {@code tools.jackson.*} package family, as shipped by Spring Boot 4.x). The Jackson
 * dependency is declared {@code provided} so the consuming application supplies the concrete
 * runtime.</p>
 *
 * @author Oussama Mahjoub
 */
module qa.fanar.json.jackson3 {
    requires qa.fanar.core;
    // jackson-databind transitively re-exports tools.jackson.core and
    // com.fasterxml.jackson.annotation (Jackson 3 kept the annotation namespace for
    // source-compat with Jackson 2).
    requires tools.jackson.databind;

    exports qa.fanar.json.jackson3;

    // Databind uses reflection to instantiate our package-private serializers / deserializers
    // via their no-arg constructors. The opens directive grants just databind the deep
    // reflection access it needs, without exposing the types publicly.
    opens qa.fanar.json.jackson3 to tools.jackson.databind;

    provides qa.fanar.core.spi.FanarJsonCodec
            with qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
}
