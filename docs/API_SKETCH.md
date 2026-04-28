# API sketch

> **Status — living reference.** Every snippet below matches a shipped type. When implementation reveals
> that a sketch was wrong, we revise the sketch (and the ADR that backed it) — not the code. Treat a
> discrepancy between sketch and code as a triage question ("which one should change?"), not a correctness
> ruling.
>
> Framework integration patterns (Spring Boot 4 starter, Spring AI 2.0 adapters) appear at the end of
> this doc. The core sections show the framework-agnostic API every adapter sits on top of.

---

## Setup

Two artifacts: `fanar-core` plus one JSON adapter matching your Jackson major version.

### Spring Boot 3.x stack (Jackson 2)

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-core</artifactId>
    <version>${fanar.version}</version>
</dependency>
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-json-jackson2</artifactId>
    <version>${fanar.version}</version>
</dependency>
```

### Spring Boot 4.x stack (Jackson 3)

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-core</artifactId>
    <version>${fanar.version}</version>
</dependency>
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-json-jackson3</artifactId>
    <version>${fanar.version}</version>
</dependency>
```

### With the BOM (recommended for multi-module consumers)

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>qa.fanar</groupId>
            <artifactId>fanar-java-bom</artifactId>
            <version>${fanar.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>qa.fanar</groupId>
        <artifactId>fanar-core</artifactId>
    </dependency>
    <dependency>
        <groupId>qa.fanar</groupId>
        <artifactId>fanar-json-jackson3</artifactId>
    </dependency>
</dependencies>
```

---

## 1. Build a client

```java
import qa.fanar.core.FanarClient;
import java.time.Duration;

try (FanarClient client = FanarClient.builder()
        .apiKey(System.getenv("FANAR_API_KEY"))
        .connectTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .build()) {

    // use client
}
```

`FANAR_API_KEY` (and `FANAR_BASE_URL`) are read from the environment automatically if the corresponding builder
method is omitted. The client is `AutoCloseable`; closing cascades to the internally-managed `HttpClient`. Pass your
own via `.httpClient(custom)` and you retain its lifecycle.

---

## 2. Sync chat

```java
import qa.fanar.core.chat.*;

ChatRequest request = ChatRequest.builder()
    .model(ChatModel.FANAR_C_2_27B)
    .addMessage(SystemMessage.of("You are a helpful assistant."))
    .addMessage(UserMessage.of("Hello, Fanar!"))
    .temperature(0.7)
    .build();

ChatResponse response = client.chat().send(request);
System.out.println(response.choices().getFirst().message().content());
```

Sync is the primary API (ADR-004). The call blocks, but on a virtual thread the block does not occupy a carrier thread.

`ChatModel` (and the other Fanar-controlled identifiers — `Source`, `FinishReason`, `ImageDetail`, `ContentFilterType`, `BookName`) is an open value-class record. Use the named constants where possible; for any wire value the SDK doesn't yet ship a constant for, fall back to the permissive factory:

```java
.model(ChatModel.of("Fanar-D-3-50B"))   // works the day Fanar adds the model
```

---

## 3. Async chat

```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<ChatResponse> future = client.chat().sendAsync(request);

future.thenAccept(response ->
    System.out.println(response.choices().getFirst().message().content())
);
```

Async delegates to the sync path on a virtual-thread executor — pure composition sugar, no scaling tax. Interop with
any reactive / coroutine library is a one-liner at the call site:

```java
// Reactor
Mono.fromFuture(future);

// RxJava 3
Single.fromCompletionStage(future);

// Kotlin coroutines
future.await()
```

---

## 4. Streaming chat

```java
import java.util.concurrent.Flow;
import qa.fanar.core.chat.*;

Flow.Publisher<StreamEvent> publisher = client.chat().stream(request);

publisher.subscribe(new Flow.Subscriber<StreamEvent>() {
    public void onSubscribe(Flow.Subscription sub) { sub.request(Long.MAX_VALUE); }

    public void onNext(StreamEvent event) {
        switch (event) {
            case TokenChunk t      -> System.out.print(t.delta());
            case ProgressChunk p   -> System.err.println("[progress] " + p.progress().message().en());
            case ToolCallChunk c   -> { /* tool invocation signalled by server */ }
            case ToolResultChunk r -> { /* tool result received */ }
            case DoneChunk d       -> { /* stream complete */ }
            case ErrorChunk e      -> { /* stream error */ }
        }
    }

    public void onError(Throwable t) { t.printStackTrace(); }
    public void onComplete() {}
});
```

The `switch` is exhaustive over the sealed `StreamEvent` hierarchy (ADR-005). If Fanar adds a new chunk type, this
`switch` stops compiling until the new case is handled — safe-by-default evolution.

### Iterator style (on a virtual thread)

```java
import qa.fanar.core.chat.Streams;

for (StreamEvent event : Streams.toStream(client.chat().stream(request))) {
    // same switch as above
}
```

Useful when the caller already runs on a virtual thread — blocking iteration costs nothing on a vthread.

---

## 5. Islamic RAG with authenticated references

```java
import qa.fanar.core.chat.*;

ChatRequest request = ChatRequest.builder()
    .model(ChatModel.FANAR_SADIQ)
    .addMessage(UserMessage.of("What does the Qur'an say about honesty?"))
    .restrictToIslamic(true)
    .preferredSources(Source.QURAN, Source.TAFSIR)
    .build();

ChatResponse response = client.chat().send(request);
ChatMessage message = response.choices().getFirst().message();

System.out.println(message.content());
for (Reference ref : message.references()) {
    System.out.printf("[%d] %s — %s%n", ref.number(), ref.source(), ref.content());
}
```

The `references()` list is Fanar-exclusive; no OpenAI-compatible client surfaces it.

---

## 6. Other domains

```java
// Text-to-speech (including Quranic TTS with validated reciters)
client.audio().speech(TextToSpeechRequest.builder()
    .model(TtsModel.FANAR_AURA_TTS_2)
    .voice(Voice.AMELIA)
    .input("Hello from Fanar")
    .build());

// Speech-to-text
client.audio().transcribe(TranscriptionRequest.of(audioFile, SttModel.FANAR_AURA_STT_1));

// Image generation
client.images().generate(ImageGenerationRequest.of(ImageModel.FANAR_ORYX_IG_2, "A futuristic Doha skyline"));

// Translation
client.translations().send(TranslationRequest.of(TranslationModel.FANAR_SHAHEEN_MT_1,
                                                  "Hello, how are you?", LanguagePair.EN_AR));

// Poetry
client.poems().generate(PoemRequest.of(PoemModel.FANAR_DIWAN, "Write a poem about the sea"));

// Moderation (returns safety + cultural-awareness scores)
client.moderation().send(ModerationRequest.of(ModerationModel.FANAR_GUARD_2, "prompt", "response"));

// Tokenization
client.tokens().count(TokenizationRequest.of("some text", ChatModel.FANAR_S_1_7B));

// Model listing
List<AvailableModel> models = client.models().list();
```

---

## 7. Typed error handling

```java
import qa.fanar.core.*;

try {
    ChatResponse response = client.chat().send(request);
} catch (FanarRateLimitException e) {
    // Retry-After honored automatically by the built-in RetryInterceptor
    log.warn("Rate limited, retry in {}", e.retryAfter());
} catch (FanarContentFilterException e) {
    showRefusalUi(e.filterType());
} catch (FanarQuotaExceededException e) {
    billing.notifyOutOfQuota();
} catch (FanarException e) {
    log.error("Fanar call failed", e);
}
```

The hierarchy is sealed per ADR-006 — every subtype is documented, and pattern matching on exception type works:

```java
switch (exception) {
    case FanarRateLimitException e      -> Thread.sleep(e.retryAfter().toMillis());
    case FanarContentFilterException e  -> showRefusalUi(e.filterType());
    case FanarException e               -> log.error("Fanar call failed", e);
}
```

---

## 8. Custom interceptor

```java
import qa.fanar.core.spi.Interceptor;

Interceptor timing = (request, chain) -> {
    long start = System.nanoTime();
    var response = chain.proceed(request);
    long ms = (System.nanoTime() - start) / 1_000_000;
    chain.observation().attribute("http.duration_ms", ms);
    return response;
};

FanarClient client = FanarClient.builder()
    .apiKey(System.getenv("FANAR_API_KEY"))
    .addInterceptor(timing)
    .build();
```

First-added interceptor wraps everything registered after it. Interceptors are sync, run on the caller's thread, and
can attach events/attributes to the current observation via `chain.observation()`.

---

## 9. Custom observability plugin

```java
import qa.fanar.core.spi.ObservabilityPlugin;

ObservabilityPlugin myPlugin = operationName -> new MyObservationHandle(operationName);

FanarClient client = FanarClient.builder()
    .apiKey(System.getenv("FANAR_API_KEY"))
    .observability(myPlugin)
    .build();
```

The default plugin is a no-op. The SDK emits one observation per semantic operation (`fanar.chat`,
`fanar.audio.speech`, etc.) with standardized attribute names defined in `FanarObservationAttributes`.
Three adapters ship: `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`. Combine them via
`ObservabilityPlugin.compose(slf4j, otel, micrometer)` — single slot, fan-out semantics.

---

## 10. Custom retry policy

```java
import qa.fanar.core.RetryPolicy;
import java.time.Duration;

RetryPolicy aggressive = RetryPolicy.defaults()
    .withMaxAttempts(5)
    .withBaseDelay(Duration.ofMillis(200))
    .withMaxDelay(Duration.ofSeconds(10));

FanarClient client = FanarClient.builder()
    .apiKey(System.getenv("FANAR_API_KEY"))
    .retryPolicy(aggressive)
    .build();

// Or opt out entirely:
RetryPolicy.disabled()
```

---

---

## 11. Spring Boot 4 starter

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-spring-boot-4-starter</artifactId>
    <version>${fanar.version}</version>
</dependency>
```

```yaml
fanar:
  api-key: ${FANAR_API_KEY}
  base-url: https://api.fanar.qa     # optional
  connect-timeout: 10s
  request-timeout: 60s
  retry:
    max-attempts: 3
    initial-backoff: 100ms
  wire-logging:
    level: BASIC                      # NONE | BASIC | HEADERS | BODY
```

```java
@RestController
class MyController {
    private final FanarClient fanar;
    MyController(FanarClient fanar) { this.fanar = fanar; }      // auto-wired
    // …
}
```

Any `Interceptor` or `ObservabilityPlugin` beans on the application context get picked up via
`ObjectProvider` and added to the client. With `spring-boot-starter-actuator` on the classpath,
`/actuator/health` includes a `fanar` contributor that calls `models().list()` — disable with
`management.health.fanar.enabled=false`.

---

## 12. Spring AI 2.0 provider

```xml
<dependency>
    <groupId>qa.fanar</groupId>
    <artifactId>fanar-spring-ai-starter</artifactId>
    <version>${fanar.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-client-chat</artifactId>
    <!-- 2.0.0-Mx; the starter pins the version we test against -->
</dependency>
```

The starter registers Spring AI `ChatModel`, `ImageModel`, `TextToSpeechModel`, `TranscriptionModel`
beans wrapping the auto-configured `FanarClient`. Compose with Spring AI's higher-level surface:

```java
@Bean
ChatMemory chatMemory() { return MessageWindowChatMemory.builder().maxMessages(20).build(); }

@Bean
ChatClient chatClient(ChatModel model, ChatMemory memory) {
    return ChatClient.builder(model)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
}

@PostMapping("/chat/{conversationId}")
String chat(@PathVariable("conversationId") String id, @RequestBody Prompt p) {
    return chatClient.prompt()
            .user(p.message())
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
            .call()
            .content();
}
```

Memory + RAG advisors + prompt templates + structured-output converters all attach via Spring
AI's standard machinery — we provide only the model SPIs.

---

## What this document does **not** show — and why

- **Conversation memory, prompt templating, vector stores, structured-output synthesis, evaluation harnesses.**
  These are framework concerns (ADR-002). Spring AI / LangChain4j already solve them; this SDK does not duplicate
  the work — it exposes the model SPIs they consume. Sections 11–12 above show the integration points.
- **`ModerationModel` / `EmbeddingModel` Spring AI adapters.** Surface mismatches with Fanar — see
  [COMPATIBILITY.md](COMPATIBILITY.md) §3 for the rationale. Use `FanarClient.moderations()` directly; bring an
  external embedder for RAG.
- **LangChain4j / Quarkus integration.** Planned, same pattern as the Spring AI adapter — see
  [PROJECT_STATE.md](PROJECT_STATE.md) for the roadmap.

Every snippet in this document should compile against the SDK once implemented. Until then, this is the *target*.
