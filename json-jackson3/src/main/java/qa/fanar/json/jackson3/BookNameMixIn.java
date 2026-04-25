package qa.fanar.json.jackson3;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Jackson mix-in that flattens {@link qa.fanar.core.chat.BookName} to its single string value on
 * the wire (instead of {@code {"value": "title"}}). Wired via
 * {@code mapper.addMixIn(BookName.class, BookNameMixIn.class)} so the core type stays
 * annotation-free.
 *
 * <p>Only the serialization side is currently exercised — Fanar does not echo {@code book_names}
 * back on responses, so no decoder path consumes it. If round-tripping a {@code ChatRequest}
 * through the codec ever becomes a supported workflow, add an explicit {@code @JsonCreator}-style
 * deserializer at that point.</p>
 */
interface BookNameMixIn {

    @JsonValue
    String wireValue();
}
