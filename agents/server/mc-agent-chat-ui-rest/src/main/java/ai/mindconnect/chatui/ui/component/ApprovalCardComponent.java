package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;

import java.util.UUID;

/**
 * The card that asks a human whether a tool may run.
 *
 * <p>One shape for every open question — a root tool call or a sub-agent's —
 * because the answer is a plain dispatch identified by the call id alone and
 * the {@code ToolApprovalStore} knows the rest.
 *
 * <p>Static by nature: a card is built from a request, not from a session's
 * state, and both the historic render and the live stream need the same one.
 */
public final class ApprovalCardComponent {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ApprovalCardComponent() {
    }

    /** The tool name and pretty-printed arguments out of a request's content JSON. */
    public record ApprovalCall(String toolName, String argsJson) { }

    public static ApprovalCall parseApprovalContent(String content) {
        String toolName = "?";
        String argsJson = "{}";
        try {
            var node = MAPPER.readTree(content);
            if (node.hasNonNull("name")) toolName = node.get("name").asText();
            if (node.has("arguments")) {
                argsJson = MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(node.get("arguments"));
            }
        } catch (Exception ignored) {
            // unreadable content — the card still renders with the raw call id
        }
        return new ApprovalCall(toolName, argsJson);
    }

    /**
     * The approval card — ONE shape for every open question (root tool or
     * sub-agent alike): the answer is a plain dispatch identified by callId
     * alone, the ToolApprovalStore knows the rest. The turn never ended, so
     * there is no stream to start — the ORIGINAL stream carries the
     * continuation once the parked tool task is woken.
     */
    public static UiList.Item approvalCard(UUID sessionId, String callId,
                                           String toolName, String argsJson, String time) {
        String base = "/chat/api/sessions/" + sessionId + "/approval"
                + "?callId=" + java.net.URLEncoder.encode(callId, java.nio.charset.StandardCharsets.UTF_8);

        // Buttons live IN the card body (a plain stack renders them as real,
        // always-visible buttons — item.action() would make them hover icons);
        // the arguments fold away behind their own collapsible row.
        var intro = UiMarkdown.of("approval-intro-" + callId,
                "The agent wants to run **`" + toolName + "`**.");
        var params = ai.mindconnect.ui.model.UiList.of("approval-params-list-" + callId, null);
        params.item(UiList.Item.of("approval-params-" + callId, "Parameters")
                .collapsible("show", false)
                .content(UiMarkdown.of("approval-args-" + callId,
                        "```json\n" + argsJson + "\n```")));
        var buttons = ai.mindconnect.ui.model.UiStack.of(
                        UiAction.danger("approval-deny-" + callId, "Deny")
                                .dispatch("POST", base + "&approved=false&scope=once"),
                        UiAction.secondary("approval-once-" + callId, "Allow once")
                                .dispatch("POST", base + "&approved=true&scope=once"),
                        UiAction.primary("approval-session-" + callId, "Allow for this session")
                                .dispatch("POST", base + "&approved=true&scope=session"))
                .direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL)
                .gap(8);
        var bodyStack = ai.mindconnect.ui.model.UiStack.of(intro, params, buttons);
        bodyStack.setId("approval-body-" + callId);
        bodyStack.withCssClass("approval-request");
        return UiList.Item.of("approval-" + callId, "Approval required  [" + time + "]")
                .content(bodyStack);
    }}
