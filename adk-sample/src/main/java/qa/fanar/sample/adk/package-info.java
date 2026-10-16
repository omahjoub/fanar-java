/**
 * Google ADK sample application demonstrating {@code fanar-adk}: ADK agents on Fanar models.
 *
 * <p>Two agents, each a {@code public static ROOT_AGENT}, which is what ADK's dev UI looks for:</p>
 * <ul>
 *   <li>{@code assistant} ({@link qa.fanar.sample.adk.Assistant}) — one {@code LlmAgent} on Fanar's
 *       default chat model, one chat call per turn. Start here.</li>
 *   <li>{@code story_studio} ({@link qa.fanar.sample.adk.studio.StoryStudio}) — an idea in, an
 *       illustrated, checked, translated and narrated Arabic short story out: seven Fanar models
 *       in one {@code SequentialAgent}, one call each.</li>
 * </ul>
 *
 * <p>Install the SDK from the repository root once ({@code ./mvnw install}), then start ADK's dev UI
 * on http://localhost:8080 with:</p>
 * <pre>
 *   FANAR_API_KEY=... ./mvnw -pl adk-sample spring-boot:run
 * </pre>
 *
 * <p>Or run the studio without the UI; the cover and the narration land in
 * {@code adk-sample/target/studio}:</p>
 * <pre>
 *   FANAR_API_KEY=... ./mvnw -pl adk-sample compile exec:java -Dexec.args="a falcon afraid of heights"
 *   FANAR_API_KEY=... ./mvnw -pl adk-sample compile exec:java -Dstudio.narrate=false   # no speech call
 * </pre>
 *
 * <p>Each Fanar call prints one line on stderr; {@code -Dfanar.wire-logging.level=BODY} prints whole
 * requests and responses. The dev UI runs in a forked JVM, so pass such flags through
 * {@code -Dspring-boot.run.jvmArguments="-Dstudio.narrate=false -Dfanar.wire-logging.level=BODY"}.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.sample.adk;
