/**
 * Spring AI 2.0 ChatModel adapter for the Fanar Java SDK.
 *
 * <p>Layered on top of {@code fanar-spring-boot-4-starter}. Drop the dep, configure
 * {@code fanar.api-key=…}, and inject Spring AI's {@code ChatClient}:</p>
 * <pre>{@code
 * @Bean ChatClient chatClient(ChatModel model) {
 *     return ChatClient.builder(model).build();
 * }
 * }</pre>
 *
 * <p>Public types:</p>
 * <ul>
 *   <li>{@link qa.fanar.spring.ai.FanarChatModel} — the {@code ChatModel} +
 *       {@code StreamingChatModel} implementation.</li>
 *   <li>{@link qa.fanar.spring.ai.FanarImageModel} — the {@code ImageModel} implementation
 *       backed by {@code /v1/images/generations}.</li>
 *   <li>{@link qa.fanar.spring.ai.FanarTextToSpeechModel} — the {@code TextToSpeechModel}
 *       (and {@code StreamingTextToSpeechModel}) implementation backed by
 *       {@code /v1/audio/speech}.</li>
 *   <li>{@link qa.fanar.spring.ai.FanarTranscriptionModel} — the {@code TranscriptionModel}
 *       implementation backed by {@code /v1/audio/transcriptions} (always {@code text} format).</li>
 *   <li>{@link qa.fanar.spring.ai.FanarSpringAiAutoConfiguration} — registers the beans when
 *       Spring AI is on the classpath.</li>
 * </ul>
 *
 * <p>Not implemented: {@code ModerationModel} (Fanar's moderation endpoint returns
 * {@code safety} + {@code culturalAwareness} continuous scores rather than Spring AI's
 * {@code Categories} per-class flags — too lossy a mapping to be useful), and
 * {@code EmbeddingModel} (Fanar exposes no embeddings endpoint at all).</p>
 *
 * <p>This module does not ship a {@code module-info.java}: Spring Boot's auto-configuration
 * runs on the classpath, and Spring AI's milestone artifacts don't yet declare full JPMS
 * modules. Same rationale as {@code fanar-spring-boot-4-starter}.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.spring.ai;
