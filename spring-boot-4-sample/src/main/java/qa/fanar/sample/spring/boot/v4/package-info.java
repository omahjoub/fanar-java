/**
 * Spring Boot 4 sample application demonstrating the {@code fanar-spring-boot-4-starter}.
 *
 * <p>Boot the app with {@code FANAR_API_KEY=... ./mvnw -pl spring-boot-4-sample
 * spring-boot:run} and try:</p>
 * <pre>
 *   curl http://localhost:8080/api/models
 *   curl -X POST http://localhost:8080/api/chat \
 *        -H 'content-type: application/json' \
 *        -d '{"prompt":"اكتب جملة بالعربية"}'
 *   curl http://localhost:8080/actuator/health
 * </pre>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.sample.spring.boot.v4;
