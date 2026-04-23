# ADR-016 — `FanarClient` builder and domain facades

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

`FanarClient` is the entry point. Users touch it first; its shape forms their first impression of the SDK and
determines how framework wrappers (Spring Boot starter, Quarkus extension, CDI bindings, plain-JDK consumers) can
bridge external configuration into our typed API. We must decide: monolithic client or domain facades; builder
options; lifecycle; failure modes; environment-variable fallback.

The design must satisfy two constraints that can feel opposed: small public surface on the one hand, and enough
configurability to cover every reasonable enterprise deployment on the other.

## Decision

### Domain facades, not monolithic methods

`FanarClient` exposes accessor methods returning domain-specific client interfaces:

```java
public final class FanarClient implements AutoCloseable {
    public static Builder builder() { return new Builder(); }

    public ChatClient         chat();
    public AudioClient        audio();
    public ImagesClient       images();
    public TranslationsClient translations();
    public PoemsClient        poems();
    public ModerationClient   moderation();
    public TokensClient       tokens();
    public ModelsClient       models();

    @Override public void close();
}
```

Each facade is an interface with sync / async / streaming methods where applicable. Example:

```java
public interface ChatClient {
    ChatResponse send(ChatRequest req);
    CompletableFuture<ChatResponse> sendAsync(ChatRequest req);
    Flow.Publisher<StreamEvent> stream(ChatRequest req);
}
```

Facades map 1:1 to the domain-grouped packages (ADR-011).

### Builder method list

```java
public static final class Builder {
    // Authentication
    public Builder apiKey(String key);
    public Builder apiKey(Supplier<String> tokenSupplier);           // rotation (ADR-012)

    // Endpoint
    public Builder baseUrl(URI uri);

    // Transport & serialization seams
    public Builder httpClient(HttpClient custom);                     // ADR-007
    public Builder jsonCodec(FanarJsonCodec codec);                   // ADR-008; else ServiceLoader

    // Cross-cutting SPIs
    public Builder addInterceptor(Interceptor interceptor);           // ADR-012
    public Builder retryPolicy(RetryPolicy policy);                   // ADR-014
    public Builder observability(ObservabilityPlugin plugin);         // ADR-013

    // Timeouts & headers
    public Builder connectTimeout(Duration d);
    public Builder requestTimeout(Duration d);
    public Builder userAgent(String userAgent);
    public Builder defaultHeader(String name, String value);          // repeated calls append

    public FanarClient build();
}
```

### Lifecycle and ownership

- `FanarClient implements AutoCloseable`. Use with try-with-resources.
- **Ownership rule**: if the user passed an `HttpClient` via `.httpClient(custom)`, they own its lifecycle.
  Otherwise `FanarClient.close()` closes the internal `HttpClient` (Java 21+ supports this, ADR-001).

### Environment-variable fallback

- `FANAR_API_KEY` — consulted if `.apiKey(...)` is not called.
- `FANAR_BASE_URL` — consulted if `.baseUrl(...)` is not called.

Both follow the convention established by AWS SDK, OpenAI Java SDK, Google Cloud, Stripe, and others: explicit builder
call wins; environment fills in when absent. Makes serverless and Docker use zero-configuration.

### Per-client timeouts only (v1)

`connectTimeout` and `requestTimeout` are set once on the client and apply to all operations. Per-request overrides
may be added later as an additive, non-breaking change if demand emerges.

### Failure modes in `build()`

Loud, early, helpful:

- **No API key** (neither explicit nor `FANAR_API_KEY`): `IllegalStateException("No Fanar API key configured. Call
  .apiKey(...) or set FANAR_API_KEY.")`.
- **No JSON codec** (neither explicit nor discovered via `ServiceLoader`): `IllegalStateException("No FanarJsonCodec
  found on the classpath. Add fanar-json-jackson3 (Spring Boot 4 / Jackson 3) or fanar-json-jackson2 (Spring Boot 3
  / Jackson 2) to your build.")`.
- **Invalid retry policy** (`maxAttempts < 1`, non-positive delays): `IllegalArgumentException` at the
  `retryPolicy(...)` call site, not deferred to `build()`.
- **Invalid base URL**: `IllegalArgumentException` at `baseUrl(...)`.

## Alternatives considered

- **Monolithic client with 30+ methods** on `FanarClient`. *Rejected*: violates the Interface Segregation Principle;
  becomes unmaintainable as Fanar adds endpoints; bloats Javadoc and IDE auto-complete.
- **No environment-variable fallback**. *Rejected*: hostile to Docker, serverless, CI pipelines that expect 12-factor
  configuration.
- **Per-request timeouts** from v1. *Rejected*: adds complexity to every DTO. Defer until there's a demonstrated use
  case (long-form STT is the leading candidate); addable without breaking the current API.
- **Fluent DSL with nested `Consumer<SubBuilder>` blocks**. *Rejected*: harder for framework config systems to
  mechanically bind to; less conventional; no clear benefit over flat builder methods.

## Consequences

### Positive
- Each domain's API evolves independently. New Fanar endpoints become new facade methods, not changes to `FanarClient`.
- Matches the pattern in AWS SDK v2, Azure SDK for Java, Google Cloud Java, OpenAI Java SDK — users have muscle memory.
- Framework starters can bind external configuration (Spring Boot's `@ConfigurationProperties`, Quarkus'
  `@ConfigMapping`, Micronaut's `@ConfigurationProperties`) to builder calls with no special contract on our side.
- Helpful build-time errors shorten a common first-run failure (missing Jackson adapter) from a confusing
  stack trace to a copy-pasteable artifact name.

### Negative / Trade-offs
- Domain facade interfaces are part of the public API surface. Changing a facade method signature is a breaking change
  subject to the deprecation discipline (JLBP-7, ADR-019). Worth it for the independence it buys.
- Eight facade interfaces to name and document — more surface than a monolithic client, less surface than what it
  replaces inside that client.

### Neutral
- The builder is immutable once `build()` returns. Reconfiguration means constructing a new client.

## References

- ADR-002 Narrow core SDK scope
- ADR-004 Sync-primary API with async sugar
- ADR-005 Streaming via `Flow.Publisher`
- ADR-007 JDK `HttpClient` as the default transport
- ADR-008 JSON as an SPI with two Jackson adapters
- ADR-011 Package conventions
- ADR-012 Interceptor SPI
- ADR-013 Observability SPI
- ADR-014 Retry policy defaults
- ADR-019 Pre-1.0 stability policy
