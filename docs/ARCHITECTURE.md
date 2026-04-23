# Architecture

> OpenAPI 3.1.0 — 12 endpoints, 14 models

---

## 1. Fanar API Surface

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
