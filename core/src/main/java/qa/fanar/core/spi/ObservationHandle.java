package qa.fanar.core.spi;

import java.util.Map;

/**
 * Lifecycle handle for a single observation begun by
 * {@link ObservabilityPlugin#start(String)}.
 *
 * <p>The handle is {@link AutoCloseable}; the SDK opens it inside a try-with-resources block and
 * closes it when the observed operation finishes, regardless of success or failure. Adapter
 * implementations map the handle's methods to their backend's idioms — for example an
 * OpenTelemetry adapter maps {@link #attribute}, {@link #event}, {@link #error}, and
 * {@link #close} to span operations; a Micrometer adapter maps them to
 * {@code Observation.Context} operations.</p>
 *
 * <p>Implementations must accept any call order and must make {@link #close()} idempotent — the
 * SDK may invoke it once, but application code using the handle directly may inadvertently close
 * it twice.</p>
 *
 * <p>Fluent methods ({@link #attribute}, {@link #event}, {@link #error}, {@link #child}) return
 * this handle (or, for {@link #child}, a new one) to allow chaining.</p>
 *
 * @author Oussama Mahjoub
 */
public interface ObservationHandle extends AutoCloseable {

    /**
     * Attach or update a key-value attribute on this observation. Canonical attribute names are
     * defined in {@link FanarObservationAttributes}. Backends may coerce {@code value} into
     * their own type system.
     *
     * @param key   attribute name; must not be {@code null}
     * @param value attribute value; may be {@code null} (the adapter decides how to represent it)
     * @return this handle
     */
    ObservationHandle attribute(String key, Object value);

    /**
     * Record a point-in-time event on this observation, for example {@code retry_attempt} or
     * {@code first_chunk}. The adapter maps this to span events, log records, or counters as
     * appropriate.
     *
     * @param name event name; must not be {@code null}
     * @return this handle
     */
    ObservationHandle event(String name);

    /**
     * Mark this observation as failed. Called by the SDK when an operation ends with an
     * exception, before {@link #close()}. Adapters typically record the exception on the
     * underlying span or error-count it.
     *
     * @param error the exception that terminated the operation; must not be {@code null}
     * @return this handle
     */
    ObservationHandle error(Throwable error);

    /**
     * Begin a nested observation representing a phase of the current operation (for example the
     * HTTP request inside a chat call, or serialization inside a transport send). Adapters map
     * child observations to child spans (OpenTelemetry) or nested observations (Micrometer).
     *
     * <p>The returned handle has the same contract as this one, including {@link #close()}
     * idempotency.</p>
     *
     * @param operationName the child operation's name; must not be {@code null}
     * @return a handle bound to the child observation; never {@code null}
     */
    ObservationHandle child(String operationName);

    /**
     * HTTP headers the SDK should merge into the outbound request for distributed-trace context
     * propagation — for example {@code traceparent} and {@code tracestate} (W3C Trace Context).
     *
     * <p>If the adapter does not propagate context, returns an empty map. Never {@code null}.
     * The returned map should be treated as immutable by callers.</p>
     *
     * @return headers to merge into outbound HTTP requests; possibly empty
     */
    Map<String, String> propagationHeaders();

    /**
     * End this observation. Must be idempotent — a second call does nothing.
     *
     * <p>Overrides {@link AutoCloseable#close()} to remove the checked-exception declaration;
     * observation plugins must not surface checked exceptions to the SDK.</p>
     */
    @Override
    void close();
}
