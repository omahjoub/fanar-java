# Architecture

> OpenAPI 3.1.0 — 12 endpoints, 14 models

---

## 1. Fanar API surface

**Base URL:** `https://api.fanar.qa` · **Auth:** Bearer token · **Compatibility:** OpenAI-compatible

### Endpoints

| Method | Path                       | Domain      | Content type  |
|--------|----------------------------|-------------|---------------|
| POST   | `/v1/chat/completions`     | Chat        | JSON / SSE    |
| POST   | `/v1/audio/speech`         | Audio       | JSON → binary |
| POST   | `/v1/audio/transcriptions` | Audio       | multipart     |
| GET    | `/v1/audio/voices`         | Audio       | JSON          |
| POST   | `/v1/audio/voices`         | Audio       | multipart     |
| DELETE | `/v1/audio/voices/{name}`  | Audio       | —             |
| POST   | `/v1/images/generations`   | Image       | JSON          |
| POST   | `/v1/translations`         | Translation | JSON          |
| POST   | `/v1/poems/generations`    | Poetry      | JSON          |
| POST   | `/v1/moderations`          | Safety      | JSON          |
| POST   | `/v1/tokens`               | Utility     | JSON          |
| GET    | `/v1/models`               | Utility     | JSON          |

### Models

| Model ID              | Rate limit | Domain             |
|-----------------------|------------|--------------------|
| `Fanar`               | 50/min     | Chat router        |
| `Fanar-S-1-7B`        | 50/min     | Chat (Star)        |
| `Fanar-C-1-8.7B`      | 50/min     | Chat (thinking v1) |
| `Fanar-C-2-27B`       | 50/min     | Chat (thinking v2) |
| `Fanar-Sadiq`         | 50/min     | Islamic RAG        |
| `Fanar-Oryx-IVU-2`    | 20/day     | Vision             |
| `Fanar-Aura-TTS-2`    | 20/day     | TTS                |
| `Fanar-Sadiq-TTS-1`   | 20/day     | Quranic TTS        |
| `Fanar-Aura-STT-1`    | 20/day     | STT short          |
| `Fanar-Aura-STT-LF-1` | 10/day     | STT long-form      |
| `Fanar-Oryx-IG-2`     | 20/day     | Image generation   |
| `Fanar-Guard-2`       | 50/min     | Moderation         |
| `Fanar-Shaheen-MT-1`  | 20/day     | Translation        |
| `Fanar-Diwan`         | 50/min     | Poetry             |

---

## 2. SDK architecture

### Module layout

```
fanar-java                            (reactor parent — NOT published)
├── core/                             qa.fanar:fanar-core                  — jar
│   └── src/main/java/
│       ├── module-info.java          module qa.fanar.core
│       └── qa/fanar/core/
│           ├── FanarClient, FanarException, ChatModel, …       (top-level public API)
│           ├── chat/                                           (ChatRequest, StreamEvent, …)
│           ├── audio/  images/  translations/  poems/
│           ├── moderation/  tokens/  models/                   (domain DTO packages)
│           ├── spi/                                            (Interceptor, FanarJsonCodec, …)
│           └── internal/                                       (transport, SSE parser, retry — not exported)
├── json-jackson2/                    qa.fanar:fanar-json-jackson2         — jar  (Spring Boot 3 / Jackson 2)
├── json-jackson3/                    qa.fanar:fanar-json-jackson3         — jar  (Spring Boot 4 / Jackson 3)
└── bom/                              qa.fanar:fanar-java-bom              — pom  (dependency management for consumers)
```

Each module has its own `module-info.java`. The reactor parent is never published; consumers import the BOM.

### Request flow — sync call

```
user code
    │
    │  client.chat().send(request)
    ▼
ChatClient (domain facade)                                                 qa.fanar.core.chat.ChatClient
    │
    │  validate → pick endpoint → start observation
    ▼
ObservabilityPlugin.start("fanar.chat")  ──────►  ObservationHandle   (AutoCloseable)
    │
    │  encode body via FanarJsonCodec, build HttpRequest, attach propagationHeaders()
    ▼
Interceptor chain (registration order: first-added is outermost)
    │
    ├─ BearerTokenInterceptor         adds Authorization header
    │
    ├─ RetryInterceptor               wraps the rest; retries on typed retryable errors
    │                                 emits observation.event("retry_attempt")
    │
    ├─ <user interceptors>            Chain.proceed(request) invokes the next link
    │
    ▼
JDK HttpClient.send(req, BodyHandlers.ofInputStream())                     qa.fanar.core.internal.transport
    │                                                                       (IOException → FanarTransportException)
    │
    ▼  HttpResponse
Interceptor chain unwinds (post-processing)
    │
    │  decode body via FanarJsonCodec, surface errors as typed FanarException subtypes
    ▼
ChatResponse                                                                qa.fanar.core.chat.ChatResponse
```

All on the caller's thread. On a virtual thread, the blocking I/O does not tie up a carrier (ADR-004).

### Request flow — streaming call

```
user code  ──────────► client.chat().stream(request)
                                    │
                                    │ (same as sync up to the HTTP call: validate, observation,
                                    │  interceptors, encode, send — but with BodyHandlers.ofLines())
                                    ▼
                       HttpClient.sendAsync(req, BodyHandlers.ofLines())    qa.fanar.core.internal.transport
                                    │
                                    ▼
                       Flow.Publisher<String>          (one line per item)
                                    │
                                    │ accumulate into SSE frames:          qa.fanar.core.internal.sse
                                    │   data: / event: / id: / blank line = dispatch
                                    ▼
                       per frame: FanarJsonCodec.decode(...) into
                                    │   the right StreamEvent subtype by shape
                                    ▼
                       Flow.Publisher<StreamEvent>  ◄── returned to caller
```

