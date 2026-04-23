package qa.fanar.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JitterStrategyTest {

    @ParameterizedTest
    @EnumSource(JitterStrategy.class)
    void valueOfRoundtrips(JitterStrategy strategy) {
        assertEquals(strategy, JitterStrategy.valueOf(strategy.name()));
    }

    @Test
    void threeStrategiesExist() {
        assertEquals(3, JitterStrategy.values().length);
    }

    @Test
    void valueOfThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> JitterStrategy.valueOf("UNKNOWN"));
    }
}
