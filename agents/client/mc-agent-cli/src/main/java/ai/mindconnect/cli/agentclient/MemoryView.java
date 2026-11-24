package ai.mindconnect.cli.agentclient;

import java.util.List;

/**
 * What the model would be sent for a session right now, as the memory
 * commands print it. The shape is the one the server answers with under
 * {@code /api/sessions/{id}/memory}, so a remote answer is read as it is.
 *
 * @param contextWindowTokens the model's window; {@code null} where it is not known
 */
public record MemoryView(String systemPrompt, int systemPromptTokens, List<Entry> messages, int totalTokens,
                         Integer contextWindowTokens) {

    public MemoryView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * One message as it is sent.
     *
     * @param compressedContent what is sent in place of {@code content}; {@code null} when it is sent as said
     */
    public record Entry(String type, String role, int sequenceNum, int tokens, String content, boolean compressed,
                        String compressedContent) { }
}
