package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;

import java.util.List;

/**
 * Edit form for an agent definition. Used both for "new" (null agent)
 * and "edit" (existing agent) modes — defaults switch on the null
 * check, the submit target becomes POST vs PUT.
 *
 * <p>The form pulls LLM configs and reviewer-candidate agents from
 * repositories at render time so the dropdown options stay current
 * without the component caching anything.
 */
public final class AgentFormComponent implements UiComponent {

    private final AgentDefinition agent;
    private final LlmConfigRepository llmConfigRepository;
    private final AgentDefinitionRepository agentRepository;
    private final Namespace defaultNamespace;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * @param agent {@code null} for the new-agent form, an existing
     *              {@link AgentDefinition} for the edit form
     */
    public AgentFormComponent(AgentDefinition agent,
                              LlmConfigRepository llmConfigRepository,
                              AgentDefinitionRepository agentRepository,
                              Namespace defaultNamespace,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.agent = agent;
        this.llmConfigRepository = llmConfigRepository;
        this.agentRepository = agentRepository;
        this.defaultNamespace = defaultNamespace;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return agent == null ? "agent-new" : "agent-" + agent.id();
    }

    @Override
    public UiForm render() {
        boolean isNew = agent == null;

        List<UiField.Option> llmOptions = llmConfigRepository.findAll().stream()
                .map(c -> UiField.Option.of(c.name(),
                        c.name() + " (" + c.provider() + " / " + c.model() + ")"))
                .toList();

        // Namespace options — hardcoded "local" for now, extensible later.
        List<UiField.Option> nsOptions = List.of(
                UiField.Option.of("local", "local"));

        // Reviewer candidates: every other agent in the same namespace.
        List<UiField.Option> reviewerOptions = agentRepository
                .findByNamespace(isNew ? defaultNamespace : agent.namespace()).stream()
                .filter(other -> isNew || !other.id().equals(agent.id()))
                .map(other -> UiField.Option.of(other.name(), other.name()))
                .toList();

        return UiForm.of(id(), isNew ? "New Agent" : "Edit Agent: " + agent.name())
                .field(UiField.select("namespace", "Namespace",
                        isNew ? defaultNamespace.value() : agent.namespace().value(), nsOptions)
                        .asEditable().asRequired())
                .field(UiField.text("name", "Name", isNew ? null : agent.name())
                        .asEditable().asRequired())
                .field(UiField.text("description", "Description", isNew ? null : agent.description())
                        .asEditable())
                .field(UiField.textarea("systemPrompt", "System Prompt",
                        isNew ? null : agent.systemPrompt())
                        .asEditable())
                .field(UiField.text("welcomeMessage", "Welcome Message",
                        isNew ? null : agent.welcomeMessage())
                        .asEditable())
                .field(UiField.select("llmConfigName", "LLM Config",
                        isNew ? null : agent.llmConfigName(), llmOptions)
                        .asEditable().asRequired())
                .field(UiField.number("maxIterations", "Max Iterations",
                        isNew ? 10 : agent.maxIterations())
                        .asEditable()
                        .hint("Maximum tool-call rounds per turn (defaults to 10, raise to 30+ for deep research)"))
                .field(UiField.textarea("memoryConfig", "Memory (JSON)",
                        isNew ? null : memoryConfigJson(agent))
                        .asEditable()
                        .hint("Memory strategy of this agent. \"kind\": summarizing_window "
                                + "(default — summaries + tool-result compression; "
                                + "\"compressToolResults\" is the on/off switch), "
                                + "full, windowed, auto_compact, none. "
                                + "Leave blank to keep the current setting"))
                .field(UiField.bool("toolSearchEnabled", "Enable Tool Search",
                        !isNew && agent.toolSearchOrOff().enabled())
                        .asEditable()
                        .hint("Adds the tool_search tool: the agent can discover its deferred tools "
                                + "(and the registry groups below) at runtime instead of carrying "
                                + "every tool definition in its context"))
                .field(UiField.text("toolSearchGroups", "Tool Search Groups",
                        isNew ? null : String.join(", ", agent.toolSearchOrOff().groups()))
                        .asEditable()
                        .hint("Comma-separated registry groups searchable beyond the agent's own "
                                + "deferred tools, e.g. web, documents — or * for every group. "
                                + "Empty = only deferred assigned tools are searchable"))
                .field(UiField.multiselect("responseReviewers", "Response Reviewers",
                        isNew ? List.of() : agent.effectiveResponseReviewers(), reviewerOptions)
                        .asEditable()
                        .hint("Agents that review and may rewrite the response, in order"))
                .action(UiAction.primary("save", "Save").icon("save")
                        .dispatch(isNew ? "POST" : "PUT",
                                  isNew ? "/admin/api/agents"
                                        : "/admin/api/agents/" + agent.id(),
                                  id()))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .dispatch("GET", isNew ? "/admin/api/agents"
                                              : "/admin/api/agents/" + agent.id()))
                .link(UiLink.of("back", "/admin/agents", "← Back to Agents"));
    }

    /**
     * The EFFECTIVE memory config, serialised WITH its {@code kind} tag —
     * pre-filling the field with the real current state (system default
     * included) makes the knobs discoverable instead of doc-only knowledge.
     */
    private String memoryConfigJson(AgentDefinition agent) {
        try {
            return objectMapper.writerFor(ai.mindconnect.agent.memory.domain.MemoryConfig.class)
                    .withDefaultPrettyPrinter()
                    .writeValueAsString(agent.effectiveMemoryConfig());
        } catch (Exception e) {
            return "";
        }
    }
}
