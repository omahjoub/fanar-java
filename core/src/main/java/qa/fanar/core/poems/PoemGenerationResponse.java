package qa.fanar.core.poems;

import java.util.Objects;

/**
 * Response from {@code POST /v1/poems/generations}.
 *
 * @param id   unique identifier for this generation
 * @param poem the generated poem text
 *
 * @author Oussama Mahjoub
 */
public record PoemGenerationResponse(String id, String poem) {

    public PoemGenerationResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(poem, "poem");
    }
}
