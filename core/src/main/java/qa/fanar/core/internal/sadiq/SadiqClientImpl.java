package qa.fanar.core.internal.sadiq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.internal.dispatch.Dispatcher;
import qa.fanar.core.internal.sse.SseStreamPublisher;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.StreamFlag;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.sadiq.SadiqClient;
import qa.fanar.core.sadiq.SadiqValidationRequest;
import qa.fanar.core.sadiq.SadiqValidationResponse;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Production implementation of {@link SadiqClient}.
 *
 * <p>Same plumbing as the other domain clients: retry &rarr; bearer-token &rarr; user interceptors
 * &rarr; transport. Validation POSTs the {@link SadiqValidationRequest} to
 * {@code /v1/sadiq/validate} and decodes the {@link SadiqValidationResponse}; 4xx/5xx become typed
 * exceptions inside the chain (retry interceptor) before reaching this class.</p>
 *
 * <p>Deep research POSTs the {@link DeepResearchRequest} to {@code /v1/sadiq/deep-research} with
 * {@code "stream":true} spliced in and reads the reply as an SSE stream — always, even for the
 * blocking variant, which collects the {@link ReportChunk} from the same stream and returns its
 * report once one has arrived, whatever the stream does afterwards: the server
 * admits the run at once and sends its headers, so the request timeout bounds the admission and
 * never the minutes-long run, and one wire path serves all three variants. Its chain is built
 * with {@link RetryPolicy#disabled()} whatever the client's policy, because the server consumes a
 * unit of the endpoint's daily quota when it admits a request, so a retried run is a run paid for
 * twice (ADR-031). The retry interceptor still sits in the chain as the error boundary.</p>
 *
 * <p>Internal (ADR-018). May be replaced, renamed, or deleted in any release.</p>
 *
 * <p>Request plumbing — chain assembly (retry &rarr; bearer token &rarr; user interceptors &rarr;
 * transport), the {@code http.method} / {@code http.url} / {@code fanar.model} attributes and the
 * trip to the transport — lives in {@link Dispatcher}; this class owns the endpoints, the wire
 * format and the decoding.</p>
 *
 * @author Oussama Mahjoub
 */
public final class SadiqClientImpl implements SadiqClient {

    private static final String VALIDATE_ENDPOINT = "/v1/sadiq/validate";
    private static final String RESEARCH_ENDPOINT = "/v1/sadiq/deep-research";
    // Observation names follow fanar.<domain>.<operation>; a streaming variant appends .stream so
    // its metrics separate from the one-shot call's (ADR-013).
    private static final String OP_VALIDATE = "fanar.sadiq.validate";
    private static final String OP_RESEARCH = "fanar.sadiq.deep_research";
    private static final String OP_RESEARCH_STREAM = "fanar.sadiq.deep_research.stream";

    private final URI validateEndpoint;
    private final URI researchEndpoint;
    private final FanarJsonCodec jsonCodec;
    private final Dispatcher dispatcher;
    private final Dispatcher researchDispatcher;
    private final ObservabilityPlugin observability;
    private final Map<String, String> defaultHeaders;
    private final String userAgent;

    public SadiqClientImpl(
            URI baseUrl,
            FanarJsonCodec jsonCodec,
            Supplier<String> apiKeySupplier,
            List<Interceptor> userInterceptors,
            HttpTransport transport,
            ObservabilityPlugin observability,
            RetryPolicy retryPolicy,
            Map<String, String> defaultHeaders,
            String userAgent) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.validateEndpoint = baseUrl.resolve(VALIDATE_ENDPOINT);
        this.researchEndpoint = baseUrl.resolve(RESEARCH_ENDPOINT);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        // Retry → bearer token → user interceptors → transport: assembled by the Dispatcher.
        this.dispatcher = new Dispatcher(transport, retryPolicy, apiKeySupplier, userInterceptors);
        // Deep research: the same chain with retries off — quota is consumed on admission.
        this.researchDispatcher = new Dispatcher(transport, RetryPolicy.disabled(), apiKeySupplier, userInterceptors);
        this.observability = Objects.requireNonNull(observability, "observability");
        this.defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
        this.userAgent = userAgent;
    }

    // --- validation --------------------------------------------------------------------------

    @Override
    public SadiqValidationResponse validate(SadiqValidationRequest request) {
        Objects.requireNonNull(request, "request");
        try (ObservationHandle obs = observability.start(OP_VALIDATE)) {
            try {
                HttpResponse<InputStream> response = dispatcher.dispatch(
                        buildHttpRequest(validateEndpoint, encodeBody(request), "application/json", obs),
                        obs, request.model().wireValue());
                return decodeResponse(response);
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<SadiqValidationResponse> validateAsync(SadiqValidationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<SadiqValidationResponse> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-sadiq-async-", 0).start(() -> {
            try {
                future.complete(validate(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    // --- deep research -----------------------------------------------------------------------

    @Override
    public DeepResearchReport deepResearch(DeepResearchRequest request) {
        Objects.requireNonNull(request, "request");
        ReportCollector collector = new ReportCollector();
        openStream(request, OP_RESEARCH).subscribe(collector);
        return collector.await();
    }

    @Override
    public CompletableFuture<DeepResearchReport> deepResearchAsync(DeepResearchRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<DeepResearchReport> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("fanar-sadiq-async-", 0).start(() -> {
            try {
                future.complete(deepResearch(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        // Cancelling the future interrupts the worker; the interrupted collector cancels its
        // subscription, which closes the response body and so the connection (ADR-031).
        future.whenComplete((report, failure) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        return future;
    }

    @Override
    public Flow.Publisher<DeepResearchEvent> deepResearchStream(DeepResearchRequest request) {
        Objects.requireNonNull(request, "request");
        return openStream(request, OP_RESEARCH_STREAM);
    }

    private SseStreamPublisher<DeepResearchEvent> openStream(DeepResearchRequest request, String operation) {
        // Not try-with-resources: the observation spans the whole stream, not just the handshake.
        // On success the publisher takes ownership of the handle and closes it on the terminal
        // signal, after recording the stream attributes (ADR-013).
        long startNanos = System.nanoTime();
        ObservationHandle obs = observability.start(operation);
        try {
            HttpResponse<InputStream> response = researchDispatcher.dispatch(
                    buildHttpRequest(researchEndpoint, StreamFlag.inject(encodeBody(request)), "text/event-stream", obs),
                    obs, request.model().wireValue());
            return SseStreamPublisher.forDeepResearch(response.body(), jsonCodec, obs, startNanos);
        } catch (RuntimeException e) {
            // The handshake failed, so no publisher exists to hand ownership to.
            obs.error(e);
            obs.close();
            throw e;
        }
    }

    /**
     * The blocking variant's subscriber: keeps the report, remembers an in-band error, and
     * turns the terminal signal into a return value or an exception on the waiting thread.
     */
    private static final class ReportCollector implements Flow.Subscriber<DeepResearchEvent> {

        private final CountDownLatch terminated = new CountDownLatch(1);
        private volatile Flow.Subscription subscription;
        private volatile DeepResearchReport report;
        private volatile ErrorChunk error;
        private volatile Throwable failure;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(DeepResearchEvent event) {
            if (event instanceof ReportChunk chunk) {
                report = chunk.report();
            } else if (event instanceof ErrorChunk chunk) {
                error = chunk;
            }
            // Progress, token and done events are the stream variant's to show; here they are
            // the run happening.
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
            terminated.countDown();
        }

        @Override
        public void onComplete() {
            terminated.countDown();
        }

        DeepResearchReport await() {
            try {
                terminated.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // onSubscribe ran synchronously inside subscribe(), so the subscription is set.
                subscription.cancel();
                throw new FanarTransportException("Deep research interrupted before the report arrived", e);
            }
            // A received report is the result, whatever came before or after it: the report chunk
            // is what the spec says to render, and a failure of the stream around it — a dropped
            // connection, a bad terminal frame, an error event — surfaces only when no report
            // arrived (ADR-031). The publisher records a transport failure on the observation; an
            // error event after the report is not recorded anywhere in this variant.
            if (report != null) {
                return report;
            }
            if (failure != null) {
                throw asUnchecked(failure);
            }
            if (error != null) {
                throw new FanarTransportException("Deep research stream reported an error: " + describe(error));
            }
            throw new FanarTransportException("Deep research stream ended without a report");
        }

        private static RuntimeException asUnchecked(Throwable t) {
            if (t instanceof RuntimeException runtime) {
                return runtime;
            }
            if (t instanceof Error error) {
                throw error;
            }
            return new FanarTransportException("Deep research stream failed: " + t.getMessage(), t);
        }

        private static String describe(ErrorChunk chunk) {
            return chunk.choices().isEmpty() ? "(no detail)" : chunk.choices().getFirst().content();
        }
    }

    // --- plumbing ----------------------------------------------------------------------------

    private HttpRequest buildHttpRequest(URI endpoint, byte[] body, String accept, ObservationHandle obs) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb.build();
    }

    private byte[] encodeBody(Object request) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            jsonCodec.encode(buf, request);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to encode " + request.getClass().getSimpleName(), e);
        }
        return buf.toByteArray();
    }

    private SadiqValidationResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, SadiqValidationResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode SadiqValidationResponse", e);
        }
    }
}
