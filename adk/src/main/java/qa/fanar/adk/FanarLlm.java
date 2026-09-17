package qa.fanar.adk;

import java.util.Objects;
import java.util.function.Supplier;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.FlowAdapters;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;

/**
 * Google ADK {@link BaseLlm} over a Fanar chat model (ADR-030).
 *
 * <p>Transport, authentication, retry, interceptors and observability are whatever the
 * {@link FanarClient} was built with; the adapter adds none. Nothing runs before ADK subscribes:
 * the request is mapped, the client resolved and the call made on ADK's subscribing thread, inside
 * ADK's {@code call_llm} span, so the client's own spans nest under it. Fanar errors propagate
 * unwrapped as {@code Flowable} errors; a request carrying features Fanar cannot honour surfaces
 * as {@link UnsupportedFeatureException} under the default policy. Instances hold no per-request
 * state and may be shared by any number of agents.</p>
 */
public final class FanarLlm extends BaseLlm {

    /** The registry pattern {@link #register} claims; the model id follows the prefix. */
    public static final String REGISTRY_PATTERN = "fanar/.*";

    /** What a registry name starts with; the Fanar model id follows it. */
    public static final String REGISTRY_PREFIX = "fanar/";

    private final Supplier<FanarClient> client;
    private final ChatModel chatModel;
    private final FanarLlmOptions options;

    /** A model over an already-built client, with {@link FanarLlmOptions#defaults()}. */
    public FanarLlm(FanarClient client, ChatModel model) {
        this(client, model, FanarLlmOptions.defaults());
    }

    /** A model over an already-built client. */
    public FanarLlm(FanarClient client, ChatModel model, FanarLlmOptions options) {
        this(constant(client), model, options);
    }

    /** A model whose client is built on the first request, with {@link FanarLlmOptions#defaults()}. */
    public FanarLlm(Supplier<FanarClient> client, ChatModel model) {
        this(client, model, FanarLlmOptions.defaults());
    }

    /**
     * A model whose client is built on the first request. Prefer this from an agent's static
     * initialiser: ADK's dev server reads {@code ROOT_AGENT} reflectively and drops an agent whose
     * initialiser throws, logging only the {@code null} message of the resulting
     * {@code LinkageError}; built lazily, a missing key or codec surfaces as a model error with
     * its message intact.
     *
     * <p>A client built this way is never closed by the adapter: ADK's {@code BaseLlm} has no
     * close hook. Build the client outside and pass it in when its lifecycle matters.</p>
     *
     * @param client  builds the client once; the result is reused for every request
     * @param model   the Fanar chat model
     * @param options Fanar-only knobs and the unsupported-feature policy
     */
    public FanarLlm(Supplier<FanarClient> client, ChatModel model, FanarLlmOptions options) {
        super(Objects.requireNonNull(model, "model").wireValue());
        this.client = new MemoizedSupplier(client);
        this.chatModel = model;
        this.options = Objects.requireNonNull(options, "options");
    }

    private static Supplier<FanarClient> constant(FanarClient client) {
        Objects.requireNonNull(client, "client");
        return () -> client;
    }

    /**
     * Register Fanar models with ADK's {@code LlmRegistry} under {@link #REGISTRY_PATTERN}, so an
     * agent can name its model as a string: {@code "fanar/Fanar-C-2-27B"} resolves to a
     * {@code FanarLlm} over {@code ChatModel.of("Fanar-C-2-27B")}. Opt-in and explicit — loading
     * this class registers nothing. ADK caches one instance per model name for the life of the
     * JVM, shared by every agent using that name; all of them share the client the supplier builds.
     * A later call replaces the factory for names not yet resolved; names already resolved keep
     * their instance, client and options.
     *
     * @param client  builds the shared client once, on the first request
     * @param options applied to every model resolved through the registry
     */
    public static void register(Supplier<FanarClient> client, FanarLlmOptions options) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(options, "options");
        Supplier<FanarClient> shared = new MemoizedSupplier(client);
        LlmRegistry.registerLlm(REGISTRY_PATTERN, name ->
                new FanarLlm(shared, ChatModel.of(name.substring(REGISTRY_PREFIX.length())), options));
    }

    /** {@link #register(Supplier, FanarLlmOptions)} with {@link FanarLlmOptions#defaults()}. */
    public static void register(Supplier<FanarClient> client) {
        register(client, FanarLlmOptions.defaults());
    }

    /** {@link #register(Supplier, FanarLlmOptions)} over an already-built client. */
    public static void register(FanarClient client, FanarLlmOptions options) {
        Objects.requireNonNull(client, "client");
        register(() -> client, options);
    }

    /** {@link #register(Supplier, FanarLlmOptions)} over an already-built client and default options. */
    public static void register(FanarClient client) {
        register(client, FanarLlmOptions.defaults());
    }

    /** The Fanar chat model; {@link #model()} is its wire id. */
    public ChatModel chatModel() {
        return chatModel;
    }

    /** The Fanar-only knobs and the unsupported-feature policy this instance applies. */
    public FanarLlmOptions options() {
        return options;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        Objects.requireNonNull(llmRequest, "llmRequest");
        return Flowable.defer(() -> {
            ChatRequest request = RequestMapper.toChatRequest(llmRequest, chatModel, options);
            ChatClient chat = client.get().chat();
            if (!stream) {
                return Flowable.fromCallable(() -> ResponseMapper.toLlmResponse(chat.send(request)));
            }
            StreamAggregator aggregator = new StreamAggregator(chatModel.wireValue());
            return Flowable.fromPublisher(FlowAdapters.toPublisher(chat.stream(request)))
                    .concatMap(aggregator::onEvent)
                    .concatWith(Flowable.defer(aggregator::onComplete));
        });
    }

    /** Fanar has no bidirectional session API. */
    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Live (bidirectional) connections are not supported for Fanar models");
    }
}
