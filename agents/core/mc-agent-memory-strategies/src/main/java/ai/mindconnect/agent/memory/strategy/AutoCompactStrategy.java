package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.memory.domain.SummaryPlacement;
import ai.mindconnect.agent.memory.domain.ToolResultEvictionPolicy;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.port.out.LlmMessageMapper;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.service.StatelessAgentSeeder;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Auto-compact memory strategy — Claude Code pattern.
 * <p>
 * Live messages flow through verbatim. After each turn, if the live window's tokens
 * exceed {@link AutoCompactConfig#compactAtRatio()} of the LLM context window, the
 * full live conversation is collapsed into one {@link ConversationSummary} and the
 * window restarts.
 * <p>
 * No per-message cap, no per-tool-result compression. The whole conversation is the
 * unit of compaction.
 */
public class AutoCompactStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactStrategy.class);
    private static final int MAX_HISTORY_FETCH = 500;

    private final AutoCompactConfig cfg;
    private final ConversationManager conversationManager;
    private final ConversationSummaryRepository summaryRepository;
    private final AgentTaskRunner agentTaskRunner;
    private final TokenCounters tokenCounterRegistry;
    private final LlmConfigRepository llmConfigRepository;
    private final LlmMessageMapper messageMapper;

    public AutoCompactStrategy(AutoCompactConfig cfg,
                               ConversationManager conversationManager,
                               ConversationSummaryRepository summaryRepository,
                               AgentTaskRunner runTask,
                               TokenCounters tokenCounterRegistry,
                               LlmConfigRepository llmConfigRepository) {
        this(cfg, conversationManager, summaryRepository, runTask, tokenCounterRegistry, llmConfigRepository, new MessageToLlmMessageMapper());
    }

    /** @param messageMapper how the selected messages read to the model — the host's {@link LlmMessageMapper} */
    public AutoCompactStrategy(AutoCompactConfig cfg,
                               ConversationManager conversationManager,
                               ConversationSummaryRepository summaryRepository,
                               AgentTaskRunner runTask,
                               TokenCounters tokenCounterRegistry,
                               LlmConfigRepository llmConfigRepository,
                               LlmMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.cfg = cfg;
        this.conversationManager = conversationManager;
        this.summaryRepository = summaryRepository;
        this.agentTaskRunner = runTask;
        this.tokenCounterRegistry = tokenCounterRegistry;
        this.llmConfigRepository = llmConfigRepository;
    }

    @Override public String kind() { return cfg.kind(); }

    // ── window construction ──────────────────────────────────────────────────

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return buildWindow(def, session, auth, conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, MAX_HISTORY_FETCH)));
    }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                        AuthenticationInfo auth, List<Message> history) {
        List<Message> live = applyEviction(liveMessages(session, history));

        List<LlmMessage> result = new ArrayList<>();
        if (cfg.summaryPlacement() == SummaryPlacement.USER_MESSAGE) {
            String summaryUserMsg = renderSummariesAsUserMessage(session);
            if (!summaryUserMsg.isEmpty()) {
                result.add(LlmMessage.user(summaryUserMsg));
            }
        }
        // No budget-driven trimming, no per-message guard — auto-compact assumes the
        // window will not overflow because compact() ran when it crossed the threshold.
        result.addAll(messageMapper.toMessages(ToolPairSanitizer.sanitize(live), def, permissiveBudget(def)));
        return result;
    }

    @Override
        public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session) {
        TokenCounter counter = resolveTokenCounter(def);
        List<Message> live = applyEviction(liveMessages(session));

        // Persisted summaries land in the LLM window (as user-role messages
        // when summaryPlacement=USER_MESSAGE, or appended to the system
        // prompt when SYSTEM_PROMPT). Either way, surface them to the admin
        // UI so operators see why the live window is "empty" after compress.
        List<ConversationSummary> summaries =
                summaryRepository.findByConversationId(session.conversationId());

        List<WorkingMemory.WorkingMemoryMessage> messages =
                new ArrayList<>(SummaryWindowMessages.render(summaries, counter));
        for (Message m : live) {
            String effective = m.compressed() && m.compressedContent() != null
                    ? m.compressedContent() : m.content();
            int tokens = counter.countText(effective);
            String role = switch (m.senderType()) {
                case USER  -> "USER";
                case AGENT -> "AGENT";
                default    -> m.senderType().name();
            };
            messages.add(new WorkingMemory.WorkingMemoryMessage(
                    m.type().name(), role, m.sequenceNum(),
                    m.sentAt().toEpochMilli(), tokens,
                    m.content(), m.compressed(), m.compressedContent()));
        }
        return messages;
    }

    @Override
    public String systemPromptAddendum(AgentDefinition def, AgentSession session) {
        if (cfg.summaryPlacement() != SummaryPlacement.SYSTEM_PROMPT) return "";
        List<ConversationSummary> summaries =
                summaryRepository.findByConversationId(session.conversationId());
        if (summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## Earlier conversation (summarized)");
        appendSummaryBody(sb, summaries);
        return sb.toString();
    }

    // ── tool-result hook (no-op for auto-compact) ───────────────────────────

    // ── auto-compact trigger ─────────────────────────────────────────────────

    @Override
    public void onAfterTurn(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        Integer ctxWindow = contextWindowTokens(def);
        if (ctxWindow == null || ctxWindow <= 0) return;

        TokenCounter counter = resolveTokenCounter(def);
        List<Message> live = liveMessages(session);
        int liveTokens = live.stream()
                .mapToInt(m -> counter.countText(effectiveText(m))).sum();

        int threshold = (int) (ctxWindow * cfg.compactAtRatio());
        if (liveTokens < threshold) {
            log.debug("Auto-compact skipped: live window at {}/{} tokens (threshold {})",
                    liveTokens, ctxWindow, threshold);
            return;
        }
        log.info("Auto-compact triggered: live window at {} tokens ≥ {} (= {}% of {}). Compacting all.",
                liveTokens, threshold, (int) (cfg.compactAtRatio() * 100), ctxWindow);
        try {
            CompressResult result = compress(def, session, auth);
            if (!result.isEmpty()) {
                log.info("Auto-compact: collapsed {} message(s) into a single summary",
                        result.compressedMessages());
            }
        } catch (Exception e) {
            log.warn("Auto-compact failed: {}", e.getMessage());
        }
    }

    // ── /compress (manual) ───────────────────────────────────────────────────

    @Override
    public CompressResult compress(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        List<Message> live = liveMessages(session);
        if (live.isEmpty()) {
            log.info("compress: nothing to compact for session {}", session.id());
            return CompressResult.empty();
        }

        int fromSeq = live.stream().mapToInt(Message::sequenceNum).min().orElseThrow();
        int toSeq   = live.stream().mapToInt(Message::sequenceNum).max().orElseThrow();
        log.info("compress: collapsing {} messages (seq {}-{}) for session {}",
                live.size(), fromSeq, toSeq, session.id());

        String summaryContent = summarise(def, live);
        ConversationSummary summary = ConversationSummary.create(
                session.conversationId(), fromSeq, toSeq, live.size(), summaryContent);
        summaryRepository.save(summary);
        log.info("compress: saved summary {} covering seq {}-{}", summary.id(), fromSeq, toSeq);

        return new CompressResult(live.size());
    }

    // ── ports ────────────────────────────────────────────────────────────────

    @Override
    public TokenCounter resolveTokenCounter(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findResolvedByName(def.llmConfigName()).orElse(null);
        return llmConfig != null
                ? tokenCounterRegistry.forModel(llmConfig.model())
                : tokenCounterRegistry.fallback();
    }

    @Override
    public Integer contextWindowTokens(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findResolvedByName(def.llmConfigName()).orElse(null);
        return llmConfig != null ? llmConfig.contextWindowTokens() : null;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** Live messages = all stored messages whose sequenceNum is not covered by a summary. */
    private List<Message> liveMessages(AgentSession session) {
        return liveMessages(session, conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, MAX_HISTORY_FETCH)));
    }

    private List<Message> liveMessages(AgentSession session, List<Message> all) {
        Set<Integer> summarized = summarizedSequenceNumbers(session);
        return all.stream().filter(m -> !summarized.contains(m.sequenceNum())).toList();
    }

    /**
     * Replace older TOOL_RESULT messages with a short pointer stub (in memory, the
     * stored Message stays untouched). This fights "topic-switch inertia": once the
     * user has moved past a tool call, its bulky output stops dominating the window
     * but the agent can still pull it back via the {@code fetch_tool_result} tool.
     * <p>
     * "Older than N user turns" = appearing before the {@code (N-th-from-last)}
     * USER chat message in the live history.
     */
    private List<Message> applyEviction(List<Message> live) {
        ToolResultEvictionPolicy policy = cfg.toolResultEviction();
        if (policy == null || live.isEmpty()) return live;

        // Find the cutoff: sequence number of the (afterTurns-th-from-last) USER message.
        // Any TOOL_RESULT with seq < cutoff is eligible for eviction.
        int[] userSeqs = live.stream()
                .filter(m -> m.senderType() == ParticipantType.USER && m.type() == MessageType.CHAT)
                .mapToInt(Message::sequenceNum)
                .toArray();
        if (userSeqs.length < policy.afterTurns()) {
            // Not enough user turns yet to evict anything.
            return live;
        }
        int cutoffSeq = userSeqs[userSeqs.length - policy.afterTurns()];

        List<Message> result = new ArrayList<>(live.size());
        int evictedCount = 0;
        for (Message m : live) {
            if (m.type() == MessageType.TOOL_RESULT
                    && m.sequenceNum() < cutoffSeq
                    && originalTokens(m) >= policy.aboveTokens()
                    && !isAlreadyEvictionStub(m)) {
                result.add(asEvictionStub(m));
                evictedCount++;
            } else {
                result.add(m);
            }
        }
        if (evictedCount > 0) {
            log.debug("Eviction: replaced {} tool result(s) with stubs (cutoff seq < {})",
                    evictedCount, cutoffSeq);
        }
        return result;
    }

    /** Token count of the original tool result (or compressed if already compressed). */
    private int originalTokens(Message m) {
        if (m.tokenCount() != null) return m.tokenCount();
        // Fallback for legacy messages without tokenCount — count on demand
        return 0;
    }

    /** Stubs we generated previously already have this marker — avoid double-evicting. */
    private boolean isAlreadyEvictionStub(Message m) {
        return m.compressed() && m.compressedContent() != null
                && m.compressedContent().startsWith("[Tool result evicted");
    }

    /**
     * Wraps the tool-result message with a copy whose {@code compressedContent} is a
     * pointer stub. The original {@code content} field is untouched — only the in-memory
     * representation that the strategy hands to the LLM changes.
     */
    private Message asEvictionStub(Message m) {
        int origTokens = originalTokens(m);
        String stub = "[Tool result evicted from context — id=" + m.id()
                + ", originally ~" + origTokens + " tokens. "
                + "Call the `fetch_tool_result` tool with this id to reload the full content.]";
        return m.withCompressed(stub, stub.length() / 4);  // rough token estimate for accounting
    }

    private Set<Integer> summarizedSequenceNumbers(AgentSession session) {
        return summaryRepository.findByConversationId(session.conversationId())
                .stream()
                .flatMapToInt(s -> IntStream.rangeClosed(s.fromSequenceNum(), s.toSequenceNum()))
                .boxed()
                .collect(Collectors.toSet());
    }

    private String effectiveText(Message m) {
        return m.compressed() && m.compressedContent() != null ? m.compressedContent() : m.content();
    }

    /** Used by MessageMapper so per-message truncation is effectively disabled. */
    private ai.mindconnect.agent.service.ContextTokenBudget permissiveBudget(AgentDefinition def) {
        ai.mindconnect.agent.memory.domain.SummarizingWindowConfig synthetic =
                new ai.mindconnect.agent.memory.domain.SummarizingWindowConfig(
                        1.0, 1.0, false, 1, 1.0, 1.0, false, 1.0,
                        SummaryPlacement.SYSTEM_PROMPT, MAX_HISTORY_FETCH);
        return ai.mindconnect.agent.service.ContextTokenBudget.resolve(
                def, synthetic, llmConfigRepository, tokenCounterRegistry);
    }

    private String renderSummariesAsUserMessage(AgentSession session) {
        List<ConversationSummary> summaries =
                summaryRepository.findByConversationId(session.conversationId());
        if (summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "This session is being continued from a previous conversation that ran out of context. "
                + "The summary below covers the earlier portion of the conversation.\n\nSummary:");
        appendSummaryBody(sb, summaries);
        return sb.toString();
    }

    private void appendSummaryBody(StringBuilder sb, List<ConversationSummary> summaries) {
        for (int i = 0; i < summaries.size(); i++) {
            ConversationSummary s = summaries.get(i);
            sb.append("\n\n### Summary ").append(i + 1)
              .append(" (messages ").append(s.fromSequenceNum())
              .append("–").append(s.toSequenceNum()).append(")\n")
              .append(s.content());
        }
    }

    private String summarise(AgentDefinition def, List<Message> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Message m : messages) {
            switch (m.type()) {
                case CHAT -> {
                    String role = m.senderId().equals(def.id()) ? "Agent" : "User";
                    transcript.append(role).append(": ").append(m.content()).append("\n");
                }
                case TOOL_CALL -> transcript.append("[tool call]\n");
                case TOOL_RESULT -> {
                    String text = m.compressed() && m.compressedContent() != null
                            ? m.compressedContent() : "[tool result]";
                    transcript.append(text).append("\n");
                }
                default -> {}
            }
        }
        String transcriptStr = transcript.toString();
        log.info("summarise: {} chars across {} messages", transcriptStr.length(), messages.size());
        String summary = agentTaskRunner.run(StatelessAgentSeeder.CONVERSATION_SUMMARIZER, transcriptStr);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("conversation-summarizer returned an empty result");
        }
        return summary;
    }
}
