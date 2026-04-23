# Fanar Java SDK

Java SDK for [Fanar](https://fanar.qa) — Qatar's Arabic-centric multimodal AI platform.

> **Status:** design phase. No public release yet. The lighthouse document for every design decision is
> [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md).

## Why this SDK?

**To open Fanar to the Java world.**

Java still powers the majority of production systems in enterprise, finance, government, telco and research — and
the JVM ecosystem has doubled down on AI. A first-class SDK puts Fanar in the idiomatic shape each of these audiences
already expects:

- **Spring** and **Spring Boot** applications — auto-configured clients, properties, Actuator endpoints
- **Spring AI** and **LangChain4j** — Fanar as a native provider alongside OpenAI, Anthropic, Mistral & co
- **Quarkus** and **Quarkus LangChain4j** — CDI beans, build-time wiring, fast startup
- **GraalVM native-image** workloads — reflection-free by design, ready for serverless and edge
- **Plain-JDK** users on `java.net.http.HttpClient`, and Kotlin on top — zero framework required

That is the real opportunity: Qatar's AI platform meeting the global developer stage in the ecosystem that still runs
the lion's share of the world's backends.

And yes — you *could* point the OpenAI Java SDK at `https://api.fanar.qa/v1` and get basic chat. So why a dedicated SDK?

**Because Fanar is not OpenAI.** Fanar has capabilities no OpenAI client knows about — Islamic RAG with authenticated
source references, Quranic text-to-speech with validated tajweed recitation, Arabic poetry through a dedicated model,
cultural-awareness scoring in moderation, bilingual progress events during streaming, two distinct thinking-mode
protocols, and a vision model that understands Arabic calligraphy. None of that fits in an OpenAI-shaped client.

The core SDK will be a **thin, pluggable, observable transport** over the Fanar API — nothing more. Memory, templating,
vectors and evaluation belong in **downstream framework modules** on top: Spring Boot auto-configuration, Spring AI and
LangChain4j provider bindings, Quarkus build-time integration, and whatever else the ecosystem needs.

## Docs

- [Compatibility matrix](docs/COMPATIBILITY.md) — the lighthouse: what's in core, what's framework-layer, what makes Fanar unique
- [Architecture](docs/ARCHITECTURE.md) — API surface, endpoints, models
- [ADRs](docs/adr/INDEX.md) — non-obvious design decisions
- [API sketch](docs/API_SKETCH.md) — aspirational code shape; the target the implementation aims for (living document, revised as we learn)
- [Contributing](docs/CONTRIBUTING.md)
- [Fanar OpenAPI spec](api-spec/openapi.json)
