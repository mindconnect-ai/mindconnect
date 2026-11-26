package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;

import java.util.Map;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.ui.ext.jsonviewer.UiJsonViewer;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiSection;
import com.fasterxml.jackson.databind.ObjectMapper;


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
 * MemoryMasterListComponent#SYS_PROMPT_SEQ} picks the system prompt,
 * {@link MemoryMasterListComponent#SUMMARY_SEQ} the summary; any other
 * value the message at that place in the session, counted from one.
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
    private final ObjectMapper json;

    public MemoryDetailSectionComponent(WorkingMemory memory, int seq, ObjectMapper json) {
        this.memory = memory;
        this.seq = seq;
        this.json = json;
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
            // Raw view is the record-shape JSON; LLM view is the literal
            // text the model will see at the top.
            rawJson = json(new SystemPromptView(memory.instructions(), memory.instructionTokens()));
            llmText = memory.instructions();
            llmJsonContent = null;
            title = "System prompt · " + memory.instructionTokens() + " tok";
        } else if (seq == MemoryMasterListComponent.SUMMARY_SEQ) {
            llmText = memory.summary().orElse("(no summary)");
            rawJson = json(Map.of("summary", llmText));
            llmJsonContent = null;
            title = "Summary of what is not sent any more";
        } else if (seq < 1 || seq > memory.messages().size()) {
            rawJson = "{\"error\":\"no such message\",\"sequenceNum\":" + seq + "}";
            llmText = "(no such message)";
            llmJsonContent = null;
            title = "#" + seq + " — not found";
        } else {
            WorkingMemory.Entry entry = memory.messages().get(seq - 1);
            TurnMessage message = entry.message();
            rawJson = json(message);
            String content = !entry.sent() ? "(not sent to the model)"
                    : entry.sentAs() != null ? entry.sentAs() : message.text();
            boolean ofATool = message.type() == TurnMessage.Type.TOOL_CALL;
            // A tool call has no text of its own: the model reads the calls.
            llmJsonContent = entry.sent() && ofATool ? json(message.toolCalls()) : null;
            llmText = llmJsonContent == null ? content : null;
            title = MemoryMasterListComponent.label(seq, entry);
        }

        UiNode rawNode = UiJsonViewer.of(RAW_ID + "-json", rawJson)
                .expandLevel(2);
        UiNode llmNode;
        if (llmJsonContent != null) {
            llmNode = UiJsonViewer.of(LLM_ID + "-json", llmJsonContent)
                    .expandLevel(2);
        } else {
            llmNode = UiMarkdown.of(LLM_ID + "-md", "```\n" + nullSafe(llmText) + "\n```");
        }

        return UiSection.of(DETAIL_ID, title)
                .section(RAW_ID, "Raw", rawNode)
                .section(LLM_ID, "LLM View", llmNode);
    }

    private String json(Object value) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    /** Minimal carrier for serialising the system-prompt row's raw view. */
    private record SystemPromptView(String content, int tokens) {}
}
