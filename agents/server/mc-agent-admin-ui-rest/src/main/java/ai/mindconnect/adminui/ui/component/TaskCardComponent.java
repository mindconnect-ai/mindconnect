package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.assembler.session.SessionUiCommons;
import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

/**
 * One tool-call or sub-agent-call card rendered as a collapsible
 * {@code <details>} list item.
 *
 * <p>A card has three lifecycle states; this component is constructed
 * directly in the state it should render in (no internal mutation):
 * <ul>
 *   <li><b>running</b> — header shows {@code ⏳ name — running…}, body
 *       shows the input arguments, card is open by default so the user
 *       can see what's executing.</li>
 *   <li><b>done</b> — header shows {@code ✓ name (duration ms)}, body
 *       shows input + output, card is collapsed.</li>
 *   <li><b>failed</b> — header shows {@code ✗ name (duration ms)},
 *       body shows input + the error message, card is collapsed.</li>
 * </ul>
 *
 * <p><b>Sub-agent cards build a real tree.</b> Instead of indenting
 * nested tool calls with whitespace, a sub-agent card's body is a
 * {@link UiStack} that contains a <em>nested</em> {@link UiList} with a
 * stable id ({@link #childListId(String)}). The sub-agent's own tool-call
 * cards are appended into that nested list, so the DOM hierarchy mirrors
 * the agent call hierarchy. A patch targeting the nested list's id appends
 * straight into its {@code <ul>}; a patch targeting a child card's id
 * morphs that {@code <li>} in place — both work regardless of nesting
 * depth because the SPA patch dispatcher resolves targets by element id.
 *
 * <p>Cards live as a single {@code <li>} inside their list's {@code <ul>}.
 * The rendered {@link UiList} wraps a single {@link UiList.Item} so the JS
 * append-into-list path treats it as a one-item insert.
 */
public final class TaskCardComponent implements UiComponent {

    private final String nodeId;
    private final String headerLabel;
    /** Pre-rendered body node. Plain tool cards use a {@link UiMarkdown}; sub-agent cards use a {@link UiStack}. */
    private final UiNode body;
    private final boolean open;
    /** When set, the {@code <summary>} text is wrapped in a span with this id so it can be REPLACEd alone. */
    private final String summaryId;

    /** Primary constructor — caller supplies the fully-built body node. */
    public TaskCardComponent(String nodeId, String headerLabel,
                             UiNode body, boolean open) {
        this(nodeId, headerLabel, body, open, null);
    }

    /** Full constructor — {@code summaryId} makes the summary text individually REPLACEable. */
    public TaskCardComponent(String nodeId, String headerLabel,
                             UiNode body, boolean open, String summaryId) {
        this.nodeId = nodeId;
        this.headerLabel = headerLabel;
        this.body = body;
        this.open = open;
        this.summaryId = summaryId;
    }

    /** Convenience constructor for the common markdown-body case. */
    public TaskCardComponent(String nodeId, String headerLabel,
                             String bodyMarkdown, boolean open) {
        this(nodeId, headerLabel,
                UiMarkdown.of(nodeId + "-md", bodyMarkdown)
                        .<UiMarkdown>withCssClass("task-card-body"),
                open);
    }

    /** Stable id of a sub-agent card's summary span (the running/done marker shown even when collapsed). */
    public static String summaryId(String taskId) {
        return "subsummary-" + taskId;
    }

    /** The id of the card's {@code <li>}; what REPLACE patches target. */
    @Override
    public String id() {
        return nodeId;
    }

    /**
     * Stable id of the nested {@code <ul>}-bearing list that holds a
     * sub-agent's child task cards. Inner tool-call events APPEND into
     * this id so they nest under the sub-agent instead of going flat into
     * the message list. Derived purely from the sub-agent's {@code taskId}
     * so the streaming handler can compute it without holding a reference
     * to the card object.
     */
    public static String childListId(String subAgentTaskId) {
        return "subtasks-" + subAgentTaskId;
    }

    /**
     * Renders as a single-item {@link UiList} wrapper. The wrapper id
     * differs from the item id so a REPLACE on the item ({@link #id()})
     * morphs the {@code <li>} content without disturbing the surrounding
     * list — but APPEND on the message list's id can also take this
     * wrapper directly: the renderer's "append a list" path extracts
     * its {@code items} into the existing {@code <ul>}.
     */
    @Override
    public UiList render() {
        var wrapper = UiList.of("task-wrapper-" + nodeId, null);
        // Task cards are CLIENT-collapsed: they always render collapsed and the
        // user's manual expand/collapse is preserved across streaming patches
        // (the morpher leaves `open` alone on data-sui-client-collapse). The
        // server never dictates open state, so a live update can't snap a card
        // the user opened back shut. `summaryId` (sub-agent cards) keeps the
        // header marker individually REPLACE-able even while collapsed.
        var item = UiList.Item.of(nodeId, "").content(body)
                .collapsibleClient(headerLabel, summaryId);
        wrapper.item(item);
        return wrapper;
    }

