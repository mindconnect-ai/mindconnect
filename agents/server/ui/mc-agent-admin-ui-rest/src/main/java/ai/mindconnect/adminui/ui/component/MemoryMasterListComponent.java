package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.ui.model.UiList;

import ai.mindconnect.agent.runtime.api.domain.SessionId;

import static ai.mindconnect.chatui.ui.SessionUiCommons.previewOf;

/**
 * Master list of the working-memory inspector: the instructions, the
 * summary where there is one, and every message of the session with
 * whether it goes to the model — each as a clickable row that triggers a
 * detail-pane replace via a GET on the {@code ?seq=…} endpoint. A message
 * is addressed by its place in the session, counted from one.
 */
public final class MemoryMasterListComponent implements UiComponent {

    /** Pseudo-seq used as the master-list id for the system prompt row. */
    public static final int SYS_PROMPT_SEQ = -1;
    /** Pseudo-seq of the summary row. */
    public static final int SUMMARY_SEQ = -2;

    private final SessionId sessionId;
    private final WorkingMemory memory;

    public MemoryMasterListComponent(SessionId sessionId, WorkingMemory memory) {
        this.sessionId = sessionId;
        this.memory = memory;
    }

    @Override
    public String id() {
        return "mem-list-" + sessionId.value();
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
                        "SYSTEM · " + memory.instructionTokens() + " tok")
                .description("System prompt")
                .dispatch("GET", itemHref(SYS_PROMPT_SEQ)));
        memory.summary().ifPresent(summary -> list.item(UiList.Item.of("mem-item-summary", "SUMMARY")
                .description(previewOf(summary))
                .dispatch("GET", itemHref(SUMMARY_SEQ))));

        for (int i = 0; i < memory.messages().size(); i++) {
            WorkingMemory.Entry entry = memory.messages().get(i);
            int seq = i + 1;
            list.item(UiList.Item.of("mem-item-" + seq, label(seq, entry))
                    .description(previewOf(entry.sentAs() != null ? entry.sentAs() : entry.message().text()))
                    .dispatch("GET", itemHref(seq)));
        }
        return list;
    }

    /** {@code #3 · TOOL_RESULT · 120 tok · (compressed)}; a message that is not sent says so. */
    static String label(int seq, WorkingMemory.Entry entry) {
        return "#" + seq + " · " + entry.message().type() + " · " + entry.tokens() + " tok"
                + (entry.sentAs() != null ? " · (compressed)" : "")
                + (entry.sent() ? "" : " · (not sent)");
    }

    private String itemHref(int seq) {
        return "/admin/api/sessions/" + sessionId.value() + "/memory?seq=" + seq;
    }
}
