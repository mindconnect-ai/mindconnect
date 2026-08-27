package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.ContextTokenBudget;
import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.domain.SummaryPlacement;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.service.StatelessAgentSeeder;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Window + summarization. Old messages are compressed into {@link ConversationSummary}
 * records (injected as system-prompt addendum), and large tool results are eagerly
 * compressed once the live window crosses {@link SummarizingWindowConfig#compressWhenWindowAboveRatio()}.
 * <p>
 * All thresholds and ratios come from the per-agent {@link SummarizingWindowConfig}.
 */
public class SummarizingWindowStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(SummarizingWindowStrategy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SummarizingWindowConfig cfg;
    private final ConversationManager conversationManager;
    private final ConversationSummaryRepository summaryRepository;
    private final ToolResultSummarizer toolResultSummarizer;
    private final AgentTaskRunner agentTaskRunner;
    private final TokenCounters tokenCounterRegistry;
    private final LlmConfigRepository llmConfigRepository;
    private final MessageToLlmMessageMapper messageMapper = new MessageToLlmMessageMapper();

    public SummarizingWindowStrategy(SummarizingWindowConfig cfg,
                                     ConversationManager conversationManager,
                                     ConversationSummaryRepository summaryRepository,
                                     ToolResultSummarizer toolResultSummarizer,
                                     AgentTaskRunner agentTaskRunner,
                                     TokenCounters tokenCounterRegistry,
                                     LlmConfigRepository llmConfigRepository) {
        this.cfg = cfg;
        this.conversationManager = conversationManager;
        this.summaryRepository = summaryRepository;
        this.toolResultSummarizer = toolResultSummarizer;
        this.agentTaskRunner = agentTaskRunner;
        this.tokenCounterRegistry = tokenCounterRegistry;
        this.llmConfigRepository = llmConfigRepository;
    }

    @Override public String kind() { return cfg.kind(); }

    // ── window construction ──────────────────────────────────────────────────

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return buildWindow(def, session, auth, conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, cfg.maxHistoryFetch())));
    }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                        AuthenticationInfo auth, List<Message> all) {
        ContextTokenBudget budget = budget(def);

        Set<Integer> summarizedSeqs = summarizedSequenceNumbers(session);
        List<Message> unsummarized = all.stream()
                .filter(m -> !summarizedSeqs.contains(m.sequenceNum()))
                .toList();

        List<Message> windowed = trimToWindowBudget(unsummarized, budget);

        if (windowed.size() < unsummarized.size()) {
            log.warn("buildWindow: {} unsummarized messages, trimmed to {} to fit ~{} token budget. Run /compress.",
                    unsummarized.size(), windowed.size(), budget.windowBudget());
        } else {
            log.debug("buildWindow: {} total, {} summarized, {} in window",
                    all.size(), summarizedSeqs.size(), windowed.size());
        }

        List<LlmMessage> result = new ArrayList<>();
        if (cfg.summaryPlacement() == SummaryPlacement.USER_MESSAGE) {
            String summaryUserMsg = renderSummariesAsUserMessage(session);
            if (!summaryUserMsg.isEmpty()) {
                result.add(LlmMessage.user(summaryUserMsg));
            }
        }
        result.addAll(messageMapper.toMessages(ToolPairSanitizer.sanitize(windowed), def, budget));
        return result;
    }

    @Override
    public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session) {
        ContextTokenBudget budget = budget(def);

        List<Message> all = conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, cfg.maxHistoryFetch()));
        Set<Integer> summarizedSeqs = summarizedSequenceNumbers(session);
        List<Message> windowed = trimToWindowBudget(
                all.stream().filter(m -> !summarizedSeqs.contains(m.sequenceNum())).toList(),
                budget);

        // Surface persisted summaries so the admin UI shows what the LLM
        // actually receives — buildWindow injects them into the prompt
        // (placement-dependent), getWindowMessages used to omit them.
        List<ConversationSummary> summaries =
                summaryRepository.findByConversationId(session.conversationId());
        List<WorkingMemory.WorkingMemoryMessage> messages =
                new ArrayList<>(SummaryWindowMessages.render(summaries, budget.counter()));
        for (Message m : windowed) {
            String effective = m.compressed() && m.compressedContent() != null
                    ? m.compressedContent() : m.content();
            int tokens = budget.counter().countText(effective);
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

    /**
     * Renders the summaries as a single user message — the Claude-Code style
     * "this session is being continued from a previous conversation" prefix.
     * Returns "" when no summaries exist.
     */
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

    // ── tool-result compression (at turn-execution entry) ────────────────────

    /**
     * How many of the newest TOOL_RESULTs always stay uncompressed — the
     * Claude model's "keep recent tool uses": the model may still be
     * comparing the last few results in detail, however old their rounds.
     */
    private static final int KEEP_RECENT_RESULTS = 3;

    /**
     * Marks eligible tool results compressed ({@code withCompressed} — the
     * original stays in {@code content}, only the rendered window shrinks).
     * Runs at the start of every turn execution, on the freshly loaded
     * history. Eligible means, in this order:
     * <ol>
     *   <li>the LLM has READ the result — an assistant message (CHAT or
     *       TOOL_CALL) follows it; the results a wake is about are unread
     *       and stay full, so a round never loses what it just fetched</li>
     *   <li>it is not among the newest {@link #KEEP_RECENT_RESULTS}</li>
     *   <li>the window is under pressure ({@code compressWhenWindowAboveRatio})</li>
     *   <li>the single result is above the size threshold</li>
     * </ol>
     */
    @Override
    public int compressEligibleToolResults(AgentDefinition def, AgentSession session,
                                           AuthenticationInfo auth, List<Message> history) {
        if (!cfg.compressToolResults() || history.isEmpty()) return 0;

        ContextTokenBudget budget = budget(def);
        TokenCounter counter = budget.counter();

        // Skip compression while the live window is still mostly empty — compressing
        // early throws away detail that isn't yet competing for space.
        int currentTokens = history.stream()
                .mapToInt(m -> counter.countText(effectiveText(m)))
                .sum();
        if (currentTokens < budget.contextWindow() * cfg.compressWhenWindowAboveRatio()) {
            log.debug("Skipping tool-result compression: window at {}/{} tokens ({}% of budget)",
                    currentTokens, budget.contextWindow(),
                    (int) (100.0 * currentTokens / budget.contextWindow()));
            return 0;
        }

        List<Message> sorted = history.stream()
                .sorted(java.util.Comparator.comparingInt(Message::sequenceNum))
                .toList();
        List<Message> toolResults = sorted.stream()
                .filter(m -> m.type() == MessageType.TOOL_RESULT)
                .toList();
        Set<UUID> keepFull = toolResults.stream()
                .skip(Math.max(0, toolResults.size() - KEEP_RECENT_RESULTS))
                .map(Message::id)
                .collect(java.util.stream.Collectors.toSet());

        int threshold = thresholdFor(budget);
        int marked = 0;
        for (Message m : toolResults) {
            if (m.compressed() || keepFull.contains(m.id())) continue;
            if (!readByModel(sorted, m)) continue;
            if (compressOneToolResult(session.conversationId(), m, counter, threshold)) marked++;
        }
        return marked;
    }

    /**
     * The model has consumed this result: some LLM output (an agent-sent
     * CHAT or TOOL_CALL) was persisted after it. Until then the result is
     * exactly what the current round is about — never touch it.
     */
    private static boolean readByModel(List<Message> sorted, Message result) {
        return sorted.stream().anyMatch(m -> m.sequenceNum() > result.sequenceNum()
                && m.senderType() == ParticipantType.AGENT
                && (m.type() == MessageType.CHAT || m.type() == MessageType.TOOL_CALL));
    }

    @Override
    public void onAfterTurn(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        if (!cfg.autoSummarize()) return;

        ContextTokenBudget budget = budget(def);
        Set<Integer> summarizedSeqs = summarizedSequenceNumbers(session);
        List<Message> all = conversationManager.loadHistory(session.conversationId(),
                new PageRequest(0, cfg.maxHistoryFetch()));
        int liveTokens = all.stream()
                .filter(m -> !summarizedSeqs.contains(m.sequenceNum()))
                .mapToInt(m -> budget.counter().countText(effectiveText(m)))
                .sum();

        int threshold = (int) (budget.contextWindow() * cfg.autoSummarizeRatio());
        if (liveTokens < threshold) {
            log.debug("Auto-summarize skipped: live window at {}/{} tokens (threshold {})",
                    liveTokens, budget.contextWindow(), threshold);
            return;
        }
        log.info("Auto-summarize triggered: live window at {} tokens ≥ {} (= {}% of {}). Compressing.",
                liveTokens, threshold,
                (int) (cfg.autoSummarizeRatio() * 100), budget.contextWindow());
        try {
            CompressResult result = compress(def, session, auth);
            if (!result.isEmpty()) {
                log.info("Auto-summarize: compressed {} message(s)", result.compressedMessages());
            }
        } catch (Exception e) {
            log.warn("Auto-summarize failed: {}", e.getMessage());
        }
    }

    /**
     * Migration hook: compresses tool-result messages that have not yet been
     * compressed (e.g. from before tool-result compression was introduced).
     * No caller in the current codebase; kept for future maintenance flows.
     */
    public int compressUncompressedToolResults(AgentDefinition def, UUID conversationId,
                                               List<Message> toolResults) {
        if (toolResults.isEmpty()) return 0;
        ContextTokenBudget budget = budget(def);
        int threshold = thresholdFor(budget);
        for (Message m : toolResults) {
            compressOneToolResult(conversationId, m, budget.counter(), threshold);
        }
        return toolResults.size();
    }

    /**
     * Force-re-compresses all tool-result messages from their original content,
     * even if they were already compressed. Useful for benchmarking summarizer
     * quality. No caller in the current codebase; kept for future debug flows.
     */
    @SuppressWarnings("unchecked")
    public int recompressAllToolResults(AgentDefinition def, UUID conversationId,
                                        List<Message> toolResults) {
        ContextTokenBudget budget = budget(def);
        TokenCounter counter = budget.counter();
        int count = 0;
        for (Message m : toolResults) {
            try {
                Map<String, Object> payload = MAPPER.readValue(m.content(), Map.class);
                String rawResult = (String) payload.get("result");
                String toolName  = (String) payload.getOrDefault("toolName", "tool");
                if (rawResult == null) continue;
                String stub = toolResultSummarizer.summarize(toolName, rawResult);
                int compressedTokens = counter.countText(stub);
                conversationManager.compressMessage(conversationId, m.id(), stub, compressedTokens);
                log.info("Re-compressed tool result message {} (tool={})", m.id(), toolName);
                count++;
            } catch (Exception e) {
                log.warn("Failed to re-compress tool result message {}: {}", m.id(), e.getMessage());
            }
        }
        return count;
    }

    // ── /compress ────────────────────────────────────────────────────────────

    @Override
    public CompressResult compress(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        List<Message> all = conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, cfg.maxHistoryFetch()));
        if (all.isEmpty()) {
            log.info("compress: no messages in session {}", session.id());
            return CompressResult.empty();
        }

        Set<Integer> alreadySummarized = summarizedSequenceNumbers(session);
        List<Message> candidates = all.stream()
                .filter(m -> !alreadySummarized.contains(m.sequenceNum()))
                .toList();

        if (candidates.isEmpty()) {
            log.info("compress: all messages already summarized for session {}", session.id());
            return CompressResult.empty();
        }

        ContextTokenBudget budget = budget(def);
        int totalCompressed = 0;
        List<Message> remaining = new ArrayList<>(candidates);

        while (!remaining.isEmpty()) {
            List<Message> chunk = takeChunk(remaining, budget);
            if (chunk.isEmpty()) break;

            int fromSeq = chunk.stream().mapToInt(Message::sequenceNum).min().orElseThrow();
            int toSeq   = chunk.stream().mapToInt(Message::sequenceNum).max().orElseThrow();
            int chunkTokens = chunk.stream()
                    .mapToInt(m -> budget.counter().countText(effectiveText(m))).sum();

            log.info("compress: summarizing {} messages (seq {}-{}, ~{} tokens) for session {}",
                    chunk.size(), fromSeq, toSeq, chunkTokens, session.id());

            String summaryContent = summarise(def, chunk);
            ConversationSummary summary = ConversationSummary.create(
                    session.conversationId(), fromSeq, toSeq, chunk.size(), summaryContent);
            summaryRepository.save(summary);
            log.info("compress: saved summary {} covering seq {}-{}", summary.id(), fromSeq, toSeq);

            totalCompressed += chunk.size();
            remaining = remaining.subList(chunk.size(), remaining.size());
        }

        if (totalCompressed == 0) return CompressResult.empty();
        log.info("compress: done — {} messages compressed", totalCompressed);
        return new CompressResult(totalCompressed);
    }

    @Override
    public TokenCounter resolveTokenCounter(AgentDefinition def) {
        return budget(def).counter();
    }

    @Override
    public Integer contextWindowTokens(AgentDefinition def) {
        return budget(def).contextWindow();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private ContextTokenBudget budget(AgentDefinition def) {
        return ContextTokenBudget.resolve(def, cfg, llmConfigRepository, tokenCounterRegistry);
    }

    private int thresholdFor(ContextTokenBudget budget) {
        return Math.max(cfg.minToolResultCompressTokens(),
                (int) (budget.contextWindow() * cfg.toolResultThresholdRatio()));
    }

    @SuppressWarnings("unchecked")
    private boolean compressOneToolResult(UUID conversationId, Message m,
                                          TokenCounter counter, int threshold) {
        try {
            if (m.compressed()) return false;
            Map<String, Object> payload = MAPPER.readValue(m.content(), Map.class);
            String rawResult = (String) payload.get("result");
            String toolName  = (String) payload.getOrDefault("toolName", "tool");
            if (rawResult == null) return false;
            int rawTokens = counter.countText(rawResult);
            if (rawTokens <= threshold) return false;

            String stub = toolResultSummarizer.summarize(toolName, rawResult);
            int compressedTokens = counter.countText(stub);
            conversationManager.compressMessage(conversationId, m.id(), stub, compressedTokens);
            log.debug("Compressed tool result message {} (~{} → ~{} tokens, threshold={})",
                    m.id(), rawTokens, compressedTokens, threshold);
            return true;
        } catch (Exception e) {
            log.warn("Failed to compress tool result message {}: {}", m.id(), e.getMessage());
            return false;
        }
    }

    private List<Message> trimToWindowBudget(List<Message> messages, ContextTokenBudget budget) {
        if (messages.isEmpty()) return messages;
        int accumulated = 0;
        List<Message> kept = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            int tokens = budget.counter().countText(effectiveText(m));
            if (accumulated + tokens > budget.windowBudget() && !kept.isEmpty()) break;
            accumulated += tokens;
            kept.add(0, m);
        }
        return kept;
    }

    private List<Message> takeChunk(List<Message> remaining, ContextTokenBudget budget) {
        int accumulated = 0;
        List<Message> chunk = new ArrayList<>();
        for (Message m : remaining) {
            int tokens = budget.counter().countText(effectiveText(m));
            accumulated += tokens;
            chunk.add(m);
            if (accumulated >= budget.windowBudget()) break;
        }
        return chunk;
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
