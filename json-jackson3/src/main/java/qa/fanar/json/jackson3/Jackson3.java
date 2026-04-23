package qa.fanar.json.jackson3;

/**
 * Marker class for the Fanar Jackson 3 adapter module.
 *
 * <p>Will be joined by {@code Jackson3FanarJsonCodec} and supporting types when the adapter is
 * implemented. For the current skeleton it exists so the module's exported package contains at
 * least one compiled type (required by JPMS).</p>
 */
public final class Jackson3 {
    private Jackson3() {
        // not instantiable
    }
}
