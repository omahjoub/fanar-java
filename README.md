# Fanar Java SDK

Java SDK for [Fanar](https://fanar.qa) — Qatar's Arabic-centric multimodal AI platform.

## Why this SDK?

Fanar's API is OpenAI-compatible. You *could* use the OpenAI Java SDK with `base_url = "https://api.fanar.qa/v1"` and it
would work for basic chat. So why does this SDK exist?

**Because Fanar is not OpenAI.**

Fanar has capabilities that no OpenAI client knows about. Islamic RAG with authenticated source references. Quranic
text-to-speech with validated tajweed recitation. Arabic poetry generation through a dedicated model. Cultural awareness
scoring in content moderation. Bilingual progress events during streaming. Two distinct thinking mode protocols. A
vision model that understands Arabic calligraphy.