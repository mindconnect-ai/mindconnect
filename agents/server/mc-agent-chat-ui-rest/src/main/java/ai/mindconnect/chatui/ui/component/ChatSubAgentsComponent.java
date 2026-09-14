package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.ToolState;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.util.List;
import java.util.Set;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The agents this chat can hand work to, as the picker behind the composer's
 * "+ &rarr; Sub-agents".
 *
 * <p>Every agent that lets itself be called by other agents, each with an
 * Off | On. On are the ones the chat's agent brought, until the chat picks
 * its own — which may include agents its agent never had. Switching the
 * first one on is all it takes to delegate: the runtime gives a chat with a
 * roster {@code run_agent}, {@code run_agents} and {@code list_agents}, and
 * takes them away again when the last one goes off.
 *
 * <p>Every row also has a Test, which sends one message to that agent in a
 * dialog and shows its answer — the quickest way to find out what an agent is
 * for before letting the chat call it.
 */
public final class ChatSubAgentsComponent {

    private ChatSubAgentsComponent() {}

    /** Stable id of the picker's body, so a toggle can REPLACE it in place. */
    public static final String BODY_ID = "chat-subagents-body";

    /**
     * @param agents   the agents this chat could be given, in registry order
     * @param selected the ones on its roster, by name
     */
    public static UiNode node(SessionId sessionId, List<AgentDefinition> agents, Set<String> selected) {
        var body = UiStack.of(BODY_ID).gap(12);

        if (agents.isEmpty()) {
            body.child(UiText.of("chat-subagents-empty",
                            "No other agents are registered yet. Agents are created in the admin UI.")
                    .withCssClass("chat-picker-empty"));
            return body;
        }

        long on = agents.stream().filter(a -> isOn(selected, a)).count();
        var list = UiList.of("chat-subagents-list",
                "Sub-agents · " + on + " of " + agents.size() + " on").icon("bot");
        for (AgentDefinition agent : agents) {
            String slug = ChatToolsPickerComponent.slug(agent.name());
            var item = UiList.Item.of("chat-subagent-" + slug, agent.name())
                    .icon(agent.iconOrDefault())
                    .description(describe(agent))
                    .action(test(sessionId, agent));
            ChatToolsPickerComponent.segments(item, "subagent-" + slug,
                    isOn(selected, agent) ? ToolState.ON : ToolState.OFF,
                    List.of(ToolState.OFF, ToolState.ON),
                    s -> s == ToolState.ON ? "On" : "Off",
                    s -> trigger(on(ChatUiController.class)
                            .setSubAgent(sessionId.value(), agent.name(), s == ToolState.ON, null)));
            list.item(item);
        }
        body.child(list);

        body.child(UiText.of("chat-subagents-hint", on == 0
                        ? "This chat calls no other agent. Switching one on gives it run_agent and list_agents."
                        : "Each sub-agent runs in its own session and reports back to this chat.")
                .withCssClass("chat-picker-hint"));
        return body;
    }

    /** A roster names agents as typed; the lookup ignores case, so does this. */
    private static boolean isOn(Set<String> selected, AgentDefinition agent) {
        return selected.stream().anyMatch(agent.name()::equalsIgnoreCase);
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
     * "Test": a dialog that sends one message straight to the agent and shows
     * what it answers. It does not go through this chat and does not need
     * delegation to be on — it is the agent on its own.
     */
    private static UiAction test(SessionId sessionId, AgentDefinition agent) {
        return UiAction.secondary("test-" + ChatToolsPickerComponent.slug(agent.name()), "Test")
                .icon("flash")
                .onClick(trigger(on(ChatUiController.class)
                        .subAgentTestDialog(sessionId.value(), agent.name(), null)));
    }
}
