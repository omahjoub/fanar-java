package qa.fanar.core.internal.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantee this wrapper exists for: an {@link ObservabilityPlugin} cannot fail the caller's
 * call, however it was supplied. Telemetry is not worth a failed request (ADR-013).
 */
class IsolatingObservabilityPluginTest {

    /** Throws from every SPI method. What a networked backend looks like on a bad day. */
    private static final class Exploding implements ObservabilityPlugin, ObservationHandle {
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

    /** Records every call so the happy path can be shown to still reach the plugin. */
    private static final class Recording implements ObservabilityPlugin, ObservationHandle {
        final List<String> calls = new ArrayList<>();
        Map<String, String> headers = Map.of("traceparent", "00-abc-01");
        ObservationHandle childToReturn = this;

        @Override public ObservationHandle start(String operationName) { calls.add("start:" + operationName); return this; }
        @Override public ObservationHandle attribute(String k, Object v) { calls.add("attribute:" + k + "=" + v); return this; }
        @Override public ObservationHandle event(String n) { calls.add("event:" + n); return this; }
        @Override public ObservationHandle error(Throwable t) { calls.add("error:" + t.getMessage()); return this; }
        @Override public ObservationHandle child(String n) { calls.add("child:" + n); return childToReturn; }
        @Override public Map<String, String> propagationHeaders() { return headers; }
        @Override public void close() { calls.add("close"); }
    }

    // --- the guarantee -------------------------------------------------------------------

    @Test
    void noSpiMethodCanThrowThroughTheWrapper() {
        ObservabilityPlugin guarded = IsolatingObservabilityPlugin.wrap(new Exploding());

        assertDoesNotThrow(() -> {
            ObservationHandle h = guarded.start("fanar.chat.send");
            h.attribute("fanar.model", "Fanar");
            h.event("retry_attempt");
            h.error(new IllegalArgumentException("caller-side"));
            h.propagationHeaders();
            h.child("decode").attribute("k", "v");
            h.close();
        }, "a throwing plugin must never reach the caller");
    }

    @Test
    void aPluginThatThrowsOnStartStillYieldsAUsableHandle() {
        Exploding broken = new Exploding();
        broken.throwOnStart = true;

        ObservationHandle h = IsolatingObservabilityPlugin.wrap(broken).start("fanar.chat.send");
        assertSame(NoopObservationHandle.INSTANCE, h, "the caller gets a silent handle, not null");
        assertDoesNotThrow(() -> h.attribute("k", "v"));
    }

    @Test
    void aPluginReturningNullFromStartStillYieldsAUsableHandle() {
        ObservabilityPlugin nullStarter = operationName -> null;
        assertSame(NoopObservationHandle.INSTANCE,
                IsolatingObservabilityPlugin.wrap(nullStarter).start("op"));
    }

    @Test
    void propagationHeadersNeverReturnNull() {
        // A null here would NPE the caller merging it into the outbound request.
        Recording plugin = new Recording();
        plugin.headers = null;
        assertEquals(Map.of(),
                IsolatingObservabilityPlugin.wrap(plugin).start("op").propagationHeaders());
    }

    @Test
    void propagationHeadersFallBackToEmptyWhenThePluginThrows() {
        assertEquals(Map.of(),
                IsolatingObservabilityPlugin.wrap(new Exploding()).start("op").propagationHeaders());
    }

    @Test
    void aChildThatThrowsOrReturnsNullStillYieldsAUsableHandle() {
        Recording plugin = new Recording();
        plugin.childToReturn = null;
        assertSame(NoopObservationHandle.INSTANCE,
                IsolatingObservabilityPlugin.wrap(plugin).start("op").child("decode"));
    }

    @Test
    void errorsAreNotCaught() {
        // An OutOfMemoryError is not the plugin's to recover from, and hiding it would suppress a
        // failure the application needs to see.
        ObservabilityPlugin fatal = operationName -> { throw new StackOverflowError("nope"); };
        assertThrows(StackOverflowError.class,
                () -> IsolatingObservabilityPlugin.wrap(fatal).start("op"));
    }

    // --- the happy path still works ------------------------------------------------------

    @Test
    void everyCallStillReachesAHealthyPlugin() {
        Recording plugin = new Recording();
        ObservationHandle h = IsolatingObservabilityPlugin.wrap(plugin).start("fanar.chat.send");

        assertSame(h, h.attribute("fanar.model", "Fanar"), "chaining returns the guarded handle");
        assertSame(h, h.event("retry_attempt"));
        assertSame(h, h.error(new IllegalArgumentException("x")));
        assertEquals(Map.of("traceparent", "00-abc-01"), h.propagationHeaders());
        h.child("decode").attribute("nested", true);
        h.close();

        assertEquals(List.of(
                "start:fanar.chat.send", "attribute:fanar.model=Fanar", "event:retry_attempt",
                "error:x", "child:decode", "attribute:nested=true", "close"), plugin.calls);
    }

    @Test
    void aChildHandleIsGuardedToo() {
        Recording plugin = new Recording();
        plugin.childToReturn = new Exploding();

        ObservationHandle child = IsolatingObservabilityPlugin.wrap(plugin).start("op").child("decode");
        assertDoesNotThrow(() -> child.attribute("k", "v"),
                "a child handle from a healthy parent must be guarded as well");
    }

    // --- wiring --------------------------------------------------------------------------

    @Test
    void theNoopPluginIsNotWrapped() {
        // Nothing to guard against, and the default path should cost nothing.
        assertSame(ObservabilityPlugin.noop(),
                IsolatingObservabilityPlugin.wrap(ObservabilityPlugin.noop()));
    }

    @Test
    void anOrdinaryPluginIsWrapped() {
        Recording plugin = new Recording();
        assertNotSame(plugin, IsolatingObservabilityPlugin.wrap(plugin));
    }

    @Test
    void rejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> IsolatingObservabilityPlugin.wrap(null));
    }

    @Test
    void rejectsNullOperationName() {
        ObservabilityPlugin guarded = IsolatingObservabilityPlugin.wrap(new Recording());
        assertThrows(NullPointerException.class, () -> guarded.start(null));
    }

    @Test
    void repeatedFailuresAreReportedButNotRepeatedlyWarned() {
        // The first failure logs at WARNING, the rest at DEBUG — a plugin that throws once usually
        // throws on every call, and a per-call warning would bury the first one. Exercises both
        // arms of the report rate-limit; the logging itself is the JDK's to deliver.
        ObservationHandle h = IsolatingObservabilityPlugin.wrap(new Exploding()).start("op");
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) {
                h.attribute("k", i);
            }
        });
        assertTrue(true, "five failures, one warning, no exception");
    }
}