    // ── Factory helpers for tool-call cards ────────────────────────────────

    /** Card representing a tool call that has just started. */
    public static TaskCardComponent runningTool(String nodeId, String toolName, Object arguments) {
        return new TaskCardComponent(nodeId,
                runningToolHeader(toolName),
                taskCardBody(prettyJson(arguments), null),
                true);
    }

    /** Card representing a tool call that finished successfully. */
    public static TaskCardComponent doneTool(String nodeId, String toolName,
                                             Object arguments, String result, long durationMs) {
        return new TaskCardComponent(nodeId,
                doneToolHeader(toolName, durationMs),
                taskCardBody(prettyJson(arguments), result),
                false);
    }

    /** Card representing a tool call that failed. */
    public static TaskCardComponent failedTool(String nodeId, String toolName,
                                               Object arguments, String error, long durationMs) {
        return new TaskCardComponent(nodeId,
                failedToolHeader(toolName, durationMs),
                taskCardBody(prettyJson(arguments), error),
                false);
    }

    // ── Factory helpers for sub-agent cards (tree-bearing) ─────────────────
    //
    // A sub-agent card is special: its body holds a NESTED list of child
    // task cards that fill in over the life of the run. Because the child
    // cards are appended into the live DOM (not re-sent on every update),
    // a sub-agent card is NEVER REPLACED wholesale once running — that
    // would morph the nested <ul> back to empty and wipe the children.
    // Instead the running card is built once, and the controller later
    // targets two stable inner ids: the summary span ({@link #summaryId} —
    // the running/done/failed marker, visible even when collapsed) and the
    // body stack ({@link #stackId}) it appends the final answer to.

    /** Stable id of a sub-agent card's body stack (append target for the final answer). */
    public static String stackId(String taskId) {
        return childListId(taskId) + "-stack";
    }

    /**
     * A link that opens the sub-agent's own session page in a new browser
     * tab. {@code external} → target="_blank" with no SPA interception.
     * {@code subSessionId} is the sub-session's id (== the card's taskId).
     */
    public static UiLink openSessionLink(String subSessionId) {
        return UiLink.external("open-sub-" + subSessionId,
                "/admin/sessions/" + subSessionId,
                "↗ Open sub-agent session");
    }

    /**
     * The running sub-agent card. Built once at SubAgentStarted. The
     * {@code <details>} summary carries the live status marker (⏳ + agent
     * name) inside an id'd span so it can be flipped to ✓/✗ on completion
     * without touching the nested tree — and stays visible when the card is
     * collapsed. The body opens with a link to the sub-session and (once
     * known) the input, then the nested child list; the final answer is
     * appended beneath it on done.
     *
     * <p>{@code subSessionId} is the durable session id this card is keyed on
     * (== {@code taskId} for live cards). {@code inputJson} is the
     * {@code run_agent} call's arguments, or null when not yet known live.
     */
    public static TaskCardComponent runningSubAgent(String nodeId, String agentName, String taskId,
                                                    String subSessionId, String inputJson) {
        var stack = UiStack.of(stackId(taskId));
        stack.child(openSessionLink(subSessionId));
        if (inputJson != null && !inputJson.isBlank()) {
            stack.child(UiMarkdown.of(nodeId + "-io",
                    taskCardBody(inputJson, null)).<UiMarkdown>withCssClass("task-card-body"));
        }
        stack.child(subAgentChildList(taskId));
        return new TaskCardComponent(nodeId, runningSubAgentHeader(agentName), stack, true,
                summaryId(taskId));
    }

    /** The nested child list ({@code subtasks-{taskId}}) that child cards append into. */
    public static UiList subAgentChildList(String taskId) {
        var children = UiList.of(childListId(taskId), null);
        children.withCssClass("sub-agent-children");
        return children;
    }

    /** Summary-span REPLACE node flipping the marker to "done". Visible while collapsed. */
    public static UiText doneSubAgentSummary(String taskId, String agentName, long durationMs) {
        return UiText.of(summaryId(taskId), doneSubAgentHeader(agentName, durationMs));
    }

    /** Summary-span REPLACE node flipping the marker to "failed". */
    public static UiText failedSubAgentSummary(String taskId, String agentName) {
        return UiText.of(summaryId(taskId), failedSubAgentHeader(agentName));
    }

    /** Markdown node holding the sub-agent's final answer — APPENDed into the body stack on done. */
    public static UiMarkdown subAgentAnswer(String taskId, String finalText) {
        String md = (finalText == null || finalText.isBlank())
                ? "_Sub-agent finished (no text output)._"
                : finalText;
        return UiMarkdown.of("subanswer-" + taskId, md)
                .<UiMarkdown>withCssClass("sub-agent-answer bot-message");
    }

