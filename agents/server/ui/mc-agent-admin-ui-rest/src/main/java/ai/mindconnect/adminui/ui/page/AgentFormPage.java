package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.AgentFormComponent;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
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

    private final ManagedAgent agent;
    private final LlmConfigRepository llmConfigRepository;
    private final java.util.List<ManagedAgent> all;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ai.mindconnect.agent.skill.port.out.SkillRepository skills;

    /**
     * @param agent {@code null} for the new-agent form, a populated
     *              agent for the edit form
     *              (including the copy-then-edit flow)
     */
    public AgentFormPage(ManagedAgent agent,
                          LlmConfigRepository llmConfigRepository,
                          java.util.List<ManagedAgent> all,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(agent, llmConfigRepository, all, objectMapper, null);
    }

    public AgentFormPage(ManagedAgent agent,
                          LlmConfigRepository llmConfigRepository,
                          java.util.List<ManagedAgent> all,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                          ai.mindconnect.agent.skill.port.out.SkillRepository skills) {
        this.agent = agent;
        this.llmConfigRepository = llmConfigRepository;
        this.all = all;
        this.objectMapper = objectMapper;
        this.skills = skills;
    }

    @Override
    public UiPage render() {
        String url = agent == null
                ? "/admin/agents/new"
                : "/admin/agents/" + agent.id().value() + "/edit";
        return UiPage.of(url,
                new AgentFormComponent(agent, llmConfigRepository, all, objectMapper,
                        skills).render());
    }
}
