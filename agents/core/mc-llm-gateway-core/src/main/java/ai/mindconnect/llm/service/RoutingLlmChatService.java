package ai.mindconnect.llm.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.LlmTransientException;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Routes a chat request at the config it names: resolves the name (following
 * aliases), asks the registry for the provider's gateway, and streams through
 * it.
 *
 * <p><b>Rate-limit fallbacks.</b> When the resolved config names
 * {@link LlmConfig#fallbackModels() fallback models} and the provider answers
 * with a transient error — HTTP 429 (rate limit) or 529 (overloaded), after the
 * config's own retry policy is exhausted — the request is re-routed at the next
 * named config, in order, until one answers or the list runs out. A fallback
 * typically lives at a different provider, whose limit is a different limit.
 *
 * <p>Switching model mid-answer would duplicate what the caller already saw, so
 * a fallback is only taken while nothing has streamed: the first chunk that
 * reaches the handler commits the call to its config, and a later transient
 * error propagates. Gateways report 429/529 before any content, so this is the
 * normal case, not an exception to it.
 */
public class RoutingLlmChatService implements LlmChat {

    private static final Logger log = LoggerFactory.getLogger(RoutingLlmChatService.class);

    private final LlmConfigRepository configRepository;
    private final LlmGatewayRegistry gatewayRegistry;

    public RoutingLlmChatService(LlmConfigRepository configRepository, LlmGatewayRegistry gatewayRegistry) {
        this.configRepository = configRepository;
        this.gatewayRegistry = gatewayRegistry;
    }

    @Override
    public void chatStreaming(LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        LlmConfig config = resolveConfig(request);
        List<LlmConfig> chain = fallbackChain(config);
        if (chain.size() == 1) {
            // The common case: no fallbacks — route straight through, nothing to guard.
            gatewayRegistry.gatewayFor(config).chatStreaming(config, request, handler, cancellation, listener);
            return;
        }

        for (int i = 0; i < chain.size(); i++) {
            LlmConfig attempt = chain.get(i);
            // Latch: flips the first time a chunk reaches the caller. From then
            // on the answer is this config's, and no fallback may replace it.
            AtomicBoolean committed = new AtomicBoolean(false);
            Consumer<LlmStreamChunk> guarded = chunk -> {
                committed.set(true);
                handler.accept(chunk);
            };
            LlmRequest attemptRequest = i == 0 ? request : request.withConfigName(attempt.name());
            try {
                gatewayRegistry.gatewayFor(attempt)
                        .chatStreaming(attempt, attemptRequest, guarded, cancellation, listener);
                if (i > 0) {
                    log.info("LLM fallback succeeded: config '{}' (model {}) answered for rate-limited '{}'",
                            attempt.name(), attempt.model(), config.name());
                }
                return;
            } catch (LlmTransientException te) {
                boolean last = i == chain.size() - 1;
                if (committed.get() || cancellation.isCancelled() || last) {
                    if (last && !committed.get()) {
                        log.error("LLM rate limit HTTP {}: config '{}' and all {} fallback(s) {} are exhausted — "
                                        + "giving up",
                                te.status(), config.name(), chain.size() - 1, names(chain.subList(1, chain.size())));
                    }
                    throw te;
                }
                LlmConfig next = chain.get(i + 1);
                log.warn("LLM rate limit HTTP {} on config '{}' (model {}) — falling back to '{}' (model {}), "
                                + "fallback {} of {}",
                        te.status(), attempt.name(), attempt.model(), next.name(), next.model(),
                        i + 1, chain.size() - 1);
            }
        }
    }

    private LlmConfig resolveConfig(LlmRequest request) {
        // Aliases point at another config by name — findResolvedByName follows the chain.
        return configRepository.findResolvedByName(request.configName())
                .orElseThrow(() -> DomainException.notFound("LlmConfig", request.configName()));
    }

    /**
     * The configs to try, in order: the resolved primary followed by each of
     * its {@link LlmConfig#fallbackModels()} that resolves to a config of its
     * own. A name nothing carries is skipped with a warning rather than failing
     * the call — a typo in a fallback list must not take down a working model.
     * The primary itself and repeated names are dropped, so a list that circles
     * back cannot make the same call twice.
     */
    private List<LlmConfig> fallbackChain(LlmConfig primary) {
        List<LlmConfig> chain = new ArrayList<>();
        chain.add(primary);
        if (!primary.hasFallbackModels()) return chain;
        Set<String> seen = new LinkedHashSet<>();
        seen.add(primary.name());
        for (String name : primary.fallbackModels()) {
            LlmConfig resolved;
            try {
                resolved = configRepository.findResolvedByName(name).orElse(null);
            } catch (RuntimeException e) {
                // A broken or circular alias chain behind the name.
                log.warn("LLM fallback '{}' of config '{}' cannot be resolved: {}",
                        name, primary.name(), e.getMessage());
                continue;
            }
            if (resolved == null) {
                log.warn("LLM fallback '{}' of config '{}' names no known config — skipping it",
                        name, primary.name());
                continue;
            }
            if (seen.add(resolved.name())) chain.add(resolved);
        }
        return chain;
    }

    private static String names(List<LlmConfig> configs) {
        return configs.stream().map(LlmConfig::name).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
}
