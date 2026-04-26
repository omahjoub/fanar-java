package qa.fanar.obs.micrometer;

import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.observation.tck.TestObservationRegistryAssert.TestObservationRegistryAssertReturningObservationContextAssert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MicrometerObservabilityPluginTest {

    private TestObservationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = TestObservationRegistry.create();
    }

    private MicrometerObservabilityPlugin plugin() {
        return new MicrometerObservabilityPlugin(registry);
    }

    /** Public-API shortcut to the assert chain for an observation by name. */
    private TestObservationRegistryAssertReturningObservationContextAssert obs(String name) {
        return TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(name)
                .that();
    }

    // --- start --------------------------------------------------------------------------------

    @Test
    void start_opensObservationWithGivenName() {
        try (ObservationHandle h = plugin().start("fanar.chat.send")) {
            assertNotNull(h);
        }
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("fanar.chat.send");
    }

    @Test
    void start_rejectsNullOperationName() {
        assertThrows(NullPointerException.class, () -> plugin().start(null));
    }

    @Test
    void start_observationIsStoppedOnClose() {
        try (ObservationHandle ignored = plugin().start("fanar.chat.send")) {
            // open
        }
        obs("fanar.chat.send").hasBeenStopped();
    }

    // --- attribute ----------------------------------------------------------------------------

    @Test
    void attribute_recordsLowCardinalityKeyValue() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("fanar.model", "Fanar-S-1-7B");
        }
        obs("op").hasLowCardinalityKeyValue("fanar.model", "Fanar-S-1-7B");
    }

    @Test
    void attribute_nonStringValueStringified() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("http.status_code", 200);
        }
        obs("op").hasLowCardinalityKeyValue("http.status_code", "200");
    }

    @Test
    void attribute_nullValueIsSkipped() {
        try (ObservationHandle h = plugin().start("op")) {
            h.attribute("k", null);
        }
        obs("op").doesNotHaveLowCardinalityKeyValueWithKey("k");
    }

    @Test
    void attribute_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.attribute("k", "v"));
        }
    }

    @Test
    void attribute_rejectsNullKey() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.attribute(null, "v"));
        }
    }

    // --- attribute: filter / redactor ---------------------------------------------------------

    @Test
    void attributeFilter_dropsKeysReturningFalse() {
        MicrometerObservabilityPlugin filtered = MicrometerObservabilityPlugin.builder(registry)
                .attributeFilter(key -> !key.startsWith("internal."))
                .build();
        try (ObservationHandle h = filtered.start("op")) {
            h.attribute("http.status_code", 200);
            h.attribute("internal.thread_id", 42);
            h.attribute("fanar.model", "Fanar-S-1-7B");
        }
        obs("op")
                .hasLowCardinalityKeyValue("http.status_code", "200")
                .hasLowCardinalityKeyValue("fanar.model", "Fanar-S-1-7B")
                .doesNotHaveLowCardinalityKeyValueWithKey("internal.thread_id");
    }

    @Test
    void attributeRedactor_replacesValueForMatchedKey() {
        MicrometerObservabilityPlugin redacting = MicrometerObservabilityPlugin.builder(registry)
                .attributeRedactor((k, v) -> "fanar.user_id".equals(k) ? "***" : v)
                .build();
        try (ObservationHandle h = redacting.start("op")) {
            h.attribute("fanar.user_id", "u-1234567890");
            h.attribute("http.status_code", 200);
        }
        obs("op")
                .hasLowCardinalityKeyValue("fanar.user_id", "***")
                .hasLowCardinalityKeyValue("http.status_code", "200");
    }

    @Test
    void attributeRedactor_returningNullSkipsKey() {
        MicrometerObservabilityPlugin redacting = MicrometerObservabilityPlugin.builder(registry)
                .attributeRedactor((k, v) -> null)
                .build();
        try (ObservationHandle h = redacting.start("op")) {
            h.attribute("k", "v");
        }
        obs("op").doesNotHaveLowCardinalityKeyValueWithKey("k");
    }

    // --- event --------------------------------------------------------------------------------

    @Test
    void event_addsObservationEvent() {
        try (ObservationHandle h = plugin().start("op")) {
            h.event("retry_attempt");
        }
        obs("op").hasEvent("retry_attempt");
    }

    @Test
    void event_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.event("e"));
        }
    }

    @Test
    void event_rejectsNullName() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.event(null));
        }
    }

    // --- error --------------------------------------------------------------------------------

    @Test
    void error_recordsThrowableOnObservation() {
        RuntimeException boom = new RuntimeException("boom");
        try (ObservationHandle h = plugin().start("op")) {
            h.error(boom);
        }
        obs("op").hasError(boom);
    }

    @Test
    void error_returnsThis() {
        try (ObservationHandle h = plugin().start("op")) {
            assertSame(h, h.error(new RuntimeException("x")));
        }
    }

    @Test
    void error_rejectsNullThrowable() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.error(null));
        }
    }

    // --- close --------------------------------------------------------------------------------

    @Test
    void close_isIdempotent() {
        ObservationHandle h = plugin().start("op");
        h.close();
        h.close();
        // Idempotency check: still exactly one stopped observation in the registry.
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsEqualTo(1);
        obs("op").hasBeenStopped();
    }

    // --- child --------------------------------------------------------------------------------

    @Test
    void child_createsChildParentedToCurrentObservation() {
        try (ObservationHandle parent = plugin().start("parent")) {
            try (ObservationHandle child = parent.child("child")) {
                assertNotSame(parent, child);
            }
        }
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsEqualTo(2);
        obs("parent").hasBeenStopped();
        obs("child").hasParentObservationContextMatching(
                ctx -> "parent".equals(ctx.getName()));
    }

    @Test
    void child_inheritsFilterAndRedactor() {
        MicrometerObservabilityPlugin custom = MicrometerObservabilityPlugin.builder(registry)
                .attributeFilter(key -> !"secret".equals(key))
                .attributeRedactor((k, v) -> "fanar.model".equals(k) ? "REDACTED" : v)
                .build();
        try (ObservationHandle parent = custom.start("parent")) {
            try (ObservationHandle child = parent.child("child")) {
                child.attribute("secret", "hide-me");
                child.attribute("fanar.model", "Fanar-S-1-7B");
                child.attribute("http.status_code", 200);
            }
        }
        obs("child")
                .doesNotHaveLowCardinalityKeyValueWithKey("secret")
                .hasLowCardinalityKeyValue("fanar.model", "REDACTED")
                .hasLowCardinalityKeyValue("http.status_code", "200");
    }

    @Test
    void child_rejectsNullOperationName() {
        try (ObservationHandle h = plugin().start("op")) {
            assertThrows(NullPointerException.class, () -> h.child(null));
        }
    }

    // --- propagationHeaders ------------------------------------------------------------------

    @Test
    void propagationHeaders_returnsEmptyMap() {
        try (ObservationHandle h = plugin().start("op")) {
            assertEquals(Map.of(), h.propagationHeaders());
        }
    }

    // --- builder validation ------------------------------------------------------------------

    @Test
    void publicConstructorRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new MicrometerObservabilityPlugin((ObservationRegistry) null));
    }

    @Test
    void builder_rejectsNullRegistry() {
        assertThrows(NullPointerException.class,
                () -> MicrometerObservabilityPlugin.builder(null));
    }

    @Test
    void builder_attributeFilterRejectsNull() {
        MicrometerObservabilityPlugin.Builder b = MicrometerObservabilityPlugin.builder(registry);
        assertThrows(NullPointerException.class, () -> b.attributeFilter(null));
    }

    @Test
    void builder_attributeRedactorRejectsNull() {
        MicrometerObservabilityPlugin.Builder b = MicrometerObservabilityPlugin.builder(registry);
        assertThrows(NullPointerException.class, () -> b.attributeRedactor(null));
    }

    @Test
    void builder_settersAreFluent() {
        MicrometerObservabilityPlugin.Builder b = MicrometerObservabilityPlugin.builder(registry);
        assertSame(b, b.attributeFilter(key -> true));
        assertSame(b, b.attributeRedactor((k, v) -> v));
    }
}
