package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.util.List;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The specialists this chat can hand work to, as the picker behind the
 * composer's "+ &rarr; Sub-agents".
 *
 * <p>Two things live here, because delegating needs both. The switch on top is
 * the capability: without {@code run_agent} in the chat's tools a roster of
 * agents is a list of names the model cannot call, and turning it on from a
 * list of agents is less surprising than discovering the tool missing
 * mid-answer. Below it is the roster itself — who is available, and what each
 * one is for.
 *
 * <p>Picking one does not reconfigure the chat: it writes the brief. "Ask"
 * drops the delegation sentence into the composer with the cursor where the
 * task goes, because a sub-agent needs a self-contained task and only the
 * person typing has it. Which agents appear at all is the agent's own roster
 * ({@code callableAgents}) — the chat cannot widen it from here, and a chat
 * that lists an agent it may not call would be theatre.
 */
public final class ChatSubAgentsComponent {

    private ChatSubAgentsComponent() {}

    /** Stable id of the picker's body, so a toggle can REPLACE it in place. */
    public static final String BODY_ID = "chat-subagents-body";

    /**
     * @param agents     the agents this chat may call, in registry order
     * @param delegation whether the chat currently carries the delegation tools
     * @param restricted whether the agent behind the chat was given a roster —
     *                   said out loud, so a short list does not read as a bug
     */
    public static UiNode node(SessionId sessionId, List<AgentDefinition> agents,
                              boolean delegation, boolean restricted) {
        var body = UiStack.of(BODY_ID).gap(12);

        var switchList = UiList.of("chat-subagents-switch", "");
        switchList.item(UiList.Item.of("chat-delegation", "Hand work to other agents")
                .icon("git-branch")
                .description("Adds run_agent, run_agents and list_agents. Each sub-agent runs in "
                        + "its own session and reports back — this chat keeps the conversation")
                .action(ChatToolsPickerComponent.toggle("delegation", delegation,
                        trigger(on(ChatUiController.class)
                                .toggleDelegation(sessionId.value(), !delegation, null)))));
        body.child(switchList);

        if (agents.isEmpty()) {
            body.child(UiText.of("chat-subagents-empty",
                            "No other agents are registered yet. Agents are created in the admin UI; "
                                    + "the ones filed under “sub-agents” show up here.")
                    .withCssClass("chat-picker-empty"));
            return body;
        }

        var list = UiList.of("chat-subagents-list", "Sub-agents · " + agents.size()).icon("bot");
        for (AgentDefinition agent : agents) {
            list.item(UiList.Item.of("chat-subagent-" + ChatToolsPickerComponent.slug(agent.name()),
                            agent.name())
                    .icon(agent.iconOrDefault())
                    .description(describe(agent))
                    .action(ask(sessionId, agent, delegation)));
        }
        body.child(list);

        if (restricted) {
            body.child(UiText.of("chat-subagents-roster",
                            "This is the roster the agent behind the chat was given. "
                                    + "Widening it is an agent setting, not a chat setting.")
                    .withCssClass("chat-picker-hint"));
        }
        return body;
    }

    /** What the agent is for, or its rubric when nobody wrote it down. */
    private static String describe(AgentDefinition agent) {
        String description = agent.description();
        if (description != null && !description.isBlank()) {
            return description;
        }
        return ChatToolsPickerComponent.displayGroup(agent.groupOrDefault());
    }

    /**
     * "Ask": the brief, not the answer. It closes the picker and leaves the
     * composer holding the first half of the sentence, so the task is typed
     * where every other message is typed and Send is the one thing that
     * starts a turn.
     */
    private static UiAction ask(SessionId sessionId, AgentDefinition agent, boolean delegation) {
        var action = UiAction.secondary("ask-" + ChatToolsPickerComponent.slug(agent.name()), "Ask")
                .icon("send-horizontal")
                .onClick(trigger(on(ChatUiController.class)
                                .delegateToAgent(sessionId.value(), agent.name(), null, null),
                        ChatFormComponent.formId(sessionId)));
        return delegation
                ? action
                : action.disabled("Turn delegation on first — this chat has no run_agent");
    }
}
