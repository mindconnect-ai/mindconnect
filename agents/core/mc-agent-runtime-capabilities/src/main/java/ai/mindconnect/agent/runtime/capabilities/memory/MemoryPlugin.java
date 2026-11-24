package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.UUID;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.BackgroundModel;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Memory: keeps what the model is sent within its context. It works on the
 * request of one model call and never on the session — the session keeps
 * every message, so nothing is lost and a different strategy can be tried
 * on the same history.
 *
 * <p>Which strategy an agent gets it says in its metadata, see
 * {@link MemoryConfig}: everything, only the turn underway, or what fits.
 * For what fits, in order: old tool results are shortened, then the oldest
 * turns are left out — summarised first, if the agent asks for that. Turns are left out whole, so a tool call never
 * loses its result.
 */
public final class MemoryPlugin implements Plugin {

    /** The key in an agent's metadata that holds its memory settings. */
    public static final String MEMORY = "memory";

    /** The window assumed for a model whose gateway does not know it. */
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;

    private final SummaryStore summaries;

    /**
     * For whoever lets a person edit an agent's memory setting: refuses a
     * setting no turn could run with, at the time it is saved instead of in
     * the agent's next turn.
     *
     * @throws IllegalArgumentException for a strategy or a summary placement there is none of
     */
    public static void check(Map<String, Object> setting) {
        MemoryConfig.of(setting);
    }

    public MemoryPlugin(SummaryStore summaries) {
        this.summaries = summaries;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        ConversationSummarizer summarizer = new ConversationSummarizer(registry.supplier(ModelGateway.class),
                () -> registry.find(BackgroundModel.class));
        Supplier<SessionStore> sessions = registry.supplier(SessionStore.class);
        registry.provide(MemoryCompaction.class, () -> sessionId -> compact(sessions.get(), sessionId, summarizer));
        registry.provide(MemoryInspection.class, () -> sessionId -> inspect(registry, sessionId));
        registry.before(RuntimeHook.MODEL_CALL, call -> BeforeOutcome.proceed(call.with(fitted(call,
                registry.get(TokenCounter.class), registry.get(ModelGateway.class), summarizer,
                // Kept on the message, so the result is shown as this note from now on.
                (message, note) -> sessions.get().updateMessageMetadata(call.context().session().id(), message.id(),
                        metadata -> {
                            Map<String, Object> changed = new HashMap<>(metadata);
                            changed.put(SessionStore.COMPRESSED, note);
                            return changed;
                        })))));
    }

    private int compact(SessionStore sessions, SessionId sessionId, ConversationSummarizer summarizer) {
        Session session = sessions.find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        if (session.agent() == null
                || MemoryConfig.of(session.agent()).strategy() != MemoryConfig.Strategy.COMPACT) {
            return 0;
        }
        Optional<ConversationSummary> summary = summaries.find(sessionId);
        List<TurnMessage> open = after(summary, sessions.messages(sessionId));
        // The last turn stays as said: what follows a summary needs something to follow.
        List<TurnMessage> left = open.subList(0, firstTurnWhere(open, from -> true));
        if (left.isEmpty()) {
            return 0;
        }
        summaries.save(sessionId, new ConversationSummary(
                summarizer.summarize(TurnId.of("compaction-" + UUID.randomUUID()), session.agent().modelSettings(),
                        summary, left),
                left.get(left.size() - 1).id()));
        return left.size();
    }

    /**
     * What of a history goes to the model: the summary there is, the
     * messages that are sent, and the ones that have to be summarised first
     * because they no longer fit.
     */
    private record Fit(Optional<ConversationSummary> summary, List<TurnMessage> sent, List<TurnMessage> toSummarise) { }

    private ModelRequest fitted(ModelCall call, TokenCounter counter, ModelGateway model,
                                ConversationSummarizer summarizer, BiConsumer<TurnMessage, String> remember) {
        ModelRequest request = call.request();
        MemoryConfig config = MemoryConfig.of(call.context().agent());
        if (config.strategy() == MemoryConfig.Strategy.FULL) {
            return request;
        }
        Fit fit = fit(config, call.context().session().id(), request.messages(), request.settings(), counter, model,
                remember);
        Optional<ConversationSummary> summary = fit.summary();
        if (!fit.toSummarise().isEmpty()) {
            List<TurnMessage> left = fit.toSummarise();
            summary = Optional.of(new ConversationSummary(
                    summarizer.summarize(request.turnId(), request.settings(), summary, left),
                    left.get(left.size() - 1).id()));
            summaries.save(call.context().session().id(), summary.get());
        }
        return placed(request, fit.sent(), summary, config.summaryPlacement());
    }

