package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.ui.ext.jsonviewer.UiJsonViewer;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiSection;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER;
import static ai.mindconnect.chatui.ui.SessionUiCommons.codeBlock;

/**
 * One LLM-call roundtrip: a line naming the ids it belongs to, then a
 * three-tab card, in the order the call happened:
 * <ul>
 *   <li><b>Request</b> — the verbatim provider request via the
 *       json-viewer.</li>
 *   <li><b>Response</b> — prose first, then per-tool-call args
 *       (json-viewer) followed by the matching persisted tool-result
 *       block (collapsible). Errors short-circuit to a code-fenced
 *       error body.</li>
 *   <li><b>Raw SSE</b> — the captured event stream as a markdown code
 *       block.</li>
 * </ul>
 */
public final class RoundtripCardComponent implements UiComponent {

    /** Soft cap for inline tool-result rendering — anything larger gets trimmed
     *  with a "[trimmed N chars]" marker. */
    private static final int TOOL_RESULT_MAX_CHARS = 5_000;

    private final LlmCallTrace trace;
    private final Map<String, Message> resultsByCallId;

    public RoundtripCardComponent(LlmCallTrace trace, Map<String, Message> resultsByCallId) {
        this.trace = trace;
        this.resultsByCallId = resultsByCallId;
    }

    @Override
    public String id() {
        return "trace-r-" + (trace.id() == null ? "" : trace.id().value());
    }

    /** One line saying who called what, when, how long, how many tokens and how it ended. */
    public String title() {
        int depth = trace.context() != null ? trace.context().depth() : 0;
        String agentLabel = trace.context() != null && trace.context().agentName() != null
                ? trace.context().agentName() : "agent";
        return TraceTableComponent.TIME_FMT.format(trace.startedAt())
                + (depth > 0 ? " · ↳ " + agentLabel : "")
                + " · " + trace.modelName()
                + " · " + trace.durationMs() + "ms"
                + " · " + trace.promptTokens() + "+" + trace.completionTokens() + " tok"
                + (trace.finishReason() != null ? " · " + trace.finishReason() : "")
                + (trace.errorStatus() != null ? " · ✗ HTTP " + trace.errorStatus() : "");
    }

    @Override
    public UiSection render() {
        UiNode requestNode = UiJsonViewer.of(id() + "-req-json", trace.requestJson())
                .expandLevel(1);
        UiNode responseNode = buildResponseTab();
        UiNode rawNode;
        if (trace.responseEvents() == null || trace.responseEvents().isEmpty()) {
            rawNode = UiMarkdown.of(id() + "-raw-md", "_(no SSE events)_");
        } else {
            rawNode = UiMarkdown.of(id() + "-raw-md",
                    codeBlock(String.join("\n\n", trace.responseEvents()), null));
        }

        var tabs = UiSection.of(id() + "-tabs", null)
                .section(id() + "-req", "Request", requestNode)
                .section(id() + "-res", "Response", responseNode)
                .section(id() + "-raw", "Raw SSE", rawNode);
        return UiSection.of(id(), null)
                .section(id() + "-ctx", null, UiMarkdown.of(id() + "-ctx-md", contextLine()))
                .section(id() + "-body", null, tabs);
    }

    /**
     * The ids that place the call — turn, parent turn, session, trace —
     * in full, for matching against log lines and files on disk. The
     * dialog title has the summary; this has what the summary leaves out.
     */
    String contextLine() {
        var ctx = trace.context();
        var sb = new StringBuilder();
        if (ctx != null) {
            if (ctx.agentName() != null) sb.append("**").append(ctx.agentName()).append("** · ");
            if (ctx.turnId() != null) sb.append("turn `").append(ctx.turnId().value()).append("` · ");
            if (ctx.parentTurnId() != null) sb.append("parent turn `").append(ctx.parentTurnId().value()).append("` · ");
            if (ctx.sessionId() != null) sb.append("session `").append(ctx.sessionId().value()).append("` · ");
        }
        if (trace.llmConfigName() != null) sb.append("config `").append(trace.llmConfigName()).append("` · ");
        if (trace.id() != null) sb.append("trace `").append(trace.id().value()).append("`");
        return sb.toString().replaceAll(" · $", "");
    }

