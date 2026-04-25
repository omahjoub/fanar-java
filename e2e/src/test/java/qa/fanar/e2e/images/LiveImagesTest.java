package qa.fanar.e2e.images;

import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarTimeoutException;
import qa.fanar.core.images.ImageGenerationItem;
import qa.fanar.core.images.ImageGenerationRequest;
import qa.fanar.core.images.ImageGenerationResponse;
import qa.fanar.core.images.ImageModel;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.LiveOutputs;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live battle-test of {@code POST /v1/images/generations} via {@link FanarClient#images()},
 * parameterized over both codec adapters.
 *
 * <p>Per the Fanar spec this endpoint requires additional authorization. {@code Fanar-Oryx-IG-2}
 * <em>was</em> visible in the live {@code /v1/models} listing as of 2026-04-25, so the call
 * is more likely to succeed than {@code /v1/poems/generations} — but we still tolerate the
 * documented access-error exceptions and log instead of failing, mirroring §M.5.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveImagesTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.6 generate returns at least one base64-encoded image (or surfaces a typed access error)")
    void generate_returnsBase64Image(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            try {
                ImageGenerationResponse r = client.images().generate(
                        ImageGenerationRequest.of(
                                ImageModel.FANAR_ORYX_IG_2,
                                "A futuristic cityscape at sunset"));

                assertNotNull(r.id(), "response id must be present");
                assertTrue(r.created() > 0, "created timestamp must be positive");
                assertFalse(r.data().isEmpty(), "expected at least one image");

                ImageGenerationItem item = r.data().getFirst();
                assertNotNull(item.b64Json(), "b64Json must be present");
                assertFalse(item.b64Json().isBlank(), "b64Json must not be blank");

                // Soft validation: the body should round-trip through the JDK Base64 decoder.
                byte[] decoded = Base64.getDecoder().decode(item.b64Json());
                String ext = LiveOutputs.detectImageExtension(decoded);
                Path file = LiveOutputs.write("image-output", "image-cityscape", ext, decoded);
                System.out.println("Live /v1/images/generations: decoded " + decoded.length
                        + " bytes (base64 length " + item.b64Json().length() + ") → " + file);
            } catch (FanarAuthorizationException | FanarNotFoundException | FanarTimeoutException e) {
                // Same lenient pattern as §M.5: SDK shape + typed-exception mapping is the
                // signal we care about; an upstream access gap shouldn't fail the build.
                System.out.println("Live /v1/images/generations: "
                        + e.getClass().getSimpleName() + " (endpoint requires extra access) — "
                        + e.getMessage());
            }
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§M.6 generateAsync().get() completes against live infra with a non-blank base64 image")
    void generate_asyncCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            ImageGenerationResponse r = client.images().generateAsync(
                    ImageGenerationRequest.of(
                            ImageModel.FANAR_ORYX_IG_2,
                            "A futuristic cityscape at sunset"))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(r.id(), "response id must be present");
            assertFalse(r.data().isEmpty(), "expected at least one image");
            ImageGenerationItem item = r.data().getFirst();
            assertNotNull(item.b64Json(), "b64Json must be present");
            assertFalse(item.b64Json().isBlank(), "b64Json must not be blank");
        }
    }
}