    /**
     * Card rebuilt from persisted TOOL_CALL + TOOL_RESULT messages. Caller
     * passes a pre-computed header (already labelled done/failed) and the
     * card body produced by {@link #taskCardBody(String, String)}.
     */
    public static TaskCardComponent historic(String nodeId, String header, String body) {
        return new TaskCardComponent(nodeId, header, body, false);
    }

    /**
     * Sub-agent card rebuilt from persisted state on a full page load.
     * Keyed on the sub-session id ({@code taskId}) so its ids match the ones
     * the live stream uses — a reload mid-run produces the identical nodes
     * and the run's continuing live patches (summary flip, answer append)
     * still land. {@code childList} holds the recursively-built child cards;
     * pass {@code finalTextMarkdown == null} while the sub-agent is still
     * running so no answer block is shown yet.
     *
     * <p>The body reads like a normal tool card plus the tree: a link to open
     * the sub-session in a new tab, the call's **Input** (and **Output** when
     * it differs from the rendered answer), the nested child tree, then the
     * sub-agent's final answer as a chat bubble.
     *
     * @param running    when true the summary shows the ⏳ marker and the card
     *                   starts open; the summary id lets a later done patch flip it.
     * @param inputJson  the {@code run_agent} call's pretty-printed arguments, or null.
     * @param resultText the persisted tool-result text (≈ the answer), or null while running.
     */
    public static TaskCardComponent historicSubAgent(String nodeId, String agentName, String taskId,
                                                     boolean running, boolean failed,
                                                     long durationMs, String inputJson, String resultText,
                                                     UiList childList, String finalTextMarkdown) {
        var stack = UiStack.of(stackId(taskId));
        stack.child(openSessionLink(taskId));
        if (inputJson != null && !inputJson.isBlank()) {
            stack.child(UiMarkdown.of(nodeId + "-io",
                    taskCardBody(inputJson, null)).<UiMarkdown>withCssClass("task-card-body"));
        }
        stack.child(childList);
        // The sub-agent's answer: prefer the persisted CHAT text (rendered as
        // a chat bubble); fall back to the raw tool-result string.
        String answer = (finalTextMarkdown != null && !finalTextMarkdown.isBlank())
                ? finalTextMarkdown : resultText;
        if (answer != null && !answer.isBlank()) {
            stack.child(UiMarkdown.of("subanswer-" + taskId, answer)
                    .<UiMarkdown>withCssClass("sub-agent-answer bot-message"));
        }
        String header = running ? runningSubAgentHeader(agentName)
                : failed       ? failedSubAgentHeader(agentName)
                :                doneSubAgentHeader(agentName, durationMs);
        return new TaskCardComponent(nodeId, header, stack, running, summaryId(taskId));
    }

    // ── Header / body string helpers ───────────────────────────────────────

    /** "⏳ toolName — running…". */
    public static String runningToolHeader(String toolName) {
        return "⏳ " + toolName + " — running…";
    }

    /** "✓ toolName (123 ms)". */
    public static String doneToolHeader(String toolName, long durationMs) {
        return "✓ " + toolName + "  (" + durationMs + " ms)";
    }

    /** "✗ toolName (123 ms)". */
    public static String failedToolHeader(String toolName, long durationMs) {
        return "✗ " + toolName + "  (" + durationMs + " ms)";
    }

    public static String runningSubAgentHeader(String agentName) {
        return "⏳ ↳ " + agentName + " — running…";
    }

    public static String doneSubAgentHeader(String agentName, long durationMs) {
        return "✓ ↳ " + agentName + "  (" + durationMs + " ms)";
    }

    public static String failedSubAgentHeader(String agentName) {
        return "✗ ↳ " + agentName;
    }

    /**
     * Builds the markdown body of a task card: optional **Input** code
     * block, optional **Output** code block, or a placeholder when both
     * are null.
     */
    public static String taskCardBody(String inputJsonOrText, String output) {
        StringBuilder md = new StringBuilder();
        if (inputJsonOrText != null && !inputJsonOrText.isBlank()) {
            md.append("**Input**\n\n```\n").append(inputJsonOrText).append("\n```\n\n");
        }
        if (output != null && !output.isBlank()) {
            md.append("**Output**\n\n```\n").append(output).append("\n```\n");
        }
        if (md.length() == 0) md.append("_(no input/output)_");
        return md.toString();
    }

    /** Pretty-prints arguments map as JSON; falls back to toString on errors. */
    public static String prettyJson(Object value) {
        if (value == null) return "";
        try {
            return SessionUiCommons.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