    /**
     * Indexes persisted TOOL_RESULT messages by their {@code toolCallId}
     * so the response tab can show each tool call together with its own
     * result block.
     */
    public static Map<String, Message> indexToolResults(List<Message> history) {
        Map<String, Message> out = new HashMap<>();
        if (history == null) return out;
        for (Message m : history) {
            if (m.type() != MessageType.TOOL_RESULT) continue;
            try {
                JsonNode node = MAPPER.readTree(m.content());
                String id = node.path("toolCallId").asText("");
                if (!id.isBlank()) out.put(id, m);
            } catch (Exception ignored) { /* skip malformed entries */ }
        }
        return out;
    }

    /**
     * Builds the Response tab: prose first (if any), then one folded
     * json-viewer per tool call followed by the persisted tool-result
     * block (with execution duration). Errors short-circuit to a
     * markdown body.
     */
    private UiNode buildResponseTab() {
        if (trace.errorBody() != null) {
            return UiMarkdown.of(id() + "-res-err",
                    codeBlock("HTTP " + trace.errorStatus() + "\n\n" + trace.errorBody(), null));
        }
        var response = trace.response();
        if (response == null) {
            return UiMarkdown.of(id() + "-res-empty", "_(no response captured)_");
        }
        var stack = UiSection.of(id() + "-res-stack", null);
        if (response.text() != null && !response.text().isBlank()) {
            stack.section(id() + "-res-text", null,
                    UiMarkdown.of(id() + "-res-text-md", response.text()));
        }
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            stack.section(id() + "-res-tc-header", null,
                    UiMarkdown.of(id() + "-res-tc-md",
                            "**Tool calls (" + response.toolCalls().size() + "):**"));
            int n = 0;
            for (var tc : response.toolCalls()) {
                String tcId = id() + "-res-tc-" + (n++);
                stack.section(tcId + "-name", null,
                        UiMarkdown.of(tcId + "-name-md",
                                "`" + tc.name() + "` (id `" + tc.id() + "`)"));
                String argsJson;
                try {
                    argsJson = MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(tc.arguments());
                } catch (Exception e) {
                    argsJson = "{}";
                }
                stack.section(tcId + "-args", null,
                        UiJsonViewer.of(tcId + "-args-json", argsJson)
                                .expandLevel(2));

                appendToolResult(stack, tcId, resultsByCallId.get(tc.id()));
            }
        }
        if (stack.getSections().isEmpty()) {
            return UiMarkdown.of(id() + "-res-empty2", "_(empty response)_");
        }
        return stack;
    }

    /**
     * Appends a tool-result block as a collapsible section: summary
     * "Tool result · {ms}ms[ · N chars total]" with the body holding
     * the actual result content. Routes JSON-shaped results through
     * the json-viewer, everything else through a markdown code-fence.
     * Long results are trimmed to {@link #TOOL_RESULT_MAX_CHARS} with
     * a marker so the dialog stays scrollable. Collapsed by default —
     * operators scanning a call don't need every result body open at once.
     */
    private void appendToolResult(UiSection stack, String tcId, Message resultMsg) {
        if (resultMsg == null) {
            stack.section(tcId + "-result-missing", null,
                    UiMarkdown.of(tcId + "-result-missing-md",
                            "_(no recorded tool result for this call)_"));
            return;
        }
        String resultText;
        try {
            JsonNode node = MAPPER.readTree(resultMsg.content());
            resultText = node.path("result").asText("");
        } catch (Exception e) {
            resultText = resultMsg.content() != null ? resultMsg.content() : "";
        }
        boolean trimmed = resultText.length() > TOOL_RESULT_MAX_CHARS;
        int originalLength = resultText.length();
        if (trimmed) {
            resultText = resultText.substring(0, TOOL_RESULT_MAX_CHARS)
                    + "\n\n[trimmed " + (originalLength - TOOL_RESULT_MAX_CHARS) + " chars]";
        }

        Long durationMs = resultMsg.durationMs();
        String summary = "Tool result"
                + (durationMs != null ? " · " + durationMs + "ms" : "")
                + (trimmed ? " · " + originalLength + " chars total" : "");

        // Heuristic: looks-like-JSON → folded viewer, else plain text fence.
        String stripped = resultText.stripLeading();
        String firstChar = stripped.isEmpty() ? "" : stripped.substring(0, 1);
        UiNode body;
        if ("{".equals(firstChar) || "[".equals(firstChar)) {
            body = UiJsonViewer.of(tcId + "-result-json", resultText)
                    .expandLevel(2);
        } else {
            body = UiMarkdown.of(tcId + "-result-md", codeBlock(resultText, null));
        }

        var collapsible = UiSection.of(tcId + "-result", null)
                .section(tcId + "-result-body", null, body)
                .collapsible(summary, false);
        stack.section(tcId + "-result-wrap", null, collapsible);
    }
}
