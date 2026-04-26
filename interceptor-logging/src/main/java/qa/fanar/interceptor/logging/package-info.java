/**
 * Wire-level logging {@link qa.fanar.core.spi.Interceptor} for the Fanar Java SDK.
 *
 * <p>Public type: {@link qa.fanar.interceptor.logging.WireLoggingInterceptor}. Wire it via
 * {@code FanarClient.builder().addInterceptor(WireLoggingInterceptor.builder().level(BODY).build()).build()}
 * — defaults to {@code BASIC} (one line per request) sinking to SLF4J at {@code DEBUG} on the
 * {@code fanar.wire} logger.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.interceptor.logging;
