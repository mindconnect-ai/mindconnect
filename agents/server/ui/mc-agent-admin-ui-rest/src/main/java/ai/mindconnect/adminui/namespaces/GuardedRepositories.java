package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;

import java.util.List;
import java.util.Optional;

/**
 * The stores a namespace's admins own, each wrapped in the one question that
 * decides it ({@link NamespaceWriteGuard}). Reading is untouched — a user of a
 * namespace chats with its agents, which means loading them — so only the
 * writing methods ask.
 *
 * <p>They are decorators rather than a check inside each store, because a
 * store is written once per persistence backend and the question belongs to
 * the installation, not to files or to Postgres.
 */
public final class GuardedRepositories {

    private GuardedRepositories() {
    }

    /** Agents: created, renamed, given tools and deleted by admins. */
    public record Agents(AgentDefinitionRepository delegate, NamespaceWriteGuard guard)
            implements AgentDefinitionRepository {

        @Override
        public AgentDefinition save(AgentDefinition definition) {
            guard.requireAdmin("an agent");
            return delegate.save(definition);
        }

        @Override
        public void deleteById(AgentId id) {
            guard.requireAdmin("an agent");
            delegate.deleteById(id);
        }

        @Override
        public Optional<AgentDefinition> findById(AgentId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<AgentDefinition> findByName(String name) {
            return delegate.findByName(name);
        }

        @Override
        public List<AgentDefinition> findAll() {
            return delegate.findAll();
        }
    }

    /** LLM configurations: the models and the keys behind them. */
    public record LlmConfigs(LlmConfigRepository delegate, NamespaceWriteGuard guard)
            implements LlmConfigRepository {

        @Override
        public void save(LlmConfig config) {
            guard.requireAdmin("an LLM config");
            delegate.save(config);
        }

        @Override
        public void deleteById(LlmConfigId id) {
            guard.requireAdmin("an LLM config");
            delegate.deleteById(id);
        }

        @Override
        public Optional<LlmConfig> findById(LlmConfigId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<LlmConfig> findByName(String name) {
            return delegate.findByName(name);
        }

        @Override
        public List<LlmConfig> findAll() {
            return delegate.findAll();
        }
    }

    /** LLM prices: what the configs cost, which the admins who own the configs maintain. */
    public record LlmPrices(LlmPriceRepository delegate, NamespaceWriteGuard guard)
            implements LlmPriceRepository {

        @Override
        public void save(LlmPrice price) {
            guard.requireAdmin("an LLM price");
            delegate.save(price);
        }

        @Override
        public void deleteById(LlmPriceId id) {
            guard.requireAdmin("an LLM price");
            delegate.deleteById(id);
        }

        @Override
        public Optional<LlmPrice> findById(LlmPriceId id) {
            return delegate.findById(id);
        }

        @Override
        public List<LlmPrice> findByConfigName(String configName) {
            return delegate.findByConfigName(configName);
        }

        @Override
        public List<LlmPrice> findAll() {
            return delegate.findAll();
        }
    }

    /** Skills: what the agents of this namespace know how to do. */
    public record Skills(SkillRepository delegate, NamespaceWriteGuard guard) implements SkillRepository {

        @Override
        public Skill save(Skill skill) {
            guard.requireAdmin("a skill");
            return delegate.save(skill);
        }

        @Override
        public void deleteById(SkillId id) {
            guard.requireAdmin("a skill");
            delegate.deleteById(id);
        }

        @Override
        public Optional<Skill> findById(SkillId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Skill> findByName(String name) {
            return delegate.findByName(name);
        }

        @Override
        public List<Skill> findAll() {
            return delegate.findAll();
        }
    }

    /** Workflow definitions: the editor's side. Running one writes instances, which are not these. */
    public record Workflows(WorkflowDataRepository delegate, NamespaceWriteGuard guard)
            implements WorkflowDataRepository {

        @Override
        public void save(String id, WorkflowData workflow) {
            guard.requireAdmin("a workflow");
            delegate.save(id, workflow);
        }

        @Override
        public boolean delete(String id) {
            guard.requireAdmin("a workflow");
            return delegate.delete(id);
        }

        @Override
        public List<String> listIds() {
            return delegate.listIds();
        }

        @Override
        public Optional<WorkflowData> findById(String id) {
            return delegate.findById(id);
        }
    }

    /**
     * MCP servers: registering one gives every agent of the namespace its tools,
     * and a registration carries the credentials it starts with. Probing a draft
     * and refreshing a registration are writes too — the first reaches out to a
     * server of the caller's choosing, the second replaces what is stored.
     */
    public record McpServers(McpRegistryAdmin delegate, NamespaceWriteGuard guard) implements McpRegistryAdmin {

        @Override
        public void save(McpServerRegistration registration) {
            guard.requireAdmin("an MCP server");
            delegate.save(registration);
        }

        @Override
        public void delete(McpServerId id) {
            guard.requireAdmin("an MCP server");
            delegate.delete(id);
        }

        @Override
        public McpProbeResult probe(McpServerRegistration draft) {
            guard.requireAdmin("an MCP server");
            return delegate.probe(draft);
        }

        @Override
        public void refresh(McpServerId id) {
            guard.requireAdmin("an MCP server");
            delegate.refresh(id);
        }

        @Override
        public List<McpServerRegistration> all() {
            return delegate.all();
        }

        @Override
        public Optional<McpServerRegistration> findById(McpServerId id) {
            return delegate.findById(id);
        }

        @Override
        public McpDiscovery discovery(McpServerId id) {
            return delegate.discovery(id);
        }
    }
}
