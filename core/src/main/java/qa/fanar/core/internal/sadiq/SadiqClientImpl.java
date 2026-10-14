package qa.fanar.core.internal.sadiq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.function.Supplier;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.internal.dispatch.Dispatcher;
import qa.fanar.core.internal.sse.SseStreamPublisher;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.internal.transport.StreamFlag;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchJob;
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
 * {@code "stream":true,"background":false} spliced in and reads the reply as an SSE stream — always,
 * even for the blocking variant, which collects the {@link ReportChunk} from the same stream and returns its
 * report once one has arrived, whatever the stream does afterwards: the server
 * admits the run at once and sends its headers, so the request timeout bounds the admission and
 * never the minutes-long run, and one wire path serves all three variants. Its chain is built
 * with {@link RetryPolicy#disabled()} whatever the client's policy, because the server consumes a
 * unit of the endpoint's daily quota when it admits a request, so a retried run is a run paid for
 * twice (ADR-031). The retry interceptor still sits in the chain as the error boundary.</p>
 *
 * <p>A background job (ADR-032) starts with the same POST and {@code "stream":false,"background":true}
 * on that never-retried chain, and returns the {@link DeepResearchJob}. Reading it
 * ({@code GET /v1/sadiq/deep-research/{id}}), following its events ({@code ?stream=true}, resumed
 * with {@code starting_after} across responses by {@link SseStreamPublisher#forDeepResearchJob}) and
 * cancelling it ({@code POST …/{id}/cancel}) spend no quota and run on the client's chain.</p>
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
    private static final String OP_START = "fanar.sadiq.deep_research.start";
    private static final String OP_RETRIEVE = "fanar.sadiq.deep_research.retrieve";
    private static final String OP_RETRIEVE_STREAM = "fanar.sadiq.deep_research.retrieve.stream";
    private static final String OP_CANCEL = "fanar.sadiq.deep_research.cancel";
    // The spec makes `stream`'s default depend on `background`, so every call pins both (ADR-032).
    private static final String SYNCHRONOUS_FLAGS = "\"stream\":true,\"background\":false";
    private static final String BACKGROUND_FLAGS = "\"stream\":false,\"background\":true";
    private static final String ACCEPT_JSON = "application/json";
    private static final String ACCEPT_SSE = "text/event-stream";

    private final URI baseUrl;
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
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.validateEndpoint = baseUrl.resolve(VALIDATE_ENDPOINT);
        this.researchEndpoint = baseUrl.resolve(RESEARCH_ENDPOINT);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        // Retry → bearer token → user interceptors → transport: assembled by the Dispatcher.
        this.dispatcher = new Dispatcher(transport, retryPolicy, apiKeySupplier, userInterceptors);
        // Deep research admissions: the same chain with retries off — each admitted run spends quota.
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
                        buildHttpRequest(validateEndpoint, encodeBody(request), ACCEPT_JSON, obs),
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
        return onVirtualThread(() -> validate(request));
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
                    buildHttpRequest(researchEndpoint, StreamFlag.inject(encodeBody(request), SYNCHRONOUS_FLAGS), ACCEPT_SSE, obs),
                    obs, request.model().wireValue());
            return SseStreamPublisher.forDeepResearch(response.body(), jsonCodec, obs, startNanos);
        } catch (RuntimeException e) {
            // The handshake failed, so no publisher exists to hand ownership to.
            obs.error(e);
            obs.close();
            throw e;
        }
    }

    // --- deep research background jobs (ADR-032) ---------------------------------------------

    @Override
    public DeepResearchJob startDeepResearch(DeepResearchRequest request) {
        Objects.requireNonNull(request, "request");
        // Never retried: a retried start would admit, and pay for, a second job.
        return jobCall(OP_START, researchDispatcher, request.model().wireValue(), obs -> buildHttpRequest(
                researchEndpoint, StreamFlag.inject(encodeBody(request), BACKGROUND_FLAGS), ACCEPT_JSON, obs));
    }

    @Override
    public CompletableFuture<DeepResearchJob> startDeepResearchAsync(DeepResearchRequest request) {
        Objects.requireNonNull(request, "request");
        return onVirtualThread(() -> startDeepResearch(request));
    }

    @Override
    public DeepResearchJob retrieveDeepResearchJob(String jobId) {
        requireJobId(jobId);
        return jobCall(OP_RETRIEVE, dispatcher, null,
                obs -> withCommonHeaders(HttpRequest.newBuilder(jobUri(jobId, "")).GET(), ACCEPT_JSON, obs));
    }

    @Override
    public CompletableFuture<DeepResearchJob> retrieveDeepResearchJobAsync(String jobId) {
        requireJobId(jobId);
        return onVirtualThread(() -> retrieveDeepResearchJob(jobId));
    }

    @Override
    public DeepResearchJob cancelDeepResearchJob(String jobId) {
        requireJobId(jobId);
        return jobCall(OP_CANCEL, dispatcher, null, obs -> withCommonHeaders(
                HttpRequest.newBuilder(jobUri(jobId, "/cancel")).POST(HttpRequest.BodyPublishers.noBody()),
                ACCEPT_JSON, obs));
    }

    @Override
    public CompletableFuture<DeepResearchJob> cancelDeepResearchJobAsync(String jobId) {
        requireJobId(jobId);
        return onVirtualThread(() -> cancelDeepResearchJob(jobId));
    }

    @Override
    public Flow.Publisher<DeepResearchEvent> deepResearchJobStream(String jobId) {
        requireJobId(jobId);
        // As openStream: the publisher owns the observation once the first response is in, and the
        // observation spans every response the stream resumes over (ADR-013, ADR-032).
        long startNanos = System.nanoTime();
        ObservationHandle obs = observability.start(OP_RETRIEVE_STREAM);
        try {
            InputStream first = openJobEvents(jobId, null, obs);
            return SseStreamPublisher.forDeepResearchJob(first,
                    lastEventId -> openJobEvents(jobId, lastEventId, obs), jsonCodec, obs, startNanos);
        } catch (RuntimeException e) {
            obs.error(e);
            obs.close();
            throw e;
        }
    }

    /** One response of a job's event stream: from the first event, or after {@code lastEventId}. */
    private InputStream openJobEvents(String jobId, String lastEventId, ObservationHandle obs) {
        String query = lastEventId == null
                ? "?stream=true"
                : "?stream=true&starting_after=" + URLEncoder.encode(lastEventId, StandardCharsets.UTF_8);
        HttpRequest request = withCommonHeaders(HttpRequest.newBuilder(jobUri(jobId, query)).GET(), ACCEPT_SSE, obs);
        return dispatcher.dispatch(request, obs, null).body();
    }

    /** A one-shot call that answers with a job: observed, dispatched and decoded. */
    private DeepResearchJob jobCall(String operation, Dispatcher via, String model,
                                    Function<ObservationHandle, HttpRequest> request) {
        try (ObservationHandle obs = observability.start(operation)) {
            try {
                return decodeJob(via.dispatch(request.apply(obs), obs, model));
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            }
        }
    }

    private URI jobUri(String jobId, String suffix) {
        return baseUrl.resolve(RESEARCH_ENDPOINT + "/" + URLEncoder.encode(jobId, StandardCharsets.UTF_8) + suffix);
    }

    private static void requireJobId(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
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
        return withCommonHeaders(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)), accept, obs);
    }

    /**
     * The headers every request carries. Never sets {@code HttpRequest.Builder.timeout}: from JDK 26
     * it would bound the whole response body, not the wait for its headers (ADR-007).
     */
    private HttpRequest withCommonHeaders(HttpRequest.Builder rb, String accept, ObservationHandle obs) {
        rb.header("Accept", accept);
        defaultHeaders.forEach(rb::header);
        obs.propagationHeaders().forEach(rb::header);
        if (userAgent != null) {
            rb.header("User-Agent", userAgent);
        }
        return rb.build();
    }

    private static <T> CompletableFuture<T> onVirtualThread(Supplier<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().name("fanar-sadiq-async-", 0).start(() -> {
            try {
                future.complete(call.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
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

    private DeepResearchJob decodeJob(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, DeepResearchJob.class);
        } catch (IOException e) {
            // Name the job: after a start, it exists and is paid for, and no endpoint lists jobs.
            String id = response.headers().firstValue("X-Id").map(x -> " (X-Id " + x + ")").orElse("");
            throw new FanarTransportException("Failed to decode DeepResearchJob" + id, e);
        }
    }

    private SadiqValidationResponse decodeResponse(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return jsonCodec.decode(in, SadiqValidationResponse.class);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to decode SadiqValidationResponse", e);
        }
    }
}
