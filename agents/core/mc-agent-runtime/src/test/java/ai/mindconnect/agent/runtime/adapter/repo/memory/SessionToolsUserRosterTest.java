package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.task.SessionTools;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.UserToolRoster;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools a user keeps in their own account reach the chat and stop there.
 *
 * <p>A sub-agent keeps the roster its definition gives it: somebody curated
 * that list for a narrow job, and a research helper that silently gained the
 * ability to send mail would be a surprise nobody asked for.
 */
class SessionToolsUserRosterTest {

    private static final UserId ALICE = UserId.of("alice");

    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final AgentDefinition agent = AgentDefinition
            .create("assistant", "a", "You help.", null, "llm")
            .withTools(List.of(AgentTool.of("web_search")));
    private final AgentSession session = sessions.create(
            AgentSession.start(agent.id(), ALICE, ConversationId.random()));

    /** Adds one tool for everybody, so the test can see whether it arrived. */
    private final UserToolRoster roster = (userId, agentId, refs) -> {
        List<AgentTool> out = new ArrayList<>(refs);
        out.add(AgentTool.of("email_list_messages"));
        return out;
    };

    @Test
    void a_chat_offers_what_its_user_brought_along() {
        assertThat(names(session.id())).containsExactly("web_search", "email_list_messages");
    }

    @Test
    void a_sub_agent_keeps_the_list_its_definition_gives_it() {
        // Same session, but it is somebody else's sub-agent: its root is the chat above it.
        assertThat(names(SessionId.random())).containsExactly("web_search");
    }

    @Test
    void without_a_roster_nothing_changes_for_anybody() {
        SessionTools tools = new SessionTools(RECORDING, new DynamicToolActivations(sessions),
                agent, session, session.id());

        assertThat(tools.liveTools()).isEmpty();          // the registry resolves nothing here
        assertThat(resolved).containsExactly("web_search");
    }

    /** The names the registry was asked to resolve for a session whose root is {@code rootSessionId}. */
    private List<String> names(SessionId rootSessionId) {
        resolved.clear();
        new SessionTools(RECORDING, new DynamicToolActivations(sessions, SkillCatalog.none(), roster),
                agent, session, rootSessionId).liveTools();
        return List.copyOf(resolved);
    }

    private static final List<String> resolved = new ArrayList<>();

    /** Records what it was asked for and resolves nothing — the ask is what this test is about. */
    private static final ToolRegistry RECORDING = new ToolRegistry() {
        @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
            resolved.add(agentTool.name());
            return Optional.empty();
        }
    };
}
