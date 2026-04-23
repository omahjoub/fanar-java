package qa.fanar.core;

/**
 * Common constants for the Fanar Java SDK.
 *
 * <p>More public types join this package as the API is implemented (see ADR-011 for package
 * conventions). For the current skeleton this class also exists to give the exported
 * {@code qa.fanar.core} package at least one compiled type, which the module system requires.</p>
 */
public final class Fanar {

    /** SDK version. Kept in sync with the Maven {@code ${project.version}} at release time. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Fanar() {
        // not instantiable
    }
}
