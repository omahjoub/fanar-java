package qa.fanar.adk;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoizedSupplierTest {

    @Test
    void buildsOnceAndReusesTheClient() {
        AtomicInteger builds = new AtomicInteger();
        try (FanarClient client = FanarClient.builder().apiKey("k").build()) {
            MemoizedSupplier supplier = new MemoizedSupplier(() -> {
                builds.incrementAndGet();
                return client;
            });

            assertEquals(0, builds.get(), "nothing is built before the first request");
            assertSame(client, supplier.get());
            assertSame(client, supplier.get());
            assertEquals(1, builds.get());
        }
    }

    @Test
    void rejectsAMissingSupplierAndANullClient() {
        assertThrows(NullPointerException.class, () -> new MemoizedSupplier(null));
        MemoizedSupplier supplier = new MemoizedSupplier(() -> null);
        assertThrows(NullPointerException.class, supplier::get);
    }
}
