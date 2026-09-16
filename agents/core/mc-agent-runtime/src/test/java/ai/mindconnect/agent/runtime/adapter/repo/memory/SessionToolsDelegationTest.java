package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.task.SessionTools;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** An agent with a roster gets {@code run_agent} only when the runtime delegates. */
class SessionToolsDelegationTest {

    private static final ToolRegistry NO_TOOLS = (agentTool, scope) -> Optional.empty();

    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final AgentDefinition orchestrator = AgentDefinition.create("orchestrator", "o", "You delegate.", null, "llm")
            .withCallableAgents(List.of("researcher"));
    private final AgentSession session = sessions.create(AgentSession.start(orchestrator.id(), UserId.of("u"), ConversationId.random()));

    private List<String> definitions(SubAgentSupport support) {
        var tools = new SessionTools(NO_TOOLS, new DynamicToolActivations(sessions), orchestrator, session, session.id(), support);
        return tools.toolDefinitions(session.id()).stream().map(d -> d.name()).toList();
    }

    @Test
    void withDelegationTheRosterYieldsTheInlineTools() {
        assertThat(definitions(SubAgentSupport.enabled(5)))
                .contains(InlineAgentTools.RUN_AGENT, InlineAgentTools.RUN_AGENTS);
    }

    @Test
    void withoutDelegationTheRosterIsIgnored() {
        assertThat(definitions(SubAgentSupport.disabled()))
                .doesNotContain(InlineAgentTools.RUN_AGENT, InlineAgentTools.RUN_AGENTS);
    }
}
