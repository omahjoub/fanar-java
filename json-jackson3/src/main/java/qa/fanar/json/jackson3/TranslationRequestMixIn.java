package qa.fanar.json.jackson3;

import com.fasterxml.jackson.annotation.JsonProperty;

import qa.fanar.core.translations.LanguagePair;

/**
 * Wire-naming override for {@link qa.fanar.core.translations.TranslationRequest}. The Java field
 * is {@code langPair} (camelCase, idiomatic) but the Fanar wire field is the single word
 * {@code "langpair"} (no underscore) — outside the SNAKE_CASE-strategy default.
 *
 * <p>Wired via {@code mapper.addMixIn(TranslationRequest.class, TranslationRequestMixIn.class)}
 * so the core record can stay annotation-free.</p>
 *
 * @author Oussama Mahjoub
 */
interface TranslationRequestMixIn {

    @JsonProperty("langpair")
    LanguagePair langPair();
}
