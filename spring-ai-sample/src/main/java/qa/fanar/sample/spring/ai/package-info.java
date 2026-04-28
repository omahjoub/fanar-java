/**
 * Spring AI sample application demonstrating the {@code fanar-spring-ai-starter}.
 *
 * <p>Boot the app with {@code FANAR_API_KEY=… ./mvnw -pl spring-ai-sample spring-boot:run}
 * and try:</p>
 * <pre>
 *   # Single-turn chat
 *   curl -X POST localhost:8080/api/chat \
 *        -H 'content-type: application/json' \
 *        -d '{"message":"Hello in Arabic"}'
 *
 *   # Multi-turn — same conversationId across calls
 *   curl -X POST localhost:8080/api/chat/conv-42 \
 *        -H 'content-type: application/json' \
 *        -d '{"message":"My name is Oussama"}'
 *   curl -X POST localhost:8080/api/chat/conv-42 \
 *        -H 'content-type: application/json' \
 *        -d '{"message":"What is my name?"}'   # remembers
 *
 *   # Inspect what's stored
 *   curl localhost:8080/api/chat/conv-42/history
 *
 *   # Streaming (Server-Sent Events)
 *   curl -X POST -N localhost:8080/api/chat/stream \
 *        -H 'content-type: application/json' \
 *        -d '{"message":"Write a haiku"}'
 *
 *   # Image
 *   curl -X POST localhost:8080/api/image \
 *        -H 'content-type: application/json' \
 *        -d '{"prompt":"a calligraphy mosque at sunset"}' | jq -r .b64Json | base64 -d &gt; out.png
 *
 *   # Text-to-speech
 *   curl -X POST localhost:8080/api/speak \
 *        -H 'content-type: application/json' \
 *        -d '{"text":"hello world"}' --output speech.mp3
 *
 *   # Speech-to-text
 *   curl -F audio=@speech.mp3 localhost:8080/api/transcribe
 * </pre>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.sample.spring.ai;
