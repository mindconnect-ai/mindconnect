package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.ui.ext.jsonviewer.UiJsonViewer;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiSection;

import static ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER;

/**
 * Detail pane of the working-memory inspector — two tabs:
 * <ul>
 *   <li><b>Raw</b> — the underlying record JSON via the json-viewer.</li>
 *   <li><b>LLM View</b> — what the model actually sees: compressed
 *       stub if compression has run on the message, otherwise the
 *       original content. TOOL_CALL / TOOL_RESULT bodies (themselves
 *       JSON) route through the json-viewer too; everything else
 *       renders in a fenced markdown code block.</li>
 * </ul>
 *
 * <p>The {@code seq} parameter selects the entry. {@link
 * MemoryMasterListComponent#SYS_PROMPT_SEQ} picks the system prompt;
 * any other value selects the windowed message with that
 * {@code sequenceNum}.
 *
 * <p>DOM ids stay stable across replacements so the JS tab handler
 * keeps working after detail-pane swaps.
 */
public final class MemoryDetailSectionComponent implements UiComponent {

    public static final String DETAIL_ID = "mem-detail";
    private static final String RAW_ID = "mem-detail-raw";
    private static final String LLM_ID = "mem-detail-llm";

    private final WorkingMemory memory;
    private final int seq;

    public MemoryDetailSectionComponent(WorkingMemory memory, int seq) {
        this.memory = memory;
        this.seq = seq;
    }

    @Override
    public String id() {
        return DETAIL_ID;
    }

    @Override
    public UiSection render() {
        String rawJson;
        String llmText;          // null if the LLM-view should be JSON instead
        String llmJsonContent;   // non-null when the LLM-view content is JSON
        String title;

        if (seq == MemoryMasterListComponent.SYS_PROMPT_SEQ) {
            // System prompt: raw view is the record-shape JSON; LLM view
            // is the literal prompt text the model will see at the top.
            try {
                rawJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        new SystemPromptView(memory.systemPrompt(), memory.systemPromptTokens()));
            } catch (Exception e) {
                rawJson = "{\"error\":\"" + e.getMessage() + "\"}";
            }
            llmText = memory.systemPrompt();
            llmJsonContent = null;
            title = "System prompt · " + memory.systemPromptTokens() + " tok";
        } else {
            WorkingMemory.WorkingMemoryMessage msg = memory.messages().stream()
                    .filter(m -> m.sequenceNum() == seq).findFirst().orElse(null);
            if (msg == null) {
                rawJson = "{\"error\":\"message not found in window\",\"sequenceNum\":" + seq + "}";
                llmText = "(not in window)";
                llmJsonContent = null;
                title = "#" + seq + " — not found";
            } else {
                try {
                    rawJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msg);
                } catch (Exception e) {
                    rawJson = "{\"error\":\"" + e.getMessage() + "\"}";
                }
                String content = msg.compressed() ? msg.compressedContent() : msg.content();
                if (isJsonContentType(msg.type())) {
                    llmText = null;
                    llmJsonContent = content;
                } else {
                    llmText = content;
                    llmJsonContent = null;
                }
                title = "#" + msg.sequenceNum() + " · " + msg.role() + " · " + msg.type()
                        + " · " + msg.tokens() + " tok"
                        + (msg.compressed() ? " · (compressed)" : "");
            }
        }

        UiNode rawNode = UiJsonViewer.of(RAW_ID + "-json", rawJson)
                .expandLevel(2)
                .theme("default-light");
        UiNode llmNode;
        if (llmJsonContent != null) {
            llmNode = UiJsonViewer.of(LLM_ID + "-json", llmJsonContent)
                    .expandLevel(2)
                    .theme("default-light");
        } else {
            llmNode = UiMarkdown.of(LLM_ID + "-md", "```\n" + nullSafe(llmText) + "\n```");
        }

        return UiSection.of(DETAIL_ID, title)
                .section(RAW_ID, "Raw", rawNode)
                .section(LLM_ID, "LLM View", llmNode);
    }

    /**
     * Whether a {@link WorkingMemory.WorkingMemoryMessage#type()} value
     * holds JSON-shaped content (so it deserves a folded viewer rather
     * than a plain code-fence).
     */
    private static boolean isJsonContentType(String type) {
        return "TOOL_CALL".equals(type) || "TOOL_RESULT".equals(type);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    /** Minimal carrier for serialising the system-prompt row's raw view. */
    private record SystemPromptView(String content, int tokens) {}
}
