package qa.fanar.core.spi;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityPluginTest {

    @Test
    void noopFactoryReturnsSingleton() {
        ObservabilityPlugin a = ObservabilityPlugin.noop();
        ObservabilityPlugin b = ObservabilityPlugin.noop();
        assertSame(a, b);
    }

    @Test
    void noopStartReturnsNonNullHandle() {
        try (ObservationHandle handle = ObservabilityPlugin.noop().start("test.operation")) {
            assertNotNull(handle);
        }
    }

    @Test
    void noopHandleIsSingleton() {
        ObservationHandle h1 = ObservabilityPlugin.noop().start("a");
        ObservationHandle h2 = ObservabilityPlugin.noop().start("b");
        assertSame(h1, h2);
    }

    @Test
    void noopHandleFluentMethodsReturnSelf() {
        ObservationHandle handle = ObservabilityPlugin.noop().start("test.operation");
        assertSame(handle, handle.attribute("key", "value"));
        assertSame(handle, handle.attribute("null-value", null));
        assertSame(handle, handle.event("something"));
        assertSame(handle, handle.error(new RuntimeException("test")));
        assertSame(handle, handle.child("child.op"));
    }

    @Test
    void noopHandlePropagationHeadersIsEmpty() {
        ObservationHandle handle = ObservabilityPlugin.noop().start("test.operation");
        Map<String, String> headers = handle.propagationHeaders();
        assertNotNull(headers);
        assertTrue(headers.isEmpty());
    }

    @Test
    void noopHandleCloseIsIdempotent() {
        ObservationHandle handle = ObservabilityPlugin.noop().start("test.operation");
        handle.close();
        handle.close(); // second call must not throw
    }
}
