package qa.fanar.core.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link ObservabilityPlugin#compose(ObservabilityPlugin...)} and the internal
 * composite handle. Uses a {@link Recorder} fixture to capture every call dispatched to a
 * child plugin.
 */
class CompositeObservabilityPluginTest {

    // --- failure isolation (ADR-022) ----------------------------------------------------

    /**
     * A plugin that throws on every call. Telemetry must never be able to fail the caller's
     * request, nor stop a sibling plugin observing.
     */
    private static final class ExplodingPlugin implements ObservabilityPlugin, ObservationHandle {
        boolean throwOnStart;

        @Override public ObservationHandle start(String operationName) {
            if (throwOnStart) {
                throw new IllegalStateException("backend down");
            }
            return this;
        }
        @Override public ObservationHandle attribute(String k, Object v) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle event(String n) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle error(Throwable t) { throw new IllegalStateException("boom"); }
        @Override public ObservationHandle child(String n) { throw new IllegalStateException("boom"); }
        @Override public Map<String, String> propagationHeaders() { throw new IllegalStateException("boom"); }
        @Override public void close() { throw new IllegalStateException("boom"); }
    }

    @Test
    void aThrowingChildNeitherFailsTheCallerNorSilencesItsSiblings() {
        Recorder healthy = new Recorder();
        healthy.headers = Map.of("traceparent", "00-abc-def-01");
        ExplodingPlugin broken = new ExplodingPlugin();

        ObservabilityPlugin composite = ObservabilityPlugin.compose(broken, healthy);

        ObservationHandle h = composite.start("fanar.chat.send");
        h.attribute("fanar.model", "Fanar");
        h.event("retry_attempt");
        h.error(new IllegalArgumentException("caller-side"));
        assertEquals(Map.of("traceparent", "00-abc-def-01"), h.propagationHeaders(),
                "the healthy plugin's headers survive a sibling that throws");
        h.child("decode").attribute("k", "v");
        h.close();

        assertTrue(healthy.calls.contains("attribute:fanar.model=Fanar"));
        assertTrue(healthy.calls.contains("event:retry_attempt"));
        assertTrue(healthy.calls.contains("error:caller-side"));
        assertTrue(healthy.calls.contains("close"));
    }

    @Test
    void aPluginThatThrowsOnStartGetsASilentSlotAndTheRestKeepWorking() {
        Recorder healthy = new Recorder();
        ExplodingPlugin broken = new ExplodingPlugin();
        broken.throwOnStart = true;

        ObservationHandle h = ObservabilityPlugin.compose(broken, healthy).start("fanar.chat.send");
        h.attribute("fanar.model", "Fanar");
        h.close();

        assertTrue(healthy.calls.contains("attribute:fanar.model=Fanar"),
                "a plugin that could not start must not take its siblings down with it");
        assertTrue(healthy.calls.contains("close"));
    }

    @Test
    void aPluginReturningNullFromStartGetsASilentSlot() {
        Recorder healthy = new Recorder();
        ObservabilityPlugin nullStarter = operationName -> null;

        ObservationHandle h = ObservabilityPlugin.compose(nullStarter, healthy).start("fanar.chat.send");
        h.attribute("fanar.model", "Fanar");
        h.close();

        assertTrue(healthy.calls.contains("attribute:fanar.model=Fanar"));
    }

    // --- factory shortcuts --------------------------------------------------------------

    @Test
    void compose_zeroPluginsReturnsNoop() {
        assertSame(ObservabilityPlugin.noop(), ObservabilityPlugin.compose());
    }

    @Test
    void compose_singlePluginReturnsItUnwrapped() {
        Recorder p = new Recorder();
        assertSame(p, ObservabilityPlugin.compose(p));
    }

