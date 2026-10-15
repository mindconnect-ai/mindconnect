package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Response Reviewers picker offers the agents written for the job — the
 * ones filed under the {@code reviewer} group — not every agent there is.
 * What the agent already names stays offered whatever its group, or saving
 * the form untouched would drop it; a name whose agent is gone is dropped.
 */
class AgentFormReviewerOptionsTest {

    private static AgentDefinition agent(String name, String group, List<String> reviewers) {
        return agent(name, group, reviewers, AgentDefinitionStatus.ACTIVE);
    }

    private static AgentDefinition agent(String name, String group, List<String> reviewers,
                                         AgentDefinitionStatus status) {
        return new AgentDefinition(AgentId.random(), name, "d", group, null, "prompt", null, "cfg", 5,
                null, status, List.of(), reviewers, null, null, null, null);
    }

    /** The values the picker offers, read off the rendered form. */
    private static List<String> reviewerOptions(AgentDefinition edited, AgentDefinition... all) {
        var repo = new InMemoryAgentDefinitionRepository();
        for (AgentDefinition a : all) repo.save(a);
        var rendered = (UiStack) new AgentFormComponent(edited, new InMemoryLlmConfigRepository(),
                repo, new ObjectMapper()).render();
        UiForm form = rendered.getChildren().stream()
                .filter(UiForm.class::isInstance).map(UiForm.class::cast).findFirst().orElseThrow();
        UiField field = form.getFields().stream()
                .filter(f -> "responseReviewers".equals(f.getId())).findFirst().orElseThrow();
        return field.getOptions().stream().map(UiField.Option::getValue).toList();
    }

    @Test
    void onlyAgentsInTheReviewerGroupAreOffered() {
        AgentDefinition edited = agent("scout", "assistants", List.of());
        AgentDefinition tone = agent("tone-reviewer", "reviewer", List.of());
        AgentDefinition helper = agent("web-researcher", "sub-agents", List.of());

        assertThat(reviewerOptions(edited, edited, tone, helper)).containsExactly("tone-reviewer");
    }

    @Test
    void theAgentItselfIsNotOfferedEvenWhenFiledAsAReviewer() {
        AgentDefinition edited = agent("tone-reviewer", "reviewer", List.of());
        AgentDefinition other = agent("style-reviewer", "reviewer", List.of());

        assertThat(reviewerOptions(edited, edited, other)).containsExactly("style-reviewer");
    }

    @Test
    void aReviewerAlreadyNamedStaysOfferedWhateverItsGroup() {
        // 'verifier' was picked before the picker was narrowed, or was moved
        // out of the group since: the form must still show it ticked, not
        // silently drop it on the next save. It comes first — the field keeps
        // the ticked rows in their stored order — the candidates after it by name.
        AgentDefinition edited = agent("scout", "assistants", List.of("verifier"));
        AgentDefinition tone = agent("tone-reviewer", "reviewer", List.of());
        AgentDefinition verifier = agent("verifier", "sub-agents", List.of());

        assertThat(reviewerOptions(edited, edited, tone, verifier)).containsExactly("verifier", "tone-reviewer");
    }

    @Test
    void aReviewerWhoseAgentIsGoneIsDropped() {
        // The agent was deleted: keeping its name would have every turn try
        // it and log a failed reviewer, with no way to clear it but unticking.
        AgentDefinition edited = agent("scout", "assistants", List.of("tone-reviewer"));

        assertThat(reviewerOptions(edited, edited)).isEmpty();
    }

    @Test
    void aDeprecatedReviewerIsNotOffered() {
        AgentDefinition edited = agent("scout", "assistants", List.of());
        AgentDefinition old = agent("old-reviewer", "reviewer", List.of(), AgentDefinitionStatus.DEPRECATED);
        AgentDefinition tone = agent("tone-reviewer", "reviewer", List.of());

        assertThat(reviewerOptions(edited, edited, old, tone)).containsExactly("tone-reviewer");
    }

    @Test
    void aNewAgentSeesTheReviewerGroupByName() {
        AgentDefinition tone = agent("tone-reviewer", "reviewer", List.of());
        AgentDefinition alpha = agent("alpha-reviewer", "reviewer", List.of());
        AgentDefinition helper = agent("planner", "sub-agents", List.of());

        assertThat(reviewerOptions(null, tone, alpha, helper)).containsExactly("alpha-reviewer", "tone-reviewer");
    }
}
