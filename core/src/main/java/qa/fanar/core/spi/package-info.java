/**
 * Extension interfaces for the Fanar Java core SDK.
 *
 * <p>Downstream modules and user code implement these interfaces to swap the JSON codec, register
 * cross-cutting interceptors (auth, retry, logging, caching), and bridge the SDK's observations to
 * metrics / tracing backends. Every SPI is framework-agnostic: method signatures use only JDK
 * types and Fanar's own DTOs, never third-party types.</p>
 *
 * <p>Stability: these interfaces are part of the public API surface. Changes follow semver per
 * ADR-018 and JLBP-10. Implementations may live in downstream modules (Jackson adapters, future
 * framework starters, user code) or in {@code qa.fanar.core.internal.*} for the SDK's own
 * built-in implementations.</p>
 */
package qa.fanar.core.spi;
