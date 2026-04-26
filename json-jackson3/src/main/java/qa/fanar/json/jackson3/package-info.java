/**
 * Jackson 3.x adapter for the Fanar JSON codec SPI.
 *
 * <p>Public type: {@code Jackson3FanarJsonCodec}. Register via {@code ServiceLoader} (automatic)
 * or pass an instance explicitly to {@code FanarClient.Builder.jsonCodec(...)}. The adapter
 * accepts a user-configured {@code tools.jackson.databind.json.JsonMapper} so Spring-managed
 * customizations flow through.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.json.jackson3;
