package qa.fanar.sample.spring.boot.v4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable demo for the Fanar Spring Boot 4 starter. Boots an embedded Tomcat with two REST
 * endpoints:
 *
 * <ul>
 *   <li>{@code GET  /api/models} — proxies {@code FanarClient.models().list()}.</li>
 *   <li>{@code POST /api/chat}   — single-turn chat completion against {@code Fanar}.</li>
 * </ul>
 *
 * <p>And the actuator endpoints:</p>
 * <ul>
 *   <li>{@code GET /actuator/health} — includes {@code fanar} contributor wired by
 *       {@link qa.fanar.spring.boot.v4.FanarHealthAutoConfiguration}.</li>
 * </ul>
 *
 * <p>Run with {@code FANAR_API_KEY=... ./mvnw -pl spring-boot-4-sample spring-boot:run}.</p>
 *
 * @author Oussama Mahjoub
 */
@SpringBootApplication
public class FanarSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FanarSampleApplication.class, args);
    }
}
