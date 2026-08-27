package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.port.out.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders persisted {@link ConversationSummary}s as
 * {@link WorkingMemory.WorkingMemoryMessage} pseudo-entries so the admin
 * UI's working-memory view can show the same summaries the LLM actually
 * receives — both
 * {@link ai.mindconnect.agent.memory.strategy.AutoCompactStrategy} and
 * {@link ai.mindconnect.agent.memory.strategy.SummarizingWindowStrategy}
 * inject summaries into the LLM window but used to omit them from the
 * window-message list, leading to "Compress shrinks the visible window
 * to nothing" confusion.
 *
 * <p>Pseudo-{@code sequenceNum}s use the negative range starting at
 * {@link #FIRST_PSEUDO_SEQ} so they don't collide with real message
 * sequence numbers (1, 2, …) or with the system-prompt pseudo-row at
 * {@code -1}. They stay stable across renders within a single load
 * because we assign them in summary list order.
 */
public final class SummaryWindowMessages {

    /** First negative pseudo sequence for summary pseudo-messages. */
    public static final int FIRST_PSEUDO_SEQ = -100;

    /** {@code type} value used for summary pseudo-messages. */
    public static final String TYPE = "SUMMARY";

    private SummaryWindowMessages() {}

    /**
     * Returns one {@link WorkingMemory.WorkingMemoryMessage} per summary,
     * in the same order as {@code summaries}. Each row carries the
     * summary text as both {@code content} and {@code compressedContent}
     * with {@code compressed=true}, so existing UI code that branches on
     * compression status renders the summary correctly.
     */
    public static List<WorkingMemory.WorkingMemoryMessage> render(
            List<ConversationSummary> summaries, TokenCounter counter) {
        List<WorkingMemory.WorkingMemoryMessage> out = new ArrayList<>(summaries.size());
        int pseudo = FIRST_PSEUDO_SEQ;
        for (ConversationSummary s : summaries) {
            int tokens = counter != null ? counter.countText(s.content()) : 0;
            // Label includes the covered seq range so operators can correlate
            // back to the (now compressed) original messages.
            String label = "summary of seq " + s.fromSequenceNum() + "–" + s.toSequenceNum()
                    + " (" + s.messageCount() + " message" + (s.messageCount() == 1 ? "" : "s") + ")";
            out.add(new WorkingMemory.WorkingMemoryMessage(
                    TYPE,
                    "USER",                       // summaries land in the LLM as a user-role message
                    pseudo--,
                    s.createdAt().toEpochMilli(),
                    tokens,
                    label,                        // content shown in the master-list preview
                    true,                         // compressed = true so UI styling matches
                    s.content()                   // full summary text in compressedContent
            ));
        }
        return out;
    }
}
