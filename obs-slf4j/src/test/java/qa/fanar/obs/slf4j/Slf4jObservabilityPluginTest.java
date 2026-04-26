package qa.fanar.obs.slf4j;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Slf4jObservabilityPluginTest {

    private final List<LogCall> records = new ArrayList<>();
    private final AtomicBoolean debugEnabled = new AtomicBoolean(true);
    private final AtomicBoolean errorEnabled = new AtomicBoolean(true);

    private final Slf4jObservabilityPlugin plugin = new Slf4jObservabilityPlugin(this::recordingLogger);

    // --- start ----------------------------------------------------------------------------

    @Test
    void start_returnsNonNullHandle() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertNotNull(h);
        }
    }

    @Test
    void start_rejectsNullOperationName() {
        assertThrows(NullPointerException.class, () -> plugin.start(null));
    }

    @Test
    void start_resolvesLoggerByOperationName() {
        try (ObservationHandle ignored = plugin.start("fanar.chat.send")) {
            ignored.attribute("k", "v");
        }
        // every recorded call must carry the operation name as its logger
        assertTrue(records.stream().anyMatch(r -> "fanar.chat.send".equals(r.loggerName())),
                "logger name must be the operation name verbatim");
    }

    // --- close: success / failure / idempotency -------------------------------------------

    @Test
    void close_emitsDebugSummaryOnSuccess() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.attribute("http.status_code", 200);
        }
        LogCall debug = lastMethodCall("debug");
        assertNotNull(debug, "expected a debug() call on close");
        // The logger name carries the operation; the message format is "ok in {}ms attrs={}".
        assertEquals("fanar.chat.send", debug.loggerName());
        Object[] payload = payload(debug);
        assertTrue(((Number) payload[0]).longValue() >= 0L,
                "duration must be a non-negative long");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload[1];
        assertEquals(200, attrs.get("http.status_code"));
    }

    @Test
    void close_emitsErrorWithThrowableWhenErrorSet() {
        RuntimeException boom = new RuntimeException("boom");
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.attribute("fanar.model", "Fanar-S-1-7B");
            h.error(boom);
        }
        LogCall errorCall = lastMethodCall("error");
        assertNotNull(errorCall, "expected an error() call on close");
        assertEquals("fanar.chat.send", errorCall.loggerName());
        Object[] payload = payload(errorCall);
        assertSame(boom, payload[2],
                "the Throwable must be the trailing vararg so SLF4J prints the stack trace");
    }

    @Test
    void close_isIdempotent() {
        ObservationHandle h = plugin.start("fanar.chat.send");
        h.close();
        int after = records.size();
        h.close();
        assertEquals(after, records.size(), "second close must be a no-op");
    }

    @Test
    void close_skipsDebugLogWhenLevelDisabled() {
        debugEnabled.set(false);
        try (ObservationHandle ignored = plugin.start("fanar.chat.send")) {
            // nothing
        }
        assertFalse(records.stream().anyMatch(r -> "debug".equals(r.method())),
                "no debug() call must be made when debug is disabled");
    }

    @Test
    void close_skipsErrorLogWhenLevelDisabled() {
        errorEnabled.set(false);
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.error(new RuntimeException("boom"));
        }
        assertFalse(records.stream().anyMatch(r -> "error".equals(r.method())),
                "no error() call must be made when error is disabled");
    }

    // --- attribute ------------------------------------------------------------------------

    @Test
    void attribute_storesValueForCloseSummary() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.attribute("http.status_code", 200).attribute("fanar.model", "Fanar-S-1-7B");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(lastMethodCall("debug"))[1];
        assertEquals(200, attrs.get("http.status_code"));
        assertEquals("Fanar-S-1-7B", attrs.get("fanar.model"));
    }

    @Test
    void attribute_nullValueRemovesKey() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.attribute("http.status_code", 200);
            h.attribute("http.status_code", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(lastMethodCall("debug"))[1];
        assertFalse(attrs.containsKey("http.status_code"),
                "null value must remove the attribute key");
    }

    @Test
    void attribute_returnsThis() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertSame(h, h.attribute("k", "v"));
        }
    }

    @Test
    void attribute_rejectsNullKey() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertThrows(NullPointerException.class, () -> h.attribute(null, "v"));
        }
    }

    // --- event ----------------------------------------------------------------------------

    @Test
    void event_emitsDebugLogImmediately() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.event("retry_attempt");
        }
        // Two debug() calls expected: one for the event, one for close summary
        long debugCalls = records.stream().filter(r -> "debug".equals(r.method())).count();
        assertEquals(2, debugCalls, "expected event() + close() debug lines");
    }

    @Test
    void event_returnsThis() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertSame(h, h.event("retry_attempt"));
        }
    }

    @Test
    void event_rejectsNullName() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertThrows(NullPointerException.class, () -> h.event(null));
        }
    }

    @Test
    void event_skipsWhenDebugDisabled() {
        debugEnabled.set(false);
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            h.event("retry_attempt");
        }
        assertFalse(records.stream().anyMatch(r -> "debug".equals(r.method())));
    }

    // --- error ----------------------------------------------------------------------------

    @Test
    void error_returnsThis() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertSame(h, h.error(new RuntimeException("x")));
        }
    }

    @Test
    void error_rejectsNullError() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertThrows(NullPointerException.class, () -> h.error(null));
        }
    }

    // --- child ----------------------------------------------------------------------------

    @Test
    void child_returnsHandleWithDerivedLoggerName() {
        try (ObservationHandle parent = plugin.start("fanar.chat.send")) {
            try (ObservationHandle child = parent.child("decode")) {
                assertNotSame(parent, child);
                child.attribute("k", "v");
            }
        }
        assertTrue(records.stream().anyMatch(r -> "fanar.chat.send.decode".equals(r.loggerName())),
                "child logger name must concatenate parent + child operation names");
    }

    @Test
    void child_rejectsNullOperationName() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertThrows(NullPointerException.class, () -> h.child(null));
        }
    }

    // --- propagationHeaders ---------------------------------------------------------------

    @Test
    void propagationHeaders_returnsEmptyMap() {
        try (ObservationHandle h = plugin.start("fanar.chat.send")) {
            assertEquals(Map.of(), h.propagationHeaders());
        }
    }

    // --- constructors ---------------------------------------------------------------------

    @Test
    void publicConstructorWiresLoggerFactoryAndIsUsable() {
        Slf4jObservabilityPlugin p = new Slf4jObservabilityPlugin();
        // Exercising the SLF4J no-op binding (slf4j-nop test dep) — must not throw.
        assertDoesNotThrow(() -> {
            try (ObservationHandle h = p.start("fanar.chat.send")) {
                h.attribute("http.status_code", 200);
                h.event("retry_attempt");
            }
        });
    }

    @Test
    void packagePrivateConstructorRejectsNullFactory() {
        assertThrows(NullPointerException.class, () -> new Slf4jObservabilityPlugin(null));
    }

    @Test
    void packagePrivateConstructorRejectsNullFilter() {
        assertThrows(NullPointerException.class,
                () -> new Slf4jObservabilityPlugin(this::recordingLogger, null, (k, v) -> v));
    }

    @Test
    void packagePrivateConstructorRejectsNullRedactor() {
        assertThrows(NullPointerException.class,
                () -> new Slf4jObservabilityPlugin(this::recordingLogger, k -> true, null));
    }

    // --- builder customization knobs ------------------------------------------------------

    @Test
    void builder_buildsPluginWithDefaults() {
        // Smoke: default builder produces a usable plugin (uses the real LoggerFactory).
        Slf4jObservabilityPlugin p = Slf4jObservabilityPlugin.builder().build();
        assertDoesNotThrow(() -> {
            try (ObservationHandle h = p.start("fanar.chat.send")) {
                h.attribute("k", "v");
            }
        });
    }

    @Test
    void builder_attributeFilter_rejectsNullPredicate() {
        Slf4jObservabilityPlugin.Builder b = Slf4jObservabilityPlugin.builder();
        assertThrows(NullPointerException.class, () -> b.attributeFilter(null));
    }

    @Test
    void builder_attributeRedactor_rejectsNullFunction() {
        Slf4jObservabilityPlugin.Builder b = Slf4jObservabilityPlugin.builder();
        assertThrows(NullPointerException.class, () -> b.attributeRedactor(null));
    }

    @Test
    void builder_attributeFilter_returnsSameBuilder() {
        Slf4jObservabilityPlugin.Builder b = Slf4jObservabilityPlugin.builder();
        assertSame(b, b.attributeFilter(key -> true));
    }

    @Test
    void builder_attributeRedactor_returnsSameBuilder() {
        Slf4jObservabilityPlugin.Builder b = Slf4jObservabilityPlugin.builder();
        assertSame(b, b.attributeRedactor((k, v) -> v));
    }

    @Test
    void filter_dropsKeysReturningFalse() {
        Predicate<String> filter = key -> !key.startsWith("internal.");
        Slf4jObservabilityPlugin filtered = new Slf4jObservabilityPlugin(
                this::recordingLogger, filter, (k, v) -> v);

        try (ObservationHandle h = filtered.start("fanar.chat.send")) {
            h.attribute("http.status_code", 200);
            h.attribute("internal.thread_id", 42);
            h.attribute("fanar.model", "Fanar-S-1-7B");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(lastMethodCall("debug"))[1];
        assertEquals(200, attrs.get("http.status_code"));
        assertEquals("Fanar-S-1-7B", attrs.get("fanar.model"));
        assertFalse(attrs.containsKey("internal.thread_id"),
                "filter must drop keys whose predicate returns false");
    }

    @Test
    void redactor_replacesValueForMatchedKey() {
        BiFunction<String, Object, Object> redactor =
                (k, v) -> "fanar.user_id".equals(k) ? "***" : v;
        Slf4jObservabilityPlugin redacting = new Slf4jObservabilityPlugin(
                this::recordingLogger, key -> true, redactor);

        try (ObservationHandle h = redacting.start("fanar.chat.send")) {
            h.attribute("fanar.user_id", "u-1234567890");
            h.attribute("http.status_code", 200);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(lastMethodCall("debug"))[1];
        assertEquals("***", attrs.get("fanar.user_id"),
                "redactor must replace the value for the matched key");
        assertEquals(200, attrs.get("http.status_code"),
                "non-matched keys must pass through unchanged");
    }

    @Test
    void redactor_canReturnNullToLogLiteralNull() {
        BiFunction<String, Object, Object> redactor = (k, v) -> null;
        Slf4jObservabilityPlugin redacting = new Slf4jObservabilityPlugin(
                this::recordingLogger, key -> true, redactor);

        try (ObservationHandle h = redacting.start("fanar.chat.send")) {
            h.attribute("k", "v");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(lastMethodCall("debug"))[1];
        assertTrue(attrs.containsKey("k"), "key must still appear in the projected map");
        assertNull(attrs.get("k"), "redactor returning null must produce a literal null value");
    }

    @Test
    void child_inheritsFilterAndRedactor() {
        Slf4jObservabilityPlugin filtered = new Slf4jObservabilityPlugin(
                this::recordingLogger,
                key -> !"secret".equals(key),
                (k, v) -> "fanar.model".equals(k) ? "REDACTED" : v);

        try (ObservationHandle parent = filtered.start("fanar.chat.send")) {
            try (ObservationHandle child = parent.child("decode")) {
                child.attribute("secret", "hide-me");
                child.attribute("fanar.model", "Fanar-S-1-7B");
                child.attribute("http.status_code", 200);
            }
        }
        // Find the close debug call for the child logger
        LogCall childClose = lastDebugFor("fanar.chat.send.decode");
        assertNotNull(childClose, "expected a debug call on the child logger");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) payload(childClose)[1];
        assertFalse(attrs.containsKey("secret"), "child must inherit parent's filter");
        assertEquals("REDACTED", attrs.get("fanar.model"), "child must inherit parent's redactor");
        assertEquals(200, attrs.get("http.status_code"));
    }

    // --- helpers --------------------------------------------------------------------------

    /** A {@link Logger} test double built via dynamic proxy — records every method call. */
    private Logger recordingLogger(String name) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    Object[] capturedArgs = args == null ? new Object[0] : args;
                    records.add(new LogCall(name, method.getName(), capturedArgs));
                    return switch (method.getName()) {
                        case "isDebugEnabled" -> debugEnabled.get();
                        case "isErrorEnabled" -> errorEnabled.get();
                        case "isInfoEnabled", "isWarnEnabled", "isTraceEnabled" -> false;
                        case "getName" -> name;
                        default -> null;
                    };
                });
    }

    /** The most recent recorded call with the given method name, or {@code null} if none. */
    private LogCall lastMethodCall(String method) {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (method.equals(records.get(i).method())) {
                return records.get(i);
            }
        }
        return null;
    }

    /** The most recent {@code debug} call against the given logger name, or {@code null}. */
    private LogCall lastDebugFor(String loggerName) {
        for (int i = records.size() - 1; i >= 0; i--) {
            LogCall c = records.get(i);
            if ("debug".equals(c.method()) && loggerName.equals(c.loggerName())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Unpack the post-format args from a recorded logger call regardless of whether the
     * compiler picked the fixed-arity overload (e.g. {@code debug(String, Object, Object)}) or
     * the varargs overload (e.g. {@code error(String, Object...)}). Returns just the
     * user-supplied args after the format string.
     */
    private static Object[] payload(LogCall call) {
        Object[] args = call.args();
        if (args.length == 2 && args[1] instanceof Object[] varargs) {
            return varargs;
        }
        Object[] out = new Object[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    record LogCall(String loggerName, String method, Object[] args) { }

    /** Compile-time check that {@link Logger#getName()} on a recording proxy returns the supplied name. */
    @Test
    void recordingProxy_getNameReturnsLoggerName() {
        Function<String, Logger> factory = this::recordingLogger;
        assertEquals("fanar.chat.send", factory.apply("fanar.chat.send").getName());
    }
}
