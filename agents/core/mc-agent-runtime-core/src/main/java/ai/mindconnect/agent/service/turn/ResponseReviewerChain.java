package ai.mindconnect.agent.service.turn;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Runs the response-reviewer chain configured on an agent definition.
 * <p>
 * Each reviewer is a stateless sub-agent that receives {@code user_message},
 * {@code agent_response}, and {@code last_messages} as Pebble template
 * variables and returns the new response text. A response starting with
 * {@code BLOCK:} replaces the answer with the rest after the prefix and
 * stops the chain. Reviewer failures are fail-open: the original draft
 * is kept and the error is logged.
 * <p>
 * Built fresh per turn by {@code AgentChatService.runReviewers}: all
 * per-call data lives in fields so {@link #run()} takes no parameters.
 * Not a Spring bean — its lifetime is bound to the turn that created it.
 */
public class ResponseReviewerChain {

    private static final Logger log = LoggerFactory.getLogger(ResponseReviewerChain.class);
    private static final int RECENT_MESSAGES_LIMIT = 10;

    // ── dependencies ────────────────────────────────────────────────────
    private final AgentTaskRunner runTask;
    private final ConversationManager conversationManager;

    // ── per-turn inputs ─────────────────────────────────────────────────
    private final AgentDefinition def;
    private final String userMessage;
    private final String draftResponse;
    private final UUID conversationId;
    /** Excluded from {@code last_messages} so the just-persisted current turn isn't duplicated. */
    private final UUID currentUserMessageId;
    private final Consumer<StreamEvent> stream;

    public ResponseReviewerChain(AgentTaskRunner runTask,
                                  ConversationManager conversationManager,
                                  AgentDefinition def,
                                  String userMessage,
                                  String draftResponse,
                                  UUID conversationId,
                                  UUID currentUserMessageId,
                                  Consumer<StreamEvent> stream) {
        this.runTask = runTask;
        this.conversationManager = conversationManager;
        this.def = def;
        this.userMessage = userMessage;
        this.draftResponse = draftResponse;
        this.conversationId = conversationId;
        this.currentUserMessageId = currentUserMessageId;
        this.stream = stream;
    }

    /**
     * Applies all reviewers configured on the agent definition. Emits
     * {@link StreamEvent.Reviewing} before each reviewer runs and exactly
     * one {@link StreamEvent.ReviewerDecision} after — plus a
     * {@link StreamEvent.ResponseRevised} when the verdict was MODIFIED
     * or BLOCKED.
     *
     * @return the final response text after all reviewers ran
     */
    public String run() {
        List<String> reviewers = def.effectiveResponseReviewers();
        if (reviewers.isEmpty()) return draftResponse;

        List<MessageView> lastMessages = buildRecentMessageView();

        String response = draftResponse;
        for (String reviewerName : reviewers) {
            String beforeReview = response;
            stream.accept(new StreamEvent.Reviewing(reviewerName));
            try {
                String revised = runTask.run(reviewerName, userMessage,
                        Map.of("user_message", userMessage,
                                "agent_response", response,
                                "last_messages", lastMessages));
                if (revised == null) {
                    log.warn("Reviewer '{}' returned null — keeping original answer", reviewerName);
                    stream.accept(new StreamEvent.ReviewerDecision(
                            reviewerName, StreamEvent.ReviewerVerdict.PASSED));
                    continue;
                }
                String trimmed = revised.trim();
                if (isPassToken(trimmed)) {
                    log.debug("Reviewer '{}' passed the answer", reviewerName);
                    stream.accept(new StreamEvent.ReviewerDecision(
                            reviewerName, StreamEvent.ReviewerVerdict.PASSED));
                } else if (trimmed.regionMatches(true, 0, "BLOCK:", 0, "BLOCK:".length())) {
                    String userFacing = trimmed.substring("BLOCK:".length()).trim();
                    log.info("Reviewer '{}' BLOCKed the answer — replacing with: {}",
                            reviewerName,
                            userFacing.length() > 80 ? userFacing.substring(0, 80) + "…" : userFacing);
                    response = userFacing;
                    stream.accept(new StreamEvent.ReviewerDecision(
                            reviewerName, StreamEvent.ReviewerVerdict.BLOCKED));
                    stream.accept(new StreamEvent.ResponseRevised(
                            response, "blocked by " + reviewerName, true));
                    break;
                } else {
                    log.info("Reviewer '{}' modified the answer ({} → {} chars)",
                            reviewerName, beforeReview.length(), trimmed.length());
                    log.debug("Reviewer '{}' before:\n{}\nafter:\n{}",
                            reviewerName, beforeReview, trimmed);
                    response = trimmed;
                    stream.accept(new StreamEvent.ReviewerDecision(
                            reviewerName, StreamEvent.ReviewerVerdict.MODIFIED));
                    stream.accept(new StreamEvent.ResponseRevised(
                            response, "modified by " + reviewerName, false));
                }
            } catch (Exception e) {
                log.warn("Reviewer '{}' failed — keeping previous answer: {}",
                        reviewerName, e.getMessage());
                response = beforeReview;
                // Fail-open: from the user's perspective the answer was passed through.
                stream.accept(new StreamEvent.ReviewerDecision(
                        reviewerName, StreamEvent.ReviewerVerdict.PASSED));
            }
        }
        return response;
    }

    /**
     * True when the reviewer signalled "no changes" via the {@code PASS} convention.
     * Accepts variants: bare {@code PASS}, {@code PASS:}, {@code PASS - reason},
     * case-insensitive, optionally surrounded by whitespace, backticks or
     * quotes on either side — a model answering {@code `PASS`} means PASS,
     * not "replace the answer with the literal text".
     */
    private static final Pattern PASS_PATTERN =
            Pattern.compile("^[`\"'\\s]*PASS([\\s:\\-—].*)?[`\"'\\s]*$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static boolean isPassToken(String text) {
        return text != null && PASS_PATTERN.matcher(text).matches();
    }

    /**
     * Renders the last {@code RECENT_MESSAGES_LIMIT} USER/AGENT chat turns of
     * the conversation as Pebble-friendly views. Excludes tool messages
     * (kept compact for sub-agent prompts) and excludes the just-persisted
     * current user message so it isn't duplicated.
     */
    private List<MessageView> buildRecentMessageView() {
        List<Message> all = conversationManager.loadHistory(conversationId, new PageRequest(0, 200));
        List<MessageView> chatOnly = all.stream()
                .filter(m -> m.type() == MessageType.CHAT)
                .filter(m -> m.senderType() == ParticipantType.USER
                        || m.senderType() == ParticipantType.AGENT)
                .filter(m -> currentUserMessageId == null || !m.id().equals(currentUserMessageId))
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .map(m -> new MessageView(
                        m.senderType() == ParticipantType.USER ? "user" : "agent",
                        m.content(),
                        m.sequenceNum()))
                .toList();
        if (chatOnly.size() <= RECENT_MESSAGES_LIMIT) return chatOnly;
        return chatOnly.subList(chatOnly.size() - RECENT_MESSAGES_LIMIT, chatOnly.size());
    }

    /** Pebble-friendly projection of a chat message — exposes role/content/seq via record accessors. */
    public record MessageView(String role, String content, int seq) {}
}
