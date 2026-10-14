package qa.fanar.e2e.translations;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationRequest;
import qa.fanar.core.translations.TranslationResponse;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live battle-test of {@code POST /v1/translations} via {@link FanarClient#translations()},
 * parameterized over both codec adapters.
 *
 * <p>Works for the standard key (observed 2026-04-25, re-confirmed 2026-08-29; {@code 20;w=86400}).
 * The 2026-04 spec flagged this endpoint as needing additional authorization, the 2026-08 spec no
 * longer does; should a key lose access the test surfaces a {@code FanarAuthorizationException}
 * loudly — share the wire log when that happens. Skipped when {@code FANAR_API_KEY} is not set.
 * Dated record: {@code docs/WIRE_OBSERVATIONS.md}.</p>
 *
 * <p>{@code Fanar-Shaheen-MT-2} — added to the spec's translation models in the 2026-10 drop — works
 * for the standard key too (observed 2026-10-14), on its own {@code 20;w=86400} window: its
 * {@code remaining} counts down from 19 independently of MT-1's. One case per codec, 2 of its 20 per
 * full run.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveTranslationsTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.4 translate en→ar returns non-empty Arabic text")
    void translate_returnsNonEmptyTranslation(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            TranslationResponse r = client.translations().translate(
                    TranslationRequest.of(
                            TranslationModel.FANAR_SHAHEEN_MT_1,
                            "Hello, how are you?",
                            LanguagePair.EN_AR));

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.text(), "translated text must be present");
            assertFalse(r.text().isBlank(), "translated text must not be blank");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.4 translateAsync().get() completes against live infra with non-blank text")
    void translate_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            TranslationResponse r = client.translations().translateAsync(
                    TranslationRequest.of(
                            TranslationModel.FANAR_SHAHEEN_MT_1,
                            "Hello, how are you?",
                            LanguagePair.EN_AR))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.text(), "translated text must be present");
            assertFalse(r.text().isBlank(), "translated text must not be blank");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.4 translate en→ar with Fanar-Shaheen-MT-2 returns non-empty Arabic text")
    void translate_withShaheenMt2ReturnsNonEmptyTranslation(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            TranslationResponse r = client.translations().translate(
                    TranslationRequest.of(
                            TranslationModel.FANAR_SHAHEEN_MT_2,
                            "Hello, how are you?",
                            LanguagePair.EN_AR));

            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.text(), "translated text must be present");
            assertFalse(r.text().isBlank(), "translated text must not be blank");
        }
    }
}
