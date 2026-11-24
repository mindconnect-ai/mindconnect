package ai.mindconnect.agent.management.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.filerepo.DocumentExistsException;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;

/**
 * Agents under {@code {base}/{namespace}/managed-agents/{agentId}.json}, one
 * file each: an agent can be read, copied and put under version control as
 * a file. Updates of one agent take turns (see {@code mc-file-repo}).
 */
public final class FileAgentRepository implements AgentRepository {

    private static final String AGENTS = "managed-agents";

    private final Documents<AgentId, ManagedAgent> agents;

    public FileAgentRepository(Path base, ObjectMapper json, ScopeSupplier scope) {
        this.agents = Documents.of(ManagedAgent.class)
                .path((AgentId id) -> AGENTS + "/" + id.value() + ".json")
                // The partition of the namespace the current call works in.
                .build(() -> FileRepo.open(base, scope.namespace().value()), json);
    }

    @Override
    public ManagedAgent create(ManagedAgent agent) {
        try {
            return agents.create(agent.id(), agent);
        } catch (DocumentExistsException e) {
            throw new IllegalStateException("Agent " + agent.id().value() + " exists already", e);
        }
    }

    @Override
    public Optional<ManagedAgent> find(AgentId id) {
        return agents.find(id);
    }

    @Override
    public List<ManagedAgent> findAll() {
        return agents.findAll(AGENTS);
    }

    @Override
    public Optional<ManagedAgent> update(AgentId id, UnaryOperator<ManagedAgent> change) {
        return agents.update(id, change);
    }

    @Override
    public boolean delete(AgentId id) {
        return agents.delete(id);
    }
}
