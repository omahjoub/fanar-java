package qa.fanar.sample.adk.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * Runs the studio from the command line, without the dev UI, and writes the cover and the narration
 * to the directory named by the {@code studio.out} system property ({@code target/studio} by default):
 *
 * <pre>FANAR_API_KEY=... ./mvnw -pl adk-sample compile exec:java -Dexec.args="your idea"</pre>
 */
public final class RunStoryStudio {

    private RunStoryStudio() {
        // main only
    }

    /**
     * Runs one story through the studio.
     *
     * @param args the idea, as words; a default idea when there are none
     * @throws IOException if the cover or the narration cannot be written
     */
    public static void main(String[] args) throws IOException {
        String idea = args.length > 0 ? String.join(" ", args) : "فتاة تكتشف مكتبة سرية تحت المسجد القديم في حيّها";
        InMemorySessionService sessions = new InMemorySessionService();
        InMemoryArtifactService artifacts = new InMemoryArtifactService();
        Runner runner = Runner.builder()
                .agent(StoryStudio.ROOT_AGENT)
                .appName("studio")
                .artifactService(artifacts)
                .sessionService(sessions)
                .build();
        Session session = sessions.createSession("studio", "me").blockingGet();

        System.out.println("💡 " + idea + "\n");
        runner.runAsync("me", session.id(),
                        Content.builder().role("user").parts(Part.fromText(idea)).build(),
                        RunConfig.builder().streamingMode(StreamingMode.SSE).build())
                .blockingForEach(RunStoryStudio::print);

        Path out = Files.createDirectories(Path.of(System.getProperty("studio.out", "target/studio")));
        for (String name : List.of("cover.png", "story.mp3")) {
            Part part = artifacts.loadArtifact("studio", "me", session.id(), name).blockingGet();
            if (part != null) {
                byte[] bytes = part.inlineData().flatMap(Blob::data).orElse(new byte[0]);
                Files.write(out.resolve(name), bytes);
                System.out.println("💾 " + out.resolve(name).toAbsolutePath() + " (" + bytes.length / 1024 + " KB)");
            }
        }
    }

    private static void print(Event event) {
        if (event.partial().orElse(false)) {
            System.out.print(event.content().map(Content::text).orElse(""));
            System.out.flush();
            return;
        }
        StringBuilder line = new StringBuilder();
        event.content().flatMap(Content::parts).orElse(List.of()).forEach(part -> {
            part.text().ifPresent(line::append);
            part.inlineData().ifPresent(blob -> line.append("\n[inline ")
                    .append(blob.mimeType().orElse("bytes")).append(", ")
                    .append(blob.data().map(d -> d.length / 1024).orElse(0)).append(" KB]"));
        });
        System.out.printf("%n━━ %s%s ━━%n%s%n",
                event.author(),
                event.branch().map(b -> " (" + b + ")").orElse(""),
                line);
    }
}
