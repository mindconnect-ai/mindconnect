package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.ext.jsonviewer.UiJsonViewer;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiSection;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.MAPPER;
import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.codeBlock;

/**
 * One LLM-call roundtrip rendered as a three-tab card:
 * <ul>
 *   <li><b>Response</b> — prose first, then per-tool-call args
 *       (json-viewer) followed by the matching persisted tool-result
 *       block (collapsible). Errors short-circuit to a code-fenced
 *       error body.</li>
 *   <li><b>Request</b> — the verbatim provider request via the
 *       json-viewer.</li>
 *   <li><b>Raw SSE</b> — the captured event stream as a markdown code
 *       block.</li>
 * </ul>
 *
 * <p>Depth (sub-agent nesting) is rendered as a {@code trace-depth-N}
 * CSS class on the card so visual indentation matches the nesting
 * level.
 */
public final class RoundtripCardComponent implements UiComponent {

    /** Soft cap for inline tool-result rendering — anything larger gets trimmed
     *  with a "[trimmed N chars]" marker. */
    private static final int TOOL_RESULT_MAX_CHARS = 5_000;

    private final int globalIdx;
    private final LlmCallTrace trace;
    private final Map<String, Message> resultsByCallId;

    public RoundtripCardComponent(int globalIdx, LlmCallTrace trace,
                                   Map<String, Message> resultsByCallId) {
        this.globalIdx = globalIdx;
        this.trace = trace;
        this.resultsByCallId = resultsByCallId;
    }

    @Override
    public String id() {
        return "trace-r-" + trace.id();
    }

    @Override
    public UiSection render() {
        int depth = trace.context() != null ? trace.context().depth() : 0;
        String agentLabel = trace.context() != null && trace.context().agentName() != null
                ? trace.context().agentName() : "agent";

        String header = "R" + globalIdx + " · "
                + ai.mindconnect.adminui.assembler.session.SessionUiCommons.DT_FMT.format(trace.startedAt())
                + (depth > 0 ? " · ↳ " + agentLabel : "")
                + " · " + trace.modelName()
                + " · " + trace.durationMs() + "ms"
                + " · " + trace.promptTokens() + "+" + trace.completionTokens() + " tok"
                + (trace.finishReason() != null ? " · " + trace.finishReason() : "")
                + (trace.errorStatus() != null ? " · ✗ HTTP " + trace.errorStatus() : "");

        UiNode requestNode = UiJsonViewer.of(id() + "-req-json", trace.requestJson())
                .expandLevel(1)
                .theme("default-light");
        UiNode responseNode = buildResponseTab();
        UiNode rawNode;
        if (trace.responseEvents() == null || trace.responseEvents().isEmpty()) {
            rawNode = UiMarkdown.of(id() + "-raw-md", "_(no SSE events)_");
        } else {
            rawNode = UiMarkdown.of(id() + "-raw-md",
                    codeBlock(String.join("\n\n", trace.responseEvents()), null));
        }

        var rt = UiSection.of(id(), header)
                .section(id() + "-res", "Response", responseNode)
                .section(id() + "-req", "Request", requestNode)
                .section(id() + "-raw", "Raw SSE", rawNode);
        if (depth > 0) {
            rt.<UiSection>withCssClass("trace-depth-" + Math.min(depth, 5));
        }
        return rt;
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
                                .expandLevel(2)
                                .theme("default-light"));

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
     * a marker so the detail pane stays scrollable. Collapsed by
     * default — operators scanning a turn don't need every result
     * body open at once.
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
                    .expandLevel(2)
                    .theme("default-light");
        } else {
            body = UiMarkdown.of(tcId + "-result-md", codeBlock(resultText, null));
        }

        var collapsible = UiSection.of(tcId + "-result", null)
                .section(tcId + "-result-body", null, body)
                .collapsible(summary, false);
        stack.section(tcId + "-result-wrap", null, collapsible);
    }
}