    @Test
    void compose_multiplePluginsReturnsCompositeImpl() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ObservabilityPlugin composite = ObservabilityPlugin.compose(a, b);
        assertFalse(composite instanceof Recorder, "composite must wrap, not pass through");
    }

    @Test
    void compose_listOverloadAccepted() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ObservabilityPlugin composite = ObservabilityPlugin.compose(List.of(a, b));
        try (ObservationHandle h = composite.start("op")) {
            h.attribute("k", "v");
        }
        assertEquals(List.of("start:op", "attribute:k=v", "close"), a.calls);
        assertEquals(List.of("start:op", "attribute:k=v", "close"), b.calls);
    }

    @Test
    void compose_rejectsNullArrayArgument() {
        assertThrows(NullPointerException.class,
                () -> ObservabilityPlugin.compose((ObservabilityPlugin[]) null));
    }

    @Test
    void compose_rejectsNullListArgument() {
        assertThrows(NullPointerException.class,
                () -> ObservabilityPlugin.compose((List<ObservabilityPlugin>) null));
    }

    @Test
    void compose_rejectsNullElement() {
        Recorder a = new Recorder();
        assertThrows(NullPointerException.class,
                () -> ObservabilityPlugin.compose(a, null));
    }

    // --- start fan-out -------------------------------------------------------------------

    @Test
    void start_fansOutToEveryChild() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ObservabilityPlugin.compose(a, b).start("fanar.chat.send").close();
        assertTrue(a.calls.contains("start:fanar.chat.send"));
        assertTrue(b.calls.contains("start:fanar.chat.send"));
    }

    @Test
    void start_rejectsNullOperationName() {
        ObservabilityPlugin composite = ObservabilityPlugin.compose(new Recorder(), new Recorder());
        assertThrows(NullPointerException.class, () -> composite.start(null));
    }

    // --- handle fan-out ------------------------------------------------------------------

    @Test
    void attribute_fansOutToEveryChildAndReturnsThis() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        try (ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op")) {
            assertSame(h, h.attribute("k", "v"));
        }
        assertTrue(a.calls.contains("attribute:k=v"));
        assertTrue(b.calls.contains("attribute:k=v"));
    }

    @Test
    void event_fansOutToEveryChildAndReturnsThis() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        try (ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op")) {
            assertSame(h, h.event("retry"));
        }
        assertTrue(a.calls.contains("event:retry"));
        assertTrue(b.calls.contains("event:retry"));
    }

    @Test
    void error_fansOutToEveryChildAndReturnsThis() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        RuntimeException boom = new RuntimeException("boom");
        try (ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op")) {
            assertSame(h, h.error(boom));
        }
        assertTrue(a.calls.contains("error:boom"));
        assertTrue(b.calls.contains("error:boom"));
    }

    @Test
    void attribute_rejectsNullKey() {
        try (ObservationHandle h = ObservabilityPlugin.compose(new Recorder(), new Recorder())
                .start("op")) {
            assertThrows(NullPointerException.class, () -> h.attribute(null, "v"));
        }
    }

    @Test
    void event_rejectsNullName() {
        try (ObservationHandle h = ObservabilityPlugin.compose(new Recorder(), new Recorder())
                .start("op")) {
            assertThrows(NullPointerException.class, () -> h.event(null));
        }
    }

    @Test
    void error_rejectsNullThrowable() {
        try (ObservationHandle h = ObservabilityPlugin.compose(new Recorder(), new Recorder())
                .start("op")) {
            assertThrows(NullPointerException.class, () -> h.error(null));
        }
    }

    // --- child ---------------------------------------------------------------------------

    @Test
    void child_fansOutToEveryChildAndReturnsCompositeHandle() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        try (ObservationHandle parent = ObservabilityPlugin.compose(a, b).start("parent")) {
            try (ObservationHandle child = parent.child("decode")) {
                child.attribute("k", "v");
            }
        }
        assertTrue(a.calls.contains("child:decode"), "first plugin must see child(decode)");
        assertTrue(b.calls.contains("child:decode"), "second plugin must see child(decode)");
        // Attribute must reach the children's child handles, not the parent handles
        assertTrue(a.calls.contains("attribute(child):k=v"));
        assertTrue(b.calls.contains("attribute(child):k=v"));
    }

    @Test
    void child_rejectsNullOperationName() {
        try (ObservationHandle h = ObservabilityPlugin.compose(new Recorder(), new Recorder())
                .start("op")) {
            assertThrows(NullPointerException.class, () -> h.child(null));
        }
    }

    // --- propagationHeaders --------------------------------------------------------------

    @Test
    void propagationHeaders_mergeFromAllChildren() {
        Recorder a = new Recorder();
        a.headers = Map.of("traceparent", "00-aaa-bbb-01");
        Recorder b = new Recorder();
        b.headers = Map.of("baggage", "k=v");
        try (ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op")) {
            Map<String, String> merged = h.propagationHeaders();
            assertEquals("00-aaa-bbb-01", merged.get("traceparent"));
            assertEquals("k=v", merged.get("baggage"));
            assertEquals(2, merged.size());
        }
    }

    @Test
    void propagationHeaders_lastChildWinsOnKeyCollision() {
        Recorder a = new Recorder();
        a.headers = Map.of("traceparent", "00-aaa-bbb-01");
        Recorder b = new Recorder();
        b.headers = Map.of("traceparent", "00-zzz-www-01");
        try (ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op")) {
            assertEquals("00-zzz-www-01", h.propagationHeaders().get("traceparent"),
                    "later plugin wins on key collision");
        }
    }

    @Test
    void propagationHeaders_emptyWhenAllChildrenEmpty() {
        try (ObservationHandle h = ObservabilityPlugin.compose(new Recorder(), new Recorder())
                .start("op")) {
            assertEquals(Map.of(), h.propagationHeaders());
        }
    }

    // --- close ---------------------------------------------------------------------------

    @Test
    void close_fansOutToEveryChild() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op");
        h.close();
        assertTrue(a.calls.contains("close"));
        assertTrue(b.calls.contains("close"));
    }

    @Test
    void close_isIdempotent() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ObservationHandle h = ObservabilityPlugin.compose(a, b).start("op");
        h.close();
        h.close();
        assertEquals(1, a.calls.stream().filter("close"::equals).count(),
                "second close must not re-dispatch to children");
        assertEquals(1, b.calls.stream().filter("close"::equals).count());
    }

    // --- exception containment -----------------------------------------------------------

    @Test
    void start_containsAChildPluginFailureAndStillReachesLaterChildren() {
        // Previously this asserted the opposite — the exception propagated to the caller and
        // short-circuited the remaining plugins, so one broken backend both failed the request
        // and blinded every plugin registered after it. Telemetry does not get that power
        // (ADR-022).
        Recorder a = new Recorder();
        ObservabilityPlugin failing = name -> { throw new RuntimeException("plugin boom"); };
        Recorder c = new Recorder();

        ObservabilityPlugin composite = ObservabilityPlugin.compose(a, failing, c);
        ObservationHandle h = assertDoesNotThrow(() -> composite.start("op"),
                "a child plugin's failure must not surface to the caller");
        h.close();

        assertTrue(a.calls.contains("start:op"), "the child before the failure still ran");
        assertTrue(c.calls.contains("start:op"), "the child after the failure still ran");
    }

    // --- recording test fixture ----------------------------------------------------------

    /** Captures every call in {@link #calls} for assertion. Returns a fresh handle each {@code start}. */
    private static final class Recorder implements ObservabilityPlugin {

        final List<String> calls = new ArrayList<>();
        Map<String, String> headers = Map.of();

        @Override
        public ObservationHandle start(String operationName) {
            calls.add("start:" + operationName);
            return new RecordingHandle(this, false);
        }
    }

    private static final class RecordingHandle implements ObservationHandle {

        private final Recorder owner;
        private final boolean isChild;
        private final AtomicBoolean closed = new AtomicBoolean();

        RecordingHandle(Recorder owner, boolean isChild) {
            this.owner = owner;
            this.isChild = isChild;
        }

        private String tag(String op) {
            return isChild ? op + "(child)" : op;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            owner.calls.add(tag("attribute") + ":" + key + "=" + value);
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            owner.calls.add(tag("event") + ":" + name);
            return this;
        }

        @Override
        public ObservationHandle error(Throwable error) {
            owner.calls.add(tag("error") + ":" + error.getMessage());
            return this;
        }

        @Override
        public ObservationHandle child(String operationName) {
            owner.calls.add("child:" + operationName);
            return new RecordingHandle(owner, true);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            return owner.headers;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.calls.add(tag("close"));
            }
        }
    }
}
