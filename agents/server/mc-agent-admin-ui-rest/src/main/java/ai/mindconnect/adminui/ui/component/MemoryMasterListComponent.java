package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.ui.model.UiList;

import java.util.UUID;

import static ai.mindconnect.chatui.ui.SessionUiCommons.previewOf;

/**
 * Master list of the working-memory inspector: every entry in the
 * upcoming LLM call window — the system prompt plus all windowed
 * messages — each as a clickable row that triggers a detail-pane
 * replace via a GET on the {@code ?seq=…} endpoint.
 */
public final class MemoryMasterListComponent implements UiComponent {

    /** Pseudo-seq used as the master-list id for the system prompt row. */
    public static final int SYS_PROMPT_SEQ = -1;

    private final UUID sessionId;
    private final WorkingMemory memory;

    public MemoryMasterListComponent(UUID sessionId, WorkingMemory memory) {
        this.sessionId = sessionId;
        this.memory = memory;
    }

    @Override
    public String id() {
        return "mem-list-" + sessionId;
    }

    @Override
    public UiList render() {
        var list = UiList.of(id(), "Window (" + memory.totalTokens() + " tok)");
        // CSS class makes the list a fixed-height scroll container (see index.html).
        list.withCssClass("memory-master");

        // System prompt first — pseudo-seq SYS_PROMPT_SEQ. Clicking the
        // label itself dispatches the detail-replace request, no
        // separate button.
        list.item(UiList.Item.of("mem-item-sys",
                        "SYSTEM · " + memory.systemPromptTokens() + " tok")
                .description("System prompt")
                .dispatch("GET", itemHref(SYS_PROMPT_SEQ)));

        for (var m : memory.messages()) {
            String label = "#" + m.sequenceNum() + " · " + m.role() + " · " + m.type()
                    + " · " + m.tokens() + " tok"
                    + (m.compressed() ? " · (compressed)" : "");
            String preview = previewOf(m.compressed() ? m.compressedContent() : m.content());
            list.item(UiList.Item.of("mem-item-" + m.sequenceNum(), label)
                    .description(preview)
                    .dispatch("GET", itemHref(m.sequenceNum())));
        }
        return list;
    }

    private String itemHref(int seq) {
        return "/admin/api/sessions/" + sessionId + "/memory?seq=" + seq;
    }
}
