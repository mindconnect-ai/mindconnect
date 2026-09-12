package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiText;

import java.util.ArrayList;
import java.util.List;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;

/**
 * Who answers in this chat, as a dialog over the running conversation: an
 * agent, a model, a prompt. Three fields, in the order they overrule each
 * other.
 *
 * <p>A chat never waits for this: it starts on the default agent with the
 * default model, and this is where you change your mind. Which is also why
 * the agent field can be empty — a chat may be its own agent, assembled from
 * the two fields below it.
 *
 * <p><b>What is deliberately not here.</b> The tool list and the tool-search
 * switch used to share this dialog, as a {@code <select multiple>} and a
 * checkbox behind a tab called "Model &amp; tools". They are switches you
 * flick while typing, not a form you fill in, and they now live one click
 * behind the composer's "+" ({@link ChatToolsPickerComponent},
 * {@link ChatSubAgentsComponent}) — so this dialog is the three things that
 * genuinely are a form, and it needs no tabs to hold them. The closing line
 * says where the rest went; a dialog that silently loses half its contents
 * teaches people the feature was removed.
 */
public final class ChatSettingsComponent implements UiComponent {

    private final SessionId sessionId;
    private final List<LlmConfig> llmConfigs;
    private final List<AgentDefinition> agents;
    private final String currentLlmConfigName;
    private final AgentId currentAgentId;
    /** What this chat runs on today — the agent's prompt, or its own override. */
    private final String currentSystemPrompt;

    public ChatSettingsComponent(SessionId sessionId, List<LlmConfig> llmConfigs,
                                 List<AgentDefinition> agents,
                                 String currentLlmConfigName, AgentId currentAgentId,
                                 String currentSystemPrompt) {
        this.sessionId = sessionId;
        this.llmConfigs = llmConfigs;
        this.agents = agents;
        this.currentLlmConfigName = currentLlmConfigName;
        this.currentAgentId = currentAgentId;
        this.currentSystemPrompt = currentSystemPrompt;
    }

    @Override
    public String id() {
        return "chat-settings-" + sessionId.value();
    }

    /** The dialog's heading — what this dialog is now about. */
    public static final String TITLE = "Agent, model & prompt";

    @Override
    public UiForm render() {
        return UiForm.of(id(), null)
                .field(agentField())
                .field(modelField())
                .field(promptField())
                // After the fields, before the footer: where the tools and the
                // sub-agents went when they left this dialog.
                .content(UiText.of(id() + "-elsewhere",
                                "Tools and sub-agents are switched in the composer's “+” menu.")
                        .withCssClass("chat-settings-elsewhere"))
                .action(UiAction.primary("apply", "Apply").icon("save")
                        .onClick(trigger(on(ChatUiController.class)
                                .applySettings(sessionId.value(), null, null), id())))
                .action(UiAction.secondary("cancel", "Cancel")
                        .onClick(trigger(on(ChatUiController.class).closeDialog())))
                .<UiForm>withCssClass("chat-settings-form");
    }

    /**
     * The agent, first because it overrules the other two: picking a different
     * one hands the chat over completely. Staying on the one it has leaves the
     * two fields below acting as this chat's own overrides.
     */
    private UiField agentField() {
        List<UiField.Option> options = new ArrayList<>();
        options.add(UiField.Option.of("", "— no agent: the model and prompt below —"));
        agents.forEach(a -> options.add(UiField.Option.of(a.id().value(), label(a))));
        return UiField.select("agentId", "Agent",
                        currentAgentId == null ? "" : currentAgentId.value(), options)
                .asEditable()
                .hint("An agent brings its own prompt, model and tools. Switch to a different "
                        + "one and it takes over; stay on this one and the two fields below "
                        + "override it for this chat alone");
    }

    /** An agent reads as what it is for, not just what it is called. */
    private static String label(AgentDefinition agent) {
        String description = agent.description();
        if (description == null || description.isBlank()) {
            return agent.name();
        }
        String trimmed = description.strip();
        if (trimmed.length() > 70) {
            trimmed = trimmed.substring(0, 69).strip() + "…";
        }
        return agent.name() + " — " + trimmed;
    }

    private UiField modelField() {
        List<UiField.Option> options = llmConfigs.stream()
                .map(c -> UiField.Option.of(c.name(), label(c)))
                .toList();
        return UiField.select("llmConfigName", "Model", currentLlmConfigName, options)
                .asEditable()
                .hint("Provider, key and context window come with the config");
    }

    /**
     * A config reads as "name (provider / model)" — except an alias, which
     * carries no provider settings of its own and reads as what it points at.
     * {@code agent-default} is exactly that, and it used to render as
     * "agent-default (null / null)": the sort of thing a dialog shows once
     * and is never trusted again.
     */
    private static String label(LlmConfig config) {
        if (config.isAlias()) {
            return config.delegatesTo() == null || config.delegatesTo().isBlank()
                    ? config.name()
                    : config.name() + " \u2192 " + config.delegatesTo();
        }
        var detail = new ArrayList<String>();
        if (config.provider() != null) {
            detail.add(config.provider().toString());
        }
        if (config.model() != null && !config.model().isBlank()) {
            detail.add(config.model());
        }
        return detail.isEmpty()
                ? config.name()
                : config.name() + " (" + String.join(" / ", detail) + ")";
    }

    private UiField promptField() {
        return UiField.textarea("systemPrompt", "System prompt", currentSystemPrompt)
                .asEditable()
                .hint("Starts as the agent's own. Edit it and this chat alone uses yours — "
                        + "the agent, its tools and the agents it may call stay as they are");
    }
}
