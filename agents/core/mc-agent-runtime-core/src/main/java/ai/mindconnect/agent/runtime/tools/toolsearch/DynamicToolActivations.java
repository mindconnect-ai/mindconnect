package ai.mindconnect.agent.runtime.tools.toolsearch;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillTool;
import ai.mindconnect.agent.runtime.skill.SkillToolFactory;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.UserToolRoster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped set of tools the agent discovered at runtime via
 * {@code tool_search}. The agent's configured tool list stays authoritative
 * and untouched — activations are an additive, in-memory layer that
 * {@code AgentChatService.resolveTools} merges in on every round, so a tool
 * found mid-turn is offered to the LLM from the next round on.
 *
 * <p>Persisted on the {@link ai.mindconnect.agent.runtime.domain.AgentSession}
 * itself: activations survive restarts and are deleted with the session.
 */
public final class DynamicToolActivations {

    private final ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions;
    /** What the {@code skill} tool would find; empty means the tool is not worth offering. */
    private final SkillCatalog skills;
    /** What the user keeps in their own account; {@link UserToolRoster#none()} where nobody can. */
    private final UserToolRoster userTools;

    public DynamicToolActivations(ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions) {
        this(sessions, SkillCatalog.none());
    }

    public DynamicToolActivations(ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions,
                                  SkillCatalog skills) {
        this(sessions, skills, UserToolRoster.none());
    }

    public DynamicToolActivations(ai.mindconnect.agent.runtime.port.out.AgentSessionRepository sessions,
                                  SkillCatalog skills, UserToolRoster userTools) {
        this.sessions = sessions;
        this.skills = skills == null ? SkillCatalog.none() : skills;
        this.userTools = userTools == null ? UserToolRoster.none() : userTools;
    }

    /**
     * {@link #effectiveRefs(ai.mindconnect.agent.runtime.domain.AgentDefinition, SessionId)}
     * with the tools the user keeps in their own account laid over — the layer
     * below the agent's list.
     *
     * <p>{@code mainAgent} is what keeps it off the sub-agents: a chat gets
     * what its user brought along, a sub-agent keeps the roster its definition
     * gives it, because somebody curated that list for a narrow job. The
     * user's <em>connections</em> are a different matter and follow them all
     * the way down — that is the call scope, not this list.
     */
    public List<AgentTool> effectiveRefs(ai.mindconnect.agent.runtime.domain.AgentDefinition def,
                                         SessionId sessionId, UserId userId, boolean mainAgent) {
        List<AgentTool> refs = effectiveRefs(def, sessionId);
        return mainAgent ? userTools.apply(userId, def.id(), refs) : refs;
    }

    /** Marks {@code toolNames} usable for {@code sessionId}, persisted on the session. */
    public void activate(SessionId sessionId, Collection<String> toolNames) {
        if (sessionId == null || toolNames.isEmpty()) {
            return;
        }
        sessions.update(sessionId, session -> session.activatedTools().containsAll(toolNames)
                ? session
                : session.withActivatedTools(toolNames));
    }

    /** The names activated for this session; empty set when none (or unknown session). */
    public Set<String> activated(SessionId sessionId) {
        if (sessionId == null) {
            return Set.of();
        }
        return sessions.findById(sessionId)
                .map(session -> (Set<String>) new java.util.LinkedHashSet<>(session.activatedTools()))
                .orElse(Set.of());
    }

