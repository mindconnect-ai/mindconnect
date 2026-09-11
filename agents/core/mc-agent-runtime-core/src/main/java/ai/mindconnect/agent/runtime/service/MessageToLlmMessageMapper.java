package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.MediaTypes;
import ai.mindconnect.agent.runtime.port.out.PartContentReader;
import ai.mindconnect.agent.runtime.port.out.LlmMessageMapper;
import ai.mindconnect.agent.runtime.service.prompt.AttachmentNotice;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Translates stored {@link Message} domain records into {@link LlmMessage} objects
 * ready to be sent to the LLM.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Structural mapping: CHAT → user/assistant, TOOL_CALL → assistantWithToolCalls,
 *       TOOL_RESULT → tool (using compressed stub if available)</li>
 *   <li>Media: an image or document part of a user message travels as a
 *       content block when the target model declares it reads that kind
 *       ({@link LlmConfig#capabilities()}) and the message belongs to the
 *       current turn; otherwise a placeholder line stands in for it — see
 *       {@link #placeholder}. Bytes come from the {@link PartContentReader}.</li>
 *   <li>Per-message token guard: truncates any message whose effective text exceeds
 *       {@link ContextTokenBudget#maxMessageTokens()}</li>
 * </ul>
 * <p>
 * Media is sent in the current turn only. A request repeats the whole
 * history, and an image repeated in every request costs its tokens every
 * time; once the turn that brought it is over, its placeholder names it and
 * the model can ask for it again. No repository access, no summarization,
 * no window logic — pure translation.
 */
public class MessageToLlmMessageMapper implements LlmMessageMapper {

    private static final Logger log = LoggerFactory.getLogger(MessageToLlmMessageMapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRUNCATION_MARKER =
            "\n[... truncated — content exceeded per-message token limit]";

    /** Largest file sent inline; above it the placeholder says so. */
    static final long MAX_INLINE_BYTES = 20L * 1024 * 1024;

    /** The tool that shows an earlier attachment again — named in the placeholder when the agent has it. */
    static final String VIEWER_TOOL = "view_attachment";

    private final PartContentReader partContentReader;

    /** A mapper without a file store: every media part renders as its placeholder. */
    public MessageToLlmMessageMapper() {
        this(PartContentReader.none());
    }

    public MessageToLlmMessageMapper(PartContentReader partContentReader) {
        this.partContentReader = Objects.requireNonNull(partContentReader, "partContentReader");
    }

    /**
     * Maps a list of stored messages to LLM-ready messages, enforcing the per-message
     * token limit from the supplied budget and rendering media by what {@code target}
     * declares it reads.
     */
    @Override
    public List<LlmMessage> toMessages(List<Message> messages,
                                       AgentDefinition def,
                                       AgentSession session,
                                       ContextTokenBudget budget,
                                       LlmConfig target) {
        Message lastUser = lastUserChat(messages);
        List<LlmMessage> result = new ArrayList<>();
        for (Message m : messages) {
            switch (m.type()) {
                case CHAT -> {
                    boolean assistant = m.senderId().equals(def.id().value());
                    String text = guard(textForModel(m, def, session), budget, m.sequenceNum(), "CHAT");
                    if (assistant) {
                        result.add(LlmMessage.assistant(text));
                    } else if (media(m).isEmpty()) {
                        result.add(LlmMessage.user(text));
                    } else {
                        result.add(LlmMessage.user(userParts(m, text, target, inCurrentTurn(m, lastUser),
                                viewerAvailable(def, session))));
                    }
                }
                case TOOL_CALL -> mapToolCall(m, result);
                case TOOL_RESULT -> mapToolResult(m, result, budget);
                default -> { /* skip SYSTEM and the not-yet-mapped approval/dispatch markers */ }
            }
        }
        return result;
    }

    /**
     * A user message that announced attachments (metadata) gets the notice
     * ahead of its text — the stored text stays what the user typed. Only
     * files still attached are named: a removed one is gone from the store,
     * so it must be gone from the notice too. A media part stands in as its
     * one-line descriptor. Assistant text is as stored.
     */
    @Override
    public String modelText(Message m, AgentDefinition def, AgentSession session) {
        String text = textForModel(m, def, session);
        List<ContentPart.Media> media = media(m);
        if (media.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text);
        for (ContentPart.Media part : media) {
            out.append('\n').append('[').append(describe(part)).append(']');
        }
        return out.toString();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** The text of the message as the model reads it — notices ahead of a user's text. */
    private static String textForModel(Message m, AgentDefinition def, AgentSession session) {
        if (m.type() != MessageType.CHAT || m.senderId().equals(def.id().value())) return m.content();
        return AttachmentNotice.forModel(m, session);
    }

    /** Can this agent, in this session, call the viewer tool — assigned, or activated by an upload? */
    private static boolean viewerAvailable(AgentDefinition def, AgentSession session) {
        if (session != null && session.activatedTools().contains(VIEWER_TOOL)) return true;
        return def.tools() != null && def.tools().stream().anyMatch(t -> VIEWER_TOOL.equals(t.name()));
    }

    private static List<ContentPart.Media> media(Message m) {
        if (m.parts() == null) return List.of();
        return m.parts().stream()
                .filter(ContentPart.Media.class::isInstance)
                .map(ContentPart.Media.class::cast)
                .toList();
    }

    private static Message lastUserChat(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.type() == MessageType.CHAT && m.senderType() == ParticipantType.USER) return m;
        }
        return null;
    }

    /**
     * The current turn is the one the last user message opened: that message
     * itself, and every message sharing its turn id (a message the runtime
     * inserted on the user's behalf within the turn, say). A legacy message
     * without a turn id is current only when it is the last user message.
     */
    private static boolean inCurrentTurn(Message m, Message lastUser) {
        if (lastUser == null) return false;
        if (m.id().equals(lastUser.id())) return true;
        return m.turnId() != null && m.turnId().equals(lastUser.turnId());
    }

    /**
     * The user's text first, then each media part — inline when the model
     * reads it and it belongs to the current turn, a placeholder line
     * otherwise. Text and placeholders are separate blocks; a gateway joins
     * them when the message ends up text-only after all.
     */
    private List<LlmContent> userParts(Message m, String text, LlmConfig target, boolean currentTurn,
                                       boolean viewerAvailable) {
        List<LlmContent> parts = new ArrayList<>();
        parts.add(new LlmContent.Text(text));
        for (ContentPart.Media part : media(m)) {
            LlmCapability needed = part instanceof ContentPart.Image
                    ? LlmCapability.VISION : LlmCapability.DOCUMENTS;
            if (target == null || !target.supports(needed)) {
                parts.add(placeholder(part, part instanceof ContentPart.Image
                        ? "this model does not read images, so it is not included"
                        : "this model does not read documents; read its content with vector_search"));
            } else if (!currentTurn) {
                parts.add(placeholder(part, viewerAvailable
                        ? "sent in an earlier turn, not resent; call " + VIEWER_TOOL + "(\""
                                + part.name() + "\") to see it again"
                        : "sent in an earlier turn, not resent"));
            } else if (part.sizeBytes() > MAX_INLINE_BYTES) {
                parts.add(placeholder(part, "too large to send inline (limit "
                        + humanSize(MAX_INLINE_BYTES) + ")"));
            } else {
                LlmContent inline = inline(part);
                parts.add(inline != null ? inline : placeholder(part, "no longer available"));
            }
        }
        return parts;
    }

    /** The media as a content block, or null when the store no longer has it. */
    private LlmContent inline(ContentPart.Media part) {
        PartContentReader.Content content = partContentReader.read(FileId.of(part.fileId())).orElse(null);
        if (content == null || content.bytes() == null) {
            log.warn("Media part {} ({}) is not readable — sending a placeholder", part.fileId(), part.name());
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(content.bytes());
        String mediaType = MediaTypes.normalize(
                part.mediaType() != null ? part.mediaType() : content.mediaType());
        return part instanceof ContentPart.Image
                ? new LlmContent.Image(base64, mediaType)
                : new LlmContent.Document(base64, mediaType, part.name());
    }

    /**
     * What stands in for a media part the model does not get: what it is
     * and why it is not here, marked as a system note so the model does not
     * take it for something the user typed.
     */
    static LlmContent.Text placeholder(ContentPart.Media part, String reason) {
        return new LlmContent.Text("[System note — " + describe(part) + " — " + reason + ".]");
    }

    /** "image attached: photo.png (image/png, 240 KB)" — the same line the working-memory view shows. */
    static String describe(ContentPart.Media part) {
        String kind = part instanceof ContentPart.Image ? "image" : "document";
        StringBuilder out = new StringBuilder(kind).append(" attached: ").append(part.name());
        List<String> details = new ArrayList<>();
        if (part.mediaType() != null) details.add(part.mediaType());
        if (part.sizeBytes() > 0) details.add(humanSize(part.sizeBytes()));
        if (!details.isEmpty()) out.append(" (").append(String.join(", ", details)).append(')');
        return out.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes + 512) / 1024 + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void mapToolCall(Message m, List<LlmMessage> result) {
        try {
            Map<String, Object> payload = MAPPER.readValue(m.content(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawCalls = (List<Map<String, Object>>) payload.get("toolCalls");
            if (rawCalls != null) {
                List<ToolCall> toolCalls = rawCalls.stream()
                        .map(tc -> new ToolCall(
                                (String) tc.get("id"),
                                (String) tc.get("name"),
                                tc.containsKey("arguments")
                                        ? (Map<String, Object>) tc.get("arguments")
                                        : Map.of(),
                                (String) tc.get("thoughtSignature")))
                        .toList();
                // Anthropic thinking blocks (with signatures) that preceded the
                // tool calls — replayed before them so the next request validates.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawThinking =
                        (List<Map<String, Object>>) payload.get("thinkingBlocks");
                List<ThinkingBlock> thinkingBlocks = rawThinking == null ? null
                        : rawThinking.stream()
                            .map(tb -> new ThinkingBlock(
                                    (String) tb.get("type"),
                                    (String) tb.get("text"),
                                    (String) tb.get("data"),
                                    (String) tb.get("signature")))
                            .toList();
                result.add(LlmMessage.assistantWithToolCalls(thinkingBlocks, toolCalls));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialise TOOL_CALL message seq={}: {}", m.sequenceNum(), e.getMessage());
        }
    }

    private void mapToolResult(Message m, List<LlmMessage> result, ContextTokenBudget budget) {
        try {
            Map<String, Object> payload = MAPPER.readValue(m.content(), new TypeReference<>() {});
            String toolCallId = (String) payload.get("toolCallId");
            String toolResult  = (String) payload.get("result");
            if (toolCallId != null && toolResult != null) {
                String contextResult;
                if (m.compressed() && m.compressedContent() != null) {
                    int originalTokens = m.tokenCount() != null ? m.tokenCount() : 0;
                    contextResult = "[Tool result truncated for context (~" + originalTokens
                            + " tokens → summary). The original output is no longer available; "
                            + "do NOT invent values that aren't in the summary below. "
                            + "If the user needs detail not present here, re-run the tool.]\n"
                            + m.compressedContent();
                } else {
                    contextResult = toolResult;
                }
                contextResult = guard(contextResult, budget, m.sequenceNum(), "TOOL_RESULT");
                result.add(LlmMessage.tool(toolCallId, contextResult));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialise TOOL_RESULT message seq={}: {}", m.sequenceNum(), e.getMessage());
        }
    }

    /**
     * Truncates {@code text} to {@link ContextTokenBudget#maxMessageTokens()} if it exceeds
     * the limit. Uses binary search on character offsets for efficiency.
     */
    private String guard(String text, ContextTokenBudget budget, int seqNum, String type) {
        int limit = budget.maxMessageTokens();
        if (budget.counter().countText(text) <= limit) return text;

        int lo = 0, hi = text.length();
        while (lo < hi - 1) {
            int mid = (lo + hi) / 2;
            if (budget.counter().countText(text.substring(0, mid)) <= limit) lo = mid;
            else hi = mid;
        }
        log.warn("MessageMapper: seq={} type={} truncated to ~{} tokens", seqNum, type, limit);
        return text.substring(0, lo) + TRUNCATION_MARKER;
    }
}
