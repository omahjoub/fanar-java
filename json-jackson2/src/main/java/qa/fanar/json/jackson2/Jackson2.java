package qa.fanar.json.jackson2;

/**
 * Marker class for the Fanar Jackson 2 adapter module.
 *
 * <p>Will be joined by {@code Jackson2FanarJsonCodec} and supporting types when the adapter is
 * implemented. For the current skeleton it exists so the module's exported package contains at
 * least one compiled type (required by JPMS).</p>
 */
public final class Jackson2 {
    private Jackson2() {
        // not instantiable
    }
}
