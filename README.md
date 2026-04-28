# Fanar Java SDK

Java SDK for [Fanar](https://fanar.qa) — Qatar's Arabic-centric multimodal AI platform.

> **Status:** pre-1.0. The core SDK and every Fanar domain (chat, audio, images, translations,
> moderations, tokens, models, poems) are implemented with 100 % JaCoCo coverage and battle-tested
> against the live API. Spring Boot 4 and Spring AI 2.0 starters ship with a sample app each.
> Not yet on Maven Central — install via `./mvnw install` for now.

## Why this SDK?

**To open Fanar to the Java world.** Java still powers the majority of production systems in
enterprise, finance, government, telco and research, and the JVM ecosystem has doubled down on
AI. A first-class SDK puts Fanar in the idiomatic shape each of these audiences already
expects — Spring Boot apps, Spring AI providers, Quarkus extensions, GraalVM native binaries,
plain-JDK code, Kotlin.

And yes — you *could* point an OpenAI-compatible client at `https://api.fanar.qa/v1` and get
basic chat. **But Fanar is not OpenAI.** Islamic RAG with authenticated source references,
Quranic TTS with validated tajweed, Arabic poetry through a dedicated model, cultural-awareness
scoring in moderation, bilingual progress events during streaming, two distinct thinking-mode
protocols, a vision model that understands Arabic calligraphy. None of that fits in an
OpenAI-shaped client.

The core SDK is a **thin, pluggable, observable transport** over the Fanar API — nothing more.
Memory, templating, vectors, evaluation belong in **framework modules** on top.

## Quick start

Three install paths depending on your stack.

### 1. Pure Java (any framework, no Spring)

```xml
<dependencies>
    <dependency>
        <groupId>qa.fanar</groupId>
        <artifactId>fanar-core</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>qa.fanar</groupId>
        <artifactId>fanar-json-jackson3</artifactId>     <!-- or fanar-json-jackson2 -->
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

```java
try (FanarClient client = FanarClient.builder().apiKey(System.getenv("FANAR_API_KEY")).build()) {
    ChatResponse r = client.chat().send(ChatRequest.builder()
            .model(ChatModel.FANAR)
            .addMessage(UserMessage.of("Say hello in Arabic"))
            .build());
    System.out.println(r.choices().getFirst().message().content());
}
```

### 2. Spring Boot 4

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-spring-boot-4-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
fanar:
  api-key: ${FANAR_API_KEY}
  wire-logging:
    level: BASIC                   # NONE | BASIC | HEADERS | BODY
```

```java
@RestController
class MyController {
    @Autowired FanarClient fanar;   // auto-wired; /actuator/health includes Fanar reachability
}
```

### 3. Spring AI 2.0

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-spring-ai-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@Bean
ChatClient chatClient(ChatModel model, ChatMemory memory) {     // Spring AI types
    return ChatClient.builder(model)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
}
// Now the standard Spring AI surface — memory, RAG advisors, prompt templates — works
// with Fanar as the provider. ImageModel, TextToSpeechModel, TranscriptionModel beans
// auto-register too.
```

## Modules

| Module | Purpose |
|---|---|
| `fanar-core` | Typed client + SPIs + every Fanar domain. Zero runtime deps. |
| `fanar-json-jackson2` / `fanar-json-jackson3` | JSON codec adapters (pick by Jackson version). |
| `fanar-obs-slf4j` / `fanar-obs-otel` / `fanar-obs-micrometer` | `ObservabilityPlugin` adapters; opt-in. |
| `fanar-interceptor-logging` | OkHttp-style wire-logging interceptor. |
| `fanar-spring-boot-4-starter` | `@AutoConfiguration` + `fanar.*` properties + actuator health. |
| `fanar-spring-boot-4-sample` | Runnable sample app. |
| `fanar-spring-ai-starter` | Spring AI 2.0 `ChatModel` / `ImageModel` / `TextToSpeechModel` / `TranscriptionModel` adapters. |
| `fanar-spring-ai-sample` | Runnable sample app with `ChatClient` + memory. |
| `fanar-java-bom` | Imports for aligned versioning. |

## Docs

- [Project state](docs/PROJECT_STATE.md) — what's shipped, planned, deferred.
- [Compatibility matrix](docs/COMPATIBILITY.md) — capability map: Fanar ↔ core ↔ framework layer.
- [Architecture](docs/ARCHITECTURE.md) — module layout, request-flow diagrams, where things live.
- [API sketch](docs/API_SKETCH.md) — concrete code shapes for every call.
- [GraalVM walkthrough](docs/GRAALVM.md) — native-image build, end-to-end.
- [Glossary](docs/GLOSSARY.md) — Fanar-specific and project-specific terminology.
- [ADRs](docs/adr/INDEX.md) — non-obvious design decisions.
- [Library best practices](docs/JAVA_LIBRARY_BEST_PRACTICES.md) — internal hygiene rules.
- [Contributing](docs/CONTRIBUTING.md) — workflow, conventions.
- [Fanar OpenAPI spec](api-spec/openapi.json) — the wire contract we model.
