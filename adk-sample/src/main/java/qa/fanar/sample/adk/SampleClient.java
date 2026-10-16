package qa.fanar.sample.adk;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import qa.fanar.core.FanarClient;
import qa.fanar.interceptor.logging.WireLoggingInterceptor;

/**
 * The one {@link FanarClient} the sample's agents share, built on the first request.
 *
 * <p>ADK's dev UI finds an agent by reading its class's {@code ROOT_AGENT} field, which runs that
 * class's static initialiser. A client built there would fail it whenever the API key or the JSON
 * codec is missing, and the loader would then drop the agent with an unnamed {@code LinkageError}.
 * Built lazily, the same failure surfaces as a model error on the first request (ADR-030).</p>
 *
 * <p>Every call is logged on stderr: one line per call at the default {@code BASIC} level. The system
 * property {@code fanar.wire-logging.level} switches it to {@code HEADERS}, {@code BODY} or
 * {@code NONE}.</p>
 */
public final class SampleClient {

    /** Built on the first request, never while ADK loads the agents. */
    public static final Supplier<FanarClient> CLIENT = memoize(SampleClient::build);

    private SampleClient() {
        // CLIENT only
    }

    private static FanarClient build() {
        WireLoggingInterceptor.Level level = WireLoggingInterceptor.Level.valueOf(
                System.getProperty("fanar.wire-logging.level", "BASIC").toUpperCase(Locale.ROOT));
        return FanarClient.builder()   // the key comes from FANAR_API_KEY
                .addInterceptor(WireLoggingInterceptor.builder()
                        .level(level)
                        .sink(System.err::println)
                        .build())
                .build();
    }

    private static Supplier<FanarClient> memoize(Supplier<FanarClient> delegate) {
        AtomicReference<FanarClient> holder = new AtomicReference<>();
        return () -> {
            FanarClient client = holder.get();
            if (client == null) {
                synchronized (holder) {
                    client = holder.get();
                    if (client == null) {
                        client = delegate.get();
                        holder.set(client);
                    }
                }
            }
            return client;
        };
    }
}
