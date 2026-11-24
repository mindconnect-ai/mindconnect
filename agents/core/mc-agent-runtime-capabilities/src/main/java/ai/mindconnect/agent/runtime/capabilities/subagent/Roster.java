package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;

/**
 * Whom an agent may hand a task to in a session: the agents the session's
 * project brings, then the ones the agent names. A project's agent stands
 * in for a named one of the same name — the project is right about its own
 * reviewer.
 */
final class Roster {

    /** @param ofProject whether the project brings it; else the caller names it */
    record Entry(AgentDefinition agent, boolean ofProject) { }

    private final AgentDefinition caller;
    private final AgentResolver agents;
    private final Supplier<List<ProjectAgentSource>> projects;

    Roster(AgentDefinition caller, AgentResolver agents, Supplier<List<ProjectAgentSource>> projects) {
        this.caller = caller;
        this.agents = agents;
        this.projects = projects;
    }

    List<Entry> entries(Session session) {
        List<Entry> entries = new ArrayList<>();
        projects.get().forEach(source -> source.agents(caller, session).stream()
                .filter(agent -> named(entries, agent.name()).isEmpty())
                .forEach(agent -> entries.add(new Entry(agent, true))));
        for (String name : SubAgentPlugin.subAgentsOf(caller)) {
            if (named(entries, name).isEmpty()) {
                agents.resolve(name, session).filter(SubAgentPlugin::callable)
                        .ifPresent(agent -> entries.add(new Entry(agent, false)));
            }
        }
        return entries;
    }

    /** The names the caller gives, whatever session it is in. */
    List<String> namedByCaller() {
        return SubAgentPlugin.subAgentsOf(caller);
    }

    Optional<AgentDefinition> find(String name, Session session) {
        return named(entries(session), name).map(Entry::agent);
    }

    List<String> names(Session session) {
        return entries(session).stream().map(entry -> entry.agent().name()).toList();
    }

    private static Optional<Entry> named(List<Entry> entries, String name) {
        return entries.stream().filter(entry -> entry.agent().name().equalsIgnoreCase(name)).findFirst();
    }
}