    /** Decides and changes nothing, except through {@code remember}: summarising is the caller's. */
    private Fit fit(MemoryConfig config, SessionId sessionId, List<TurnMessage> said, ModelSettings settings,
                    TokenCounter counter, ModelGateway model, BiConsumer<TurnMessage, String> remember) {
        if (config.strategy() == MemoryConfig.Strategy.FULL) {
            return new Fit(Optional.empty(), said, List.of());
        }
        if (config.strategy() == MemoryConfig.Strategy.NONE) {
            return new Fit(Optional.empty(), said.subList(firstTurnWhere(said, from -> true), said.size()),
                    List.of());
        }
        int budget = (int) (config.maxConversationRatio()
                * model.contextWindowTokens(settings).orElse(DEFAULT_CONTEXT_WINDOW_TOKENS));
        boolean compact = config.strategy() == MemoryConfig.Strategy.COMPACT;
        Optional<ConversationSummary> summary = compact ? summaries.find(sessionId) : Optional.empty();

        List<TurnMessage> messages = new ToolResultTrimmer(counter, budget, config.keepRecentToolResults(), remember)
                .trim(after(summary, said));
        List<TurnMessage> toSummarise = List.of();
        if (counter.count(messages) > budget) {
            // Half the budget stays as said: a summary that leaves no room for what follows it helps nobody.
            int room = compact ? budget / 2 : budget;
            List<TurnMessage> counted = messages;
            int kept = firstTurnWhere(counted, from -> counter.count(counted.subList(from, counted.size())) > room);
            if (compact && kept > 0) {
                toSummarise = List.copyOf(messages.subList(0, kept));
            }
            messages = messages.subList(kept, messages.size());
        }
        if (messages.size() > config.maxMessages()) {
            List<TurnMessage> counted = messages;
            messages = messages.subList(
                    firstTurnWhere(counted, from -> counted.size() - from > config.maxMessages()), messages.size());
        }
        return new Fit(summary, messages, toSummarise);
    }

    private WorkingMemory inspect(PluginRegistry registry, SessionId sessionId) {
        Session session = registry.get(SessionStore.class).find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        AgentDefinition agent = session.agent();
        if (agent == null) {
            throw new IllegalArgumentException("Session " + sessionId.value() + " has no agent");
        }
        TokenCounter counter = registry.get(TokenCounter.class);
        ModelGateway model = registry.get(ModelGateway.class);
        // As the engine makes them for a model call: the template with what the providers know, then what
        // the capabilities add. No turn is underway, so the context names none.
        Map<String, Object> variables = new HashMap<>();
        registry.all(PromptVariableProvider.class).forEach(provider ->
                variables.putAll(provider.variables(agent, session)));
        String instructions = registry.run(RuntimeHook.INSTRUCTIONS, new AgentContext(session, agent, null),
                context -> registry.get(PromptRenderer.class).render(agent.instructions(), variables));

        List<TurnMessage> said = registry.get(SessionStore.class).messages(sessionId);
        Fit fit = fit(MemoryConfig.of(agent), sessionId, said, agent.modelSettings(), counter, model,
                (message, note) -> { });
        // What would be summarised first is not sent as said either.
        Map<String, TurnMessage> sent = new HashMap<>();
        fit.sent().forEach(message -> sent.put(message.id(), message));
        List<WorkingMemory.Entry> entries = said.stream().map(message -> {
            TurnMessage asSent = sent.get(message.id());
            boolean shortened = asSent != null && !asSent.text().equals(message.text());
            return new WorkingMemory.Entry(message, asSent != null, shortened ? asSent.text() : null,
                    counter.count(asSent != null ? asSent.text() : message.text()));
        }).toList();
        Optional<String> summary = fit.summary().map(ConversationSummary::text);
        int instructionTokens = counter.count(instructions);
        return new WorkingMemory(instructions, instructionTokens, summary, entries,
                instructionTokens + summary.map(counter::count).orElse(0) + counter.count(fit.sent()),
                model.contextWindowTokens(agent.modelSettings()));
    }

    /** What the summary does not cover; everything, when there is none or its last message is not in the history. */
    private static List<TurnMessage> after(Optional<ConversationSummary> summary, List<TurnMessage> messages) {
        return summary.map(covered -> {
            for (int i = 0; i < messages.size(); i++) {
                if (covered.throughMessageId().equals(messages.get(i).id())) {
                    return messages.subList(i + 1, messages.size());
                }
            }
            return messages;
        }).orElse(messages);
    }

    /**
     * Where the newest turns begin that are not too much: going back from
     * the end, the start of the last turn before {@code tooMuch} says so of
     * a start. Never past the start of the last turn — the turn underway is
     * sent whole, whatever it takes — and 0 for a history without a turn.
     */
    private static int firstTurnWhere(List<TurnMessage> messages, IntPredicate tooMuch) {
        int kept = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).type() != TurnMessage.Type.USER) {
                continue;
            }
            if (kept >= 0 && tooMuch.test(i)) {
                break;
            }
            kept = i;
        }
        return Math.max(kept, 0);
    }

    private static ModelRequest placed(ModelRequest request, List<TurnMessage> messages,
                                       Optional<ConversationSummary> summary, MemoryConfig.SummaryPlacement placement) {
        String instructions = request.instructions();
        List<TurnMessage> sent = new ArrayList<>(messages);
        if (summary.isPresent() && placement == MemoryConfig.SummaryPlacement.SYSTEM_PROMPT) {
            instructions += "\n\n## Earlier conversation (summarized)\n\n" + summary.get().text();
        } else if (summary.isPresent()) {
            sent.add(0, TurnMessage.user(List.of(new ContentPart.Text(
                    "This session is being continued from a previous conversation that ran out of context. "
                            + "The summary below covers the earlier portion of the conversation.\n\nSummary:\n"
                            + summary.get().text()))));
        }
        return new ModelRequest(request.turnId(), request.settings(), instructions, sent, request.tools());
    }
}
