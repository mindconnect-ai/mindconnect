package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
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

    private static ManagedAgent agent(String name, String group, List<String> reviewers) {
        return agent(name, group, reviewers, ManagedAgent.Status.ACTIVE);
    }

    private static ManagedAgent agent(String name, String group, List<String> reviewers,
                                      ManagedAgent.Status status) {
        return new ManagedAgent(ai.mindconnect.agent.management.api.AgentId.of(AgentId.random().value()),
                new ai.mindconnect.agent.runtime.api.domain.AgentDefinition(name, "d", "prompt",
                        ai.mindconnect.agent.runtime.api.domain.ModelSettings.ofModel("cfg"), List.of(), 5,
                        java.util.Map.of(ResponseReviewPlugin.REVIEWERS, reviewers)),
                group, null, null, status, null, null, 0);
    }

    /** The values the picker offers, read off the rendered form. */
    private static List<String> reviewerOptions(ManagedAgent edited, ManagedAgent... all) {
        var rendered = (UiStack) new AgentFormComponent(edited, new InMemoryLlmConfigRepository(ScopeSupplier.local()),
                List.of(all), new ObjectMapper()).render();
        UiForm form = rendered.getChildren().stream()
                .filter(UiForm.class::isInstance).map(UiForm.class::cast).findFirst().orElseThrow();
        UiField field = form.getFields().stream()
                .filter(f -> "responseReviewers".equals(f.getId())).findFirst().orElseThrow();
        return field.getOptions().stream().map(UiField.Option::getValue).toList();
    }

    @Test
    void onlyAgentsInTheReviewerGroupAreOffered() {
        ManagedAgent edited = agent("scout", "assistants", List.of());
        ManagedAgent tone = agent("tone-reviewer", "reviewer", List.of());
        ManagedAgent helper = agent("web-researcher", "sub-agents", List.of());

        assertThat(reviewerOptions(edited, edited, tone, helper)).containsExactly("tone-reviewer");
    }

    @Test
    void theAgentItselfIsNotOfferedEvenWhenFiledAsAReviewer() {
        ManagedAgent edited = agent("tone-reviewer", "reviewer", List.of());
        ManagedAgent other = agent("style-reviewer", "reviewer", List.of());

        assertThat(reviewerOptions(edited, edited, other)).containsExactly("style-reviewer");
    }

    @Test
    void aReviewerAlreadyNamedStaysOfferedWhateverItsGroup() {
        // 'verifier' was picked before the picker was narrowed, or was moved
        // out of the group since: the form must still show it ticked, not
        // silently drop it on the next save. It comes first — the field keeps
        // the ticked rows in their stored order — the candidates after it by name.
        ManagedAgent edited = agent("scout", "assistants", List.of("verifier"));
        ManagedAgent tone = agent("tone-reviewer", "reviewer", List.of());
        ManagedAgent verifier = agent("verifier", "sub-agents", List.of());

        assertThat(reviewerOptions(edited, edited, tone, verifier)).containsExactly("verifier", "tone-reviewer");
    }

    @Test
    void aReviewerWhoseAgentIsGoneIsDropped() {
        // The agent was deleted: keeping its name would have every turn try
        // it and log a failed reviewer, with no way to clear it but unticking.
        ManagedAgent edited = agent("scout", "assistants", List.of("tone-reviewer"));

        assertThat(reviewerOptions(edited, edited)).isEmpty();
    }

    @Test
    void aDeprecatedReviewerIsNotOffered() {
        ManagedAgent edited = agent("scout", "assistants", List.of());
        ManagedAgent old = agent("old-reviewer", "reviewer", List.of(), ManagedAgent.Status.DEPRECATED);
        ManagedAgent tone = agent("tone-reviewer", "reviewer", List.of());

        assertThat(reviewerOptions(edited, edited, old, tone)).containsExactly("tone-reviewer");
    }

    @Test
    void aNewAgentSeesTheReviewerGroupByName() {
        ManagedAgent tone = agent("tone-reviewer", "reviewer", List.of());
        ManagedAgent alpha = agent("alpha-reviewer", "reviewer", List.of());
        ManagedAgent helper = agent("planner", "sub-agents", List.of());

        assertThat(reviewerOptions(null, tone, alpha, helper)).containsExactly("alpha-reviewer", "tone-reviewer");
    }
}
