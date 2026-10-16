package qa.fanar.sample.adk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import com.google.adk.agents.BaseAgent;
import com.google.adk.web.CompiledAgentLoader;
import com.google.adk.web.config.AgentLoadingProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What ADK's dev UI does at startup, offline. Its default loader scans this module's compiled
 * classes and reads every {@code ROOT_AGENT}, which runs that class's static initialiser. An agent
 * that built its Fanar client there would be dropped from the dev UI, with an unnamed
 * {@code LinkageError}, whenever the key is missing (ADR-030). This JVM never sees
 * {@code FANAR_API_KEY} (the pom removes it), so finding both agents proves the sample's client is
 * built on the first request instead.
 */
@Tag("integration")
class AgentDiscoveryIntegrationTest {

    @Test
    void theDevUiLoaderFindsBothAgentsWithoutAnApiKey() {
        assertNull(System.getenv("FANAR_API_KEY"), "the premise: Surefire removes the key from this JVM");

        AgentLoadingProperties properties = new AgentLoadingProperties();
        properties.setSourceDir(Path.of("").toAbsolutePath().toString());   // the module, as under spring-boot:run
        CompiledAgentLoader loader = new CompiledAgentLoader(properties);

        assertEquals(List.of("assistant", "story_studio"), loader.listAgents().stream().sorted().toList());
        assertEquals(List.of("storyteller", "editor", "production", "publisher"),
                loader.loadAgent("story_studio").subAgents().stream().map(BaseAgent::name).toList());
    }
}
