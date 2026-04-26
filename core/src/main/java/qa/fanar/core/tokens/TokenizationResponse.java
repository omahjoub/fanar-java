package qa.fanar.core.tokens;

import java.util.Objects;

/**
 * Response from {@code POST /v1/tokens} — the token count for the submitted content along with
 * the model's per-request budget.
 *
 * @param id                unique identifier for this tokenization
 * @param tokens            number of tokens the content occupies for the chosen model
 * @param maxRequestTokens  server-side ceiling on tokens per request for the chosen model;
 *                          callers can use this to size {@code maxTokens} or to chunk inputs
 *
 * @author Oussama Mahjoub
 */
public record TokenizationResponse(String id, int tokens, int maxRequestTokens) {

    public TokenizationResponse {
        Objects.requireNonNull(id, "id");
    }
}
