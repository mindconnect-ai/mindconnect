package ai.mindconnect.agent.runtime.capabilities.attachment;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * Lets the model ask for an attached image or PDF again. The tool only says
 * whether there is such a file: a tool's result is text for every provider,
 * so the file itself follows the result in the next model call, where the
 * {@link AttachmentPlugin} adds it.
 */
final class ViewAttachmentTool implements Tool {

    static final String NAME = AttachmentPlugin.VIEW_TOOL;
    static final ToolDefinition DEFINITION = new ToolDefinition(NAME, """
            Show an image or PDF attached to this chat again.
            An attached image or PDF is shown to you with the message it came with, once. \
            In later turns a note names it instead. Call this tool with the file's name when you need \
            to look at it again — it is shown to you right after this tool's result. Files that are \
            not images or PDFs are read with vector_search, not with this tool.""",
            Map.of("type", "object",
                    "properties", Map.of("name", Map.of("type", "string",
                            "description", "The attached file's name, as shown in the chat's attachments")),
                    "required", List.of("name")));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        String name = String.valueOf(call.arguments().getOrDefault("name", "")).trim();
        List<Attachment> attached = Attachment.of(session);
        Attachment file = attached.stream().filter(one -> one.name().equals(name)).findFirst().orElse(null);
        if (file == null) {
            return ToolOutput.failed(attached.isEmpty()
                    ? "No file named '" + name + "' — nothing is attached to this chat."
                    : "No file named '" + name + "'. Attached to this chat: "
                            + String.join(", ", attached.stream().map(Attachment::name).toList()) + ".");
        }
        if (!file.sendableAsPart()) {
            return ToolOutput.failed("'" + name + "' is not an image or PDF — read its content with vector_search.");
        }
        return ToolOutput.ok("Showing '" + name + "' — the " + file.kind() + " follows this result.");
    }
}