The caller's `Flow.Subscriber<StreamEvent>` consumes events as they arrive and pattern-matches on the sealed
`StreamEvent` hierarchy (ADR-005). Interceptors apply to the initial connection handshake only; mid-stream events
bypass them.

### Seams (extension points)

| Seam | SPI / config slot | Default if not set |
|---|---|---|
| HTTP transport | `.httpClient(HttpClient custom)` | JDK `HttpClient` built by the client, closed on `FanarClient.close()` |
| JSON codec | `FanarJsonCodec` via `.jsonCodec(codec)` or `ServiceLoader` | `ServiceLoader` discovery; **loud error** at `build()` if none found |
| Observability | `ObservabilityPlugin` via `.observability(plugin)` | No-op plugin |
| Interceptors | `.addInterceptor(i)` (registration order = chain order) | `BearerTokenInterceptor` (if apiKey set) + `RetryInterceptor` |
| Retry policy | `.retryPolicy(policy)` | `RetryPolicy.defaults()` — 3 attempts, exponential + full jitter, 30 s cap |
| User-Agent | `.userAgent(ua)` | `fanar-java/<version>` |
| Default headers | `.defaultHeader(name, value)` (repeated) | none |

Every seam is an interface; every default lives behind the builder's "if-not-set" guard. Core imports no third-party
runtime types — all seams use JDK types or our own interfaces (ADR-003, ADR-007, ADR-008, ADR-012, ADR-013).

### Error model

```
FanarException               (sealed, unchecked)
├── FanarTransportException  (wraps IOException / InterruptedException from transport)
├── FanarContentFilterException
├── FanarClientException     (sealed 4xx)
│   ├── FanarAuthenticationException
│   ├── FanarAuthorizationException
│   ├── FanarQuotaExceededException
│   ├── FanarNotFoundException
│   ├── FanarConflictException
│   ├── FanarTooLargeException
│   ├── FanarUnprocessableException
│   └── FanarGoneException
└── FanarServerException     (sealed 5xx)
    ├── FanarRateLimitException
    ├── FanarOverloadedException
    ├── FanarTimeoutException
    └── FanarInternalServerException
```

One subtype per Fanar `ErrorCode` plus `FanarTransportException` for JDK-transport failures. See ADR-006.

---

## 3. Where things live

Lookup table for "I want to change X, where's X?". **Status** reflects the design-phase skeleton; all marked
"not implemented" are to be built per [PROJECT_STATE.md](PROJECT_STATE.md).

| Concern | Package | Status |
|---|---|---|
| Public entry point | `qa.fanar.core.FanarClient` | not implemented |
| Exception hierarchy root | `qa.fanar.core.FanarException` | **implemented** (sealed, 13 subtypes) |
| Error-code enum | `qa.fanar.core.ErrorCode` | **implemented** |
| Content-filter-type enum | `qa.fanar.core.ContentFilterType` | **implemented** |
| Domain DTOs — chat messages | `qa.fanar.core.chat.Message` + variants + content parts + `ToolCall` | **implemented** |
| Domain DTOs — chat enums | `qa.fanar.core.chat.{ChatModel, Source, ImageDetail}` | **implemented** |
| `ChatRequest` / `ChatResponse` | `qa.fanar.core.chat` | not implemented |
| Other domain DTOs | `qa.fanar.core.<audio\|images\|translations\|poems\|moderation\|tokens\|models>` | not implemented |
| Sealed `StreamEvent` hierarchy | `qa.fanar.core.chat` | not implemented |
| Extension SPIs | `qa.fanar.core.spi` | **implemented** (FanarJsonCodec, Interceptor+Chain, ObservabilityPlugin, ObservationHandle, FanarObservationAttributes) |
| Default no-op observability | `qa.fanar.core.internal.observability` | **implemented** (NoopObservabilityPlugin, NoopObservationHandle) |
| Retry policy (public) | `qa.fanar.core.RetryPolicy` + `qa.fanar.core.JitterStrategy` | **implemented** (record + enum; retry loop still to come) |
| HTTP transport | `qa.fanar.core.internal.transport` | not implemented |
| SSE parser | `qa.fanar.core.internal.sse` | not implemented |
| Retry interceptor impl | `qa.fanar.core.internal.retry` | not implemented |
| Bearer-token interceptor impl | `qa.fanar.core.internal.auth` | not implemented |
| Jackson 2 codec | `qa.fanar.json.jackson2.Jackson2FanarJsonCodec` | placeholder (`Jackson2.java`) |
| Jackson 3 codec | `qa.fanar.json.jackson3.Jackson3FanarJsonCodec` | placeholder (`Jackson3.java`) |
| Reachability metadata | `META-INF/native-image/qa.fanar/<artifact>/` | not generated |

---

## References

- [Compatibility matrix](COMPATIBILITY.md) — capability view.
- [API sketch](API_SKETCH.md) — concrete code shapes for every call.
- [ADRs 010 + 011](adr/INDEX.md) — module and package conventions.
- [ADRs 007 + 008 + 017](adr/INDEX.md) — transport, JSON, SSE.
- [ADRs 012 + 013 + 014](adr/INDEX.md) — interceptor, observability, retry SPIs.
- [ADR 018](adr/018-internals-not-a-contract.md) — internals are not a contract.
