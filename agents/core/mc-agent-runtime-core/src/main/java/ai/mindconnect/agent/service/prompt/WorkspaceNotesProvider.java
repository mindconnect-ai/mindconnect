package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.Map;

/**
 * Exposes persistent workspace artefacts (per-agent-user notes, user profile)
 * so they can be conditionally rendered in the system prompt.
 * <p>
 * Variables provided:
 * <ul>
 *   <li>{@code user_notes}   — content of {@code notes.md} in the agent-user
 *       workspace, or {@code null} if absent. The agent uses this scope to
 *       remember things across sessions about the same user.</li>
 *   <li>{@code user_profile} — content of {@code profile.md} in the user
 *       workspace, or {@code null} if absent. Shared read-only across all agents.</li>
 * </ul>
 * <p>
 * Higher priority than the metadata providers — runs after them so a more
 * expensive read happens once the cheaper data is already in place.
 */
public class WorkspaceNotesProvider implements PromptContextProvider {

    private final WorkspaceStore workspaceStore;

    public WorkspaceNotesProvider(WorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
    }

    @Override public int priority() { return 100; }

    @Override
    public void contribute(Map<String, Object> ctx,
                           AgentDefinition def,
                           AgentSession session,
                           AuthenticationInfo auth) {
        if (def == null || session == null || session.userId() == null) {
            ctx.put("user_notes", null);
            ctx.put("user_profile", null);
            return;
        }
        WorkspaceScope agentUserScope = WorkspaceScope.agentUser(def.id(), session.userId());
        WorkspaceScope userScope      = WorkspaceScope.user(session.userId());

        String notes = workspaceStore.readOrNull(agentUserScope, "notes.md");
        ctx.put("user_notes", (notes != null && !notes.isEmpty()) ? notes : null);

        String profile = workspaceStore.readOrNull(userScope, "profile.md");
        ctx.put("user_profile", (profile != null && !profile.isEmpty()) ? profile : null);
    }
}
