package ai.mindconnect.agent.runtime.capabilities.memory;

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
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
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

    public MemoryPlugin(SummaryStore summaries) {
        this.summaries = summaries;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void install(PluginRegistry registry) {
        ConversationSummarizer summarizer = new ConversationSummarizer(registry.supplier(ModelGateway.class));
        Supplier<SessionStore> sessions = registry.supplier(SessionStore.class);
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

    private ModelRequest fitted(ModelCall call, TokenCounter counter, ModelGateway model,
                                ConversationSummarizer summarizer, BiConsumer<TurnMessage, String> remember) {
        ModelRequest request = call.request();
        MemoryConfig config = MemoryConfig.of(call.context().agent());
        if (config.strategy() == MemoryConfig.Strategy.FULL) {
            return request;
        }
        if (config.strategy() == MemoryConfig.Strategy.NONE) {
            List<TurnMessage> said = request.messages();
            return placed(request, said.subList(firstTurnWhere(said, from -> true), said.size()),
                    Optional.empty(), config.summaryPlacement());
        }
        int budget = (int) (config.maxConversationRatio()
                * model.contextWindowTokens(request.settings()).orElse(DEFAULT_CONTEXT_WINDOW_TOKENS));
        boolean compact = config.strategy() == MemoryConfig.Strategy.COMPACT;
        Optional<ConversationSummary> summary = compact
                ? summaries.find(call.context().session().id()) : Optional.empty();

        List<TurnMessage> messages = new ToolResultTrimmer(counter, budget, config.keepRecentToolResults(), remember)
                .trim(after(summary, request.messages()));
        if (counter.count(messages) > budget) {
            // Half the budget stays as said: a summary that leaves no room for what follows it helps nobody.
            int room = compact ? budget / 2 : budget;
            List<TurnMessage> counted = messages;
            int kept = firstTurnWhere(counted, from -> counter.count(counted.subList(from, counted.size())) > room);
            if (compact && kept > 0) {
                List<TurnMessage> left = messages.subList(0, kept);
                summary = Optional.of(new ConversationSummary(
                        summarizer.summarize(request.turnId(), request.settings(), summary, left),
                        left.get(left.size() - 1).id()));
                summaries.save(call.context().session().id(), summary.get());
            }
            messages = messages.subList(kept, messages.size());
        }
        if (messages.size() > config.maxMessages()) {
            List<TurnMessage> counted = messages;
            messages = messages.subList(
                    firstTurnWhere(counted, from -> counted.size() - from > config.maxMessages()), messages.size());
        }
        return placed(request, messages, summary, config.summaryPlacement());
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
