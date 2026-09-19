package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.service.UserToolService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a user keeps in their own tool account, and what they could add — the
 * catalogue and the store in one place, so neither the page nor the controller
 * has to know about both.
 *
 * <p>Every part is optional: without a {@link ToolRegistry} there is nothing to
 * offer, without a {@link UserToolService} nothing to store. Then the tab says
 * so rather than failing.
 */
@Service
public class UserTools {

    private final ObjectProvider<ToolRegistry> tools;
    private final ObjectProvider<UserToolService> userTools;
    private final ToolConnections connections;

    public UserTools(ObjectProvider<ToolRegistry> tools, ObjectProvider<UserToolService> userTools,
                     ToolConnections connections) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.userTools = Objects.requireNonNull(userTools, "userTools");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** True when this host can keep a user's own tools at all. */
    public boolean available() {
        return userTools.getIfAvailable() != null;
    }

    public Optional<UserToolService> service() {
        return Optional.ofNullable(userTools.getIfAvailable());
    }

    /** What {@code user} keeps, by the name it appears under. */
    public List<UserTool> of(UserId user) {
        return service().map(service -> service.of(user)).orElse(List.of());
    }

    public Optional<UserTool> find(UserId user, UserToolId id) {
        return service().flatMap(service -> service.find(user, id));
    }

    /**
     * What a user could add, grouped as the catalogue groups it. The inline
     * delegation tools are left out: they have no registry implementation, and
     * offering them would produce a tool that never resolves.
     */
    public Map<String, Set<String>> catalogue() {
        ToolRegistry registry = tools.getIfAvailable();
        if (registry == null) {
            return Map.of();
        }
        Map<String, Set<String>> byGroup = new LinkedHashMap<>();
        registry.toolNamesByGroup().forEach((group, names) -> {
            Set<String> offered = new java.util.LinkedHashSet<>(names);
            offered.remove("run_agent");
            offered.remove("run_agents");
            if (!offered.isEmpty()) byGroup.put(group, offered);
        });
        return byGroup;
    }

    /** The same catalogue as things to pick whole: groups, subgroups, connections, single tools. */
    public ToolBundles bundles() {
        ToolRegistry registry = tools.getIfAvailable();
        Set<String> known = new java.util.HashSet<>();
        catalogue().values().forEach(known::addAll);
        return registry == null ? ToolBundles.none() : ToolBundles.of(registry, known::contains);
    }

    /** True when the catalogue knows this name — a hand-typed URL must not store nonsense. */
    public boolean isKnown(String toolName) {
        return catalogue().values().stream().anyMatch(names -> names.contains(toolName));
    }

    /** The account a tool runs on, if it runs on one. */
    public Optional<ConnectionSpec> connectionSpecOf(String toolName) {
        ToolRegistry registry = tools.getIfAvailable();
        return registry == null ? Optional.empty() : registry.connectionSpecOf(toolName);
    }

    /** The connections {@code user} could point this tool at; empty when it needs none. */
    public List<Connection> connectionsFor(UserId user, String toolName) {
        return connectionSpecOf(toolName)
                .map(spec -> connections.of(user, spec.provider()))
                .orElse(List.of());
    }
}
