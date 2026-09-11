package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.AgentFormComponent;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.ui.model.UiPage;

/**
 * Agent create/edit form. Same page for both modes — the wrapped
 * {@link AgentFormComponent} accepts {@code null} as the "new agent"
 * marker and switches its defaults and submit target accordingly.
 *
 * <p>The URL embedded in the {@link UiPage} reflects the mode:
 * {@code /admin/agents/new}, {@code /admin/agents/{id}/edit}, or the
 * copy variant ({@code /admin/agents/{copyId}/edit}) when an agent has
 * just been duplicated.
 */
public final class AgentFormPage extends AdminPage {

    private final AgentDefinition agent;
    private final LlmConfigRepository llmConfigRepository;
    private final AgentDefinitionRepository agentRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * @param agent {@code null} for the new-agent form, a populated
     *              {@link AgentDefinition} for the edit form
     *              (including the copy-then-edit flow)
     */
    public AgentFormPage(AgentDefinition agent,
                          LlmConfigRepository llmConfigRepository,
                          AgentDefinitionRepository agentRepository,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.agent = agent;
        this.llmConfigRepository = llmConfigRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public UiPage render() {
        String url = agent == null
                ? "/admin/agents/new"
                : "/admin/agents/" + agent.id().value() + "/edit";
        return UiPage.of(url,
                new AgentFormComponent(agent, llmConfigRepository, agentRepository, objectMapper)
                        .render());
    }
}
