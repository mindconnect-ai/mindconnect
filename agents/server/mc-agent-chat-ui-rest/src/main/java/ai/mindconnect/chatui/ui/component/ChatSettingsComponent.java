package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiSection;

import java.util.List;
import java.util.UUID;

/**
 * Model and tools for one chat, as a dialog over the running conversation.
 *
 * <p>A chat never waits for this: it starts on the default model with the
 * default tools, and this is where you change your mind. Which is also why
 * the agent field can be empty — most chats have no agent behind them.
 */
public final class ChatSettingsComponent implements UiComponent {

    /** What a chat can do before anyone configures anything. */
    public static final List<String> DEFAULT_TOOLS = List.of(
            "list_agents", "run_agent", "run_agents",
            "workspace_read", "workspace_write", "workspace_list",
            "todo_read", "todo_write");

    private final UUID sessionId;
    private final List<LlmConfig> llmConfigs;
    private final List<AgentDefinition> agents;
    private final List<String> toolNames;
    private final String currentLlmConfigName;
    private final List<String> currentTools;
    private final boolean toolSearchOn;
    private final UUID currentAgentId;
    /** What this chat runs on today — the agent's prompt, or its own override. */
    private final String currentSystemPrompt;

    public ChatSettingsComponent(UUID sessionId, List<LlmConfig> llmConfigs,
                                 List<AgentDefinition> agents, List<String> toolNames,
                                 String currentLlmConfigName, List<String> currentTools,
                                 boolean toolSearchOn, UUID currentAgentId) {
        this(sessionId, llmConfigs, agents, toolNames, currentLlmConfigName, currentTools,
                toolSearchOn, currentAgentId, null);
    }

    public ChatSettingsComponent(UUID sessionId, List<LlmConfig> llmConfigs,
                                 List<AgentDefinition> agents, List<String> toolNames,
                                 String currentLlmConfigName, List<String> currentTools,
                                 boolean toolSearchOn, UUID currentAgentId,
                                 String currentSystemPrompt) {
        this.currentSystemPrompt = currentSystemPrompt;
        this.sessionId = sessionId;
        this.llmConfigs = llmConfigs;
        this.agents = agents;
        this.toolNames = toolNames;
        this.currentLlmConfigName = currentLlmConfigName;
        this.currentTools = currentTools;
        this.toolSearchOn = toolSearchOn;
        this.currentAgentId = currentAgentId;
    }

    @Override
    public String id() {
        return "chat-settings-" + sessionId;
    }

    @Override
    public UiForm render() {
        List<UiField.Option> modelOptions = llmConfigs.stream()
                .map(c -> UiField.Option.of(c.name(),
                        c.name() + " (" + c.provider() + " / " + c.model() + ")"))
                .toList();

        List<UiField.Option> agentOptions = new java.util.ArrayList<>();
        agentOptions.add(UiField.Option.of("", "— no agent: model and tools below —"));
        agents.forEach(a -> agentOptions.add(UiField.Option.of(a.id().toString(), a.name())));

        List<UiField.Option> toolOptions = toolNames.stream()
                .map(n -> UiField.Option.of(n, n))
                .toList();

        // One tab per half. The fields live in field groups inside the tabs,
        // and the tabs inside the FORM — not the form inside the tabs. The
        // submitted payload is the id of the <form> the button sits in
        // (EventBus.inferImplicitPayload), so a second form would submit only
        // its own half: pressing Apply on the agent tab would send agentId and
        // nothing else, and the model, the tools and the prompt would arrive
        // as absent. A hidden tab is hidden, not removed, so its inputs are
        // still part of the one form.
        var modelTab = UiFieldGroup.of(id() + "-g-model", null)
                .field(UiField.select("llmConfigName", "Model", currentLlmConfigName, modelOptions)
                        .asEditable()
                        .hint("Provider, key and context window come with the config"))
                .field(UiField.multiselect("tools", "Tools", currentTools, toolOptions)
                        .asEditable()
                        .hint("Offered up front. Everything else stays reachable through tool search"))
                .field(UiField.bool("toolSearch", "Tool search", toolSearchOn)
                        .asEditable()
                        .hint("Lets the chat find the remaining tools itself instead of carrying "
                                + "every definition in its context"))
                .field(UiField.textarea("systemPrompt", "System prompt", currentSystemPrompt)
                        .asEditable()
                        .hint("Starts as the agent's own. Edit it and this chat alone uses "
                                + "yours — the agent, its tools and the agents it may call "
                                + "stay as they are"));

        var agentTab = UiFieldGroup.of(id() + "-g-agent", null)
                .field(UiField.select("agentId", "…or an agent",
                                currentAgentId == null ? "" : currentAgentId.toString(), agentOptions)
                        .asEditable()
                        .hint("Takes over prompt, model and tools — the fields on the other tab "
                                + "stop applying"));

        var tabs = UiSection.of(id() + "-tabs", null)
                .section(id() + "-tab-model", "Model & tools", modelTab)
                .section(id() + "-tab-agent", "Agent", agentTab);

        return UiForm.of(id(), "Chat settings")
                .content(tabs)
                .action(UiAction.primary("apply", "Apply").icon("save")
                        .dispatch("POST", "/chat/api/sessions/" + sessionId + "/settings"))
                .action(UiAction.secondary("cancel", "Cancel")
                        .dispatch("POST", "/chat/api/close-dialog"));
    }
}