    /**
     * The tool references to resolve for one round:
     * <ul>
     *   <li>non-deferred configured tools — always offered;</li>
     *   <li>deferred configured tools — only once a search activated them
     *       (with their configured overrides and pins intact);</li>
     *   <li>the {@code tool_search} tool itself whenever some tool is
     *       deferred, carrying their names as its search space so the factory
     *       needs no definition lookup;</li>
     *   <li>{@code list_agents} whenever the agent's roster names someone —
     *       the delegation tools follow the roster, not the tool list.</li>
     *   <li>the {@code skill} tool when the agent's skills setting is not
     *       NONE and there are any to load, carrying the names its setting
     *       names for the same reason. A tool that would find nothing is left away, so the switch
     *       costs nothing until somebody writes a skill. This is the only way
     *       an agent gets it: a {@code skill} row among its tools is dropped,
     *       since it would carry no names and reach every skill, whatever the
     *       setting says.</li>
     *   <li>the tools a file attached to this chat needs — {@code vector_search}
     *       for what was indexed, {@code view_attachment} for an image or a PDF,
     *       {@code file_read} / {@code file_list} for a copy on disk. These follow
     *       the session's attachments, not the definition: the upload put the file
     *       where only those tools reach it, and the system note tells the model to
     *       use them. They go when the attachment goes.</li>
     * </ul>
     * Nothing beyond these: a tool the agent neither lists nor has an attachment
     * for cannot be found, activated or offered.
     */
    public List<AgentTool> effectiveRefs(ai.mindconnect.agent.runtime.domain.AgentDefinition def, SessionId sessionId) {
        Set<String> activated = activated(sessionId);
        List<AgentTool> refs = new ArrayList<>();
        List<String> deferredNames = new ArrayList<>();
        for (AgentTool tool : def.tools()) {
            // The skill tool follows the agent's skills setting alone (below): a
            // row assigned by hand would carry no names and reach every skill.
            if (SkillTool.NAME.equals(tool.name())) continue;
            if (!tool.deferred()) {
                refs.add(tool);
                continue;
            }
            deferredNames.add(tool.name());
            if (activated.contains(tool.name())) {
                refs.add(tool);
            }
        }
        // What the session's own state justifies, the way list_agents follows the
        // roster: a file attached to this chat brings the tools that read it, whether
        // or not the definition lists them. The definition still bounds everything
        // else — an activation cannot hand out a tool nobody attached anything for.
        Set<String> defined = new java.util.HashSet<>();
        for (AgentTool tool : def.tools()) defined.add(tool.name());
        for (String name : attachmentTools(sessionId)) {
            if (!defined.contains(name)) refs.add(AgentTool.of(name));
        }
        if (!deferredNames.isEmpty()) {
            refs.add(AgentTool.of(ai.mindconnect.agent.runtime.domain.AgentDefinition.TOOL_SEARCH, null,
                    Map.of("assigned", List.copyOf(deferredNames))));
        }
        if (def.delegates()) {
            refs.add(AgentTool.of("list_agents"));
        }
        var skillsConfig = def.skillsOrDefault();
        // hasSkills asks the catalog by the same setting, so SPECIFIC naming
        // nothing gets no tool — the binding's empty list would mean "all".
        if (skillsConfig.enabled() && hasSkills(def, sessionId)) {
            refs.add(AgentTool.of(SkillTool.NAME, null, Map.of(
                    SkillToolFactory.NAMES, List.copyOf(skillsConfig.names()))));
        }
        return refs;
    }

    /**
     * Whether this agent has a skill to load in this session — the same
     * question the prompt's skills section answers, asked of the same
     * catalog, so the tool and the section appear together or not at all.
     */
    private boolean hasSkills(ai.mindconnect.agent.runtime.domain.AgentDefinition def, SessionId sessionId) {
        var session = sessionId == null ? null : sessions.findById(sessionId).orElse(null);
        return !skills.available(def, session).isEmpty();
    }

    /**
     * What the files attached to this chat need to be read at all. An upload
     * stores the file where only these tools reach it — indexed in the
     * session's vector store, kept as an image or a PDF for the viewer, copied
     * into the session's directory — and the system note that announces it
     * names them. Derived from the session, so the grant lasts exactly as long
     * as the attachment and cannot be handed out for anything else.
     */
    private Set<String> attachmentTools(SessionId sessionId) {
        var session = sessionId == null ? null : sessions.findById(sessionId).orElse(null);
        if (session == null || session.attachedFiles().isEmpty()) {
            return Set.of();
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        for (var file : session.attachedFiles()) {
            // An image is never indexed; everything else is, PDFs additionally readable page by page.
            if (file.isImage() || file.isPdf()) {
                names.add(ai.mindconnect.agent.runtime.tools.attachment.ViewAttachmentTool.NAME);
            }
            if (!file.isImage()) {
                names.add("vector_search");
            }
            if (file.hasPath()) {
                names.add("file_read");
                names.add("file_list");
            }
        }
        return names;
    }
}
