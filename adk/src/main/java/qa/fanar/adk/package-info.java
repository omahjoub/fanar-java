/**
 * Google ADK (Agent Development Kit) adapter for the Fanar Java SDK (ADR-030).
 *
 * <p>{@link qa.fanar.adk.FanarLlm} implements ADK's {@code BaseLlm} over a
 * {@link qa.fanar.core.FanarClient}, so ADK agents, the dev UI, sessions, callbacks and plugins
 * run on Fanar models. Transport, authentication, retry, interceptors and observability stay
 * configured on {@code FanarClient.builder()}; the adapter adds none of its own.</p>
 *
 * <h2>Wiring</h2>
 * <pre>{@code
 * // Build the client lazily: ADK's dev server reads ROOT_AGENT reflectively and drops the agent
 * // with an unnamed LinkageError if the static initialiser throws.
 * FanarLlm model = new FanarLlm(() -> FanarClient.builder().build(), ChatModel.FANAR);
 * LlmAgent agent = LlmAgent.builder().name("fanar").model(model).instruction("...").build();
 *
 * // Or resolve by name through ADK's registry, opt-in:
 * FanarLlm.register(() -> FanarClient.builder().build(), FanarLlmOptions.defaults());
 * LlmAgent byName = LlmAgent.builder().name("fanar").model("fanar/Fanar-C-2-27B").build();
 * }</pre>
 *
 * <h2>Features Fanar cannot honour</h2>
 * <p>Fanar's chat endpoint accepts tool declarations and silently ignores them, and has no
 * structured-output parameter. Under the default {@link qa.fanar.adk.UnsupportedFeaturePolicy#REJECT}
 * a request carrying function declarations (declared by the agent or injected by ADK), an output
 * schema, or parts with no Fanar mapping fails before anything goes on the wire with an
 * {@link qa.fanar.adk.UnsupportedFeatureException} naming the items.
 * {@link qa.fanar.adk.UnsupportedFeaturePolicy#IGNORE} drops them and sends the rest.</p>
 *
 * <h2>Multi-agent recipes that need no opt-in</h2>
 * <ul>
 *   <li><b>Workflow step.</b> Under a {@code SequentialAgent}, {@code ParallelAgent} or
 *       {@code LoopAgent} parent ADK injects no transfer tool, so a Fanar agent runs as a step
 *       unchanged.</li>
 *   <li><b>Leaf specialist.</b> Under an LLM-driven root, build the Fanar agent with
 *       {@code disallowTransferToParent(true)} and {@code disallowTransferToPeers(true)} and no
 *       sub-agents: ADK injects no transfer tool, and after the leaf answers, the runner routes the
 *       next user turn back to the root.</li>
 * </ul>
 *
 * <h2>Request configuration</h2>
 * <p>{@code GenerateContentConfig} fields with a {@code ChatRequest} counterpart are forwarded:
 * temperature, top-p, top-k, max output tokens, candidate count, stop sequences, presence and
 * frequency penalties, and log-probabilities. Not forwarded, because Fanar has no counterpart:
 * seed, safety settings, thinking, speech, response modalities, cached content, labels, media
 * resolution, routing and model-selection configuration, HTTP options and automatic function
 * calling. Fanar-only knobs are set once per model instance through
 * {@link qa.fanar.adk.FanarLlmOptions}.</p>
 */
package qa.fanar.adk;
