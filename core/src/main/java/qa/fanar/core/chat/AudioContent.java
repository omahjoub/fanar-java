package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Audio content part in a chat-completion response.
 *
 * <p>Returned by chat models that can emit audio output. Referenced by URL; no input-side
 * counterpart — audio is output-only in the chat domain (input audio goes through the STT
 * endpoint instead).</p>
 *
 * @param url the audio URL; must not be {@code null}
 */
public record AudioContent(String url) implements ResponseContent {

    public AudioContent {
        Objects.requireNonNull(url, "url");
    }
}
