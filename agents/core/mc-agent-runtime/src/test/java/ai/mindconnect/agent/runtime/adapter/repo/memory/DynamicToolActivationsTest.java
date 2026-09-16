package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file attached to a chat brings the tools that read it, whether or not the
 * agent's definition lists them — the upload stores the file where only those
 * reach it. Nothing else may be handed out that way.
 */
class DynamicToolActivationsTest {

    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final DynamicToolActivations activations = new DynamicToolActivations(sessions);
    private final AgentDefinition noTools = AgentDefinition.create("demo", "d", "You.", null, "llm");

    private AgentSession session(AgentDefinition def) {
        return sessions.create(AgentSession.start(def.id(), UserId.of("u"), ConversationId.random()));
    }

    private void attach(AgentSession session, AttachedFile file) {
        sessions.update(session.id(), current -> current.withAttachedFiles(List.of(file)));
    }

    @Test
    void anIndexedAttachmentBringsVectorSearchToAnAgentWithoutTools() {
        AgentSession session = session(noTools);
        assertThat(activations.effectiveRefs(noTools, session.id())).isEmpty();

        attach(session, new AttachedFile("f1", "policy.md", "text/markdown", 10));

        assertThat(activations.effectiveRefs(noTools, session.id())).extracting(AgentTool::name)
                .containsExactly("vector_search");
    }

    @Test
    void aCopyOnDiskBringsTheFileToolsAndAnImageTheViewer() {
        AgentSession session = session(noTools);
        attach(session, new AttachedFile("f1", "notes.md", "text/markdown", 10).withPath("/tmp/notes.md"));
        attach(session, new AttachedFile("f2", "shot.png", "image/png", 10));

        assertThat(activations.effectiveRefs(noTools, session.id())).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("vector_search", "file_read", "file_list", "view_attachment");
    }

    @Test
    void aToolTheDefinitionListsIsNotOfferedTwice() {
        AgentDefinition def = noTools.withTools(List.of(AgentTool.of("vector_search")));
        AgentSession session = session(def);
        attach(session, new AttachedFile("f1", "policy.md", "text/markdown", 10));

        assertThat(activations.effectiveRefs(def, session.id())).extracting(AgentTool::name)
                .containsExactly("vector_search");
    }

    @Test
    void anActivationAloneGrantsNothingTheDefinitionDoesNotList() {
        AgentSession session = session(noTools);

        activations.activate(session.id(), List.of("bash", "file_read"));

        assertThat(activations.effectiveRefs(noTools, session.id())).isEmpty();
    }
}
