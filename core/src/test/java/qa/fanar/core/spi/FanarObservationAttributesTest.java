package qa.fanar.core.spi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FanarObservationAttributesTest {

    @Test
    void constantValuesFollowOpenTelemetrySemanticConventions() {
        assertEquals("http.method", FanarObservationAttributes.HTTP_METHOD);
        assertEquals("http.url", FanarObservationAttributes.HTTP_URL);
        assertEquals("http.status_code", FanarObservationAttributes.HTTP_STATUS_CODE);
        assertEquals("fanar.model", FanarObservationAttributes.FANAR_MODEL);
        assertEquals("fanar.retry_count", FanarObservationAttributes.FANAR_RETRY_COUNT);
        assertEquals("fanar.stream.chunks", FanarObservationAttributes.FANAR_STREAM_CHUNKS);
        assertEquals("fanar.stream.first_chunk_ms", FanarObservationAttributes.FANAR_STREAM_FIRST_CHUNK_MS);
    }

    /**
     * Constants classes are final and not instantiable. This test verifies the invariant and
     * simultaneously invokes the private constructor via reflection, which is the standard
     * pattern for reaching 100% coverage on utility classes.
     */
    @Test
    void classIsFinalAndNotInstantiable() throws Exception {
        assertTrue(Modifier.isFinal(FanarObservationAttributes.class.getModifiers()),
                "FanarObservationAttributes should be final");

        Constructor<?>[] ctors = FanarObservationAttributes.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "exactly one (private) constructor expected");
        assertTrue(Modifier.isPrivate(ctors[0].getModifiers()),
                "the sole constructor should be private");

        ctors[0].setAccessible(true);
        assertNotNull(ctors[0].newInstance());
    }
}
