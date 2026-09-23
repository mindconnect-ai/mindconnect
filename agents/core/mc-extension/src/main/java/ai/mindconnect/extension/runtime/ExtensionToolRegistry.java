package ai.mindconnect.extension.runtime;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AliasTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ConnectionTester;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolVariable;
import ai.mindconnect.extension.service.ExtensionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A {@link ToolRegistry} that leaves out the tools of extensions switched
 * off in the namespace at hand. Sits beneath the operator's tool settings
 * ({@code OverlayToolRegistry}): a namespace that turned an extension off has
 * said something about the whole extension, and no tool setting and no
 * agent definition brings one of its tools back.
 *
 * <p>Which tools belong to which extension is what the manifests declare
 * under {@code contributes.tools.names} — patterns like {@code acme_*}. A
 * tool no manifest claims is nobody's to hide.
 *
 * <p>The decision is the namespace's, so it is asked per call through the
 * service, whose store is routed by the current scope. Off a bound scope —
 * a warm-up, a start-up routine — there is no namespace to have decided, and
 * nothing is hidden.
 */
public final class ExtensionToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionToolRegistry.class);

    private final ToolRegistry delegate;
    private final ExtensionService extensions;

    public ExtensionToolRegistry(ToolRegistry delegate, ExtensionService extensions) {
        this.delegate = delegate;
        this.extensions = extensions;
    }

    @Override
    public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
        if (hidden(AliasTool.registryName(agentTool))) {
            return Optional.empty();
        }
        return delegate.resolve(agentTool, scope);
    }

    @Override
    public Set<String> knownToolNames() {
        return delegate.knownToolNames().stream()
                .filter(name -> !hidden(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Map<String, Set<String>> toolNamesByGroup() {
        Map<String, Set<String>> byGroup = new TreeMap<>();
        delegate.toolNamesByGroup().forEach((group, names) -> {
            Set<String> kept = names.stream().filter(name -> !hidden(name))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!kept.isEmpty()) byGroup.put(group, kept);
        });
        return byGroup;
    }

    private boolean hidden(String toolName) {
        try {
            return extensions.hidesTool(toolName);
        } catch (IllegalStateException noScope) {
            // No namespace bound to this thread: nobody has decided anything here.
            log.debug("No scope while resolving '{}' — extension decisions not applied", toolName);
            return false;
        }
    }

    @Override
    public Map<String, Object> overridesSchema(String toolName) {
        return delegate.overridesSchema(toolName);
    }

    @Override
    public String subgroupOf(String toolName) {
        return delegate.subgroupOf(toolName);
    }

    @Override
    public List<ToolVariable> declaredVariables() {
        return delegate.declaredVariables();
    }

    @Override
    public List<ConnectionSpec> connectionSpecs() {
        return delegate.connectionSpecs();
    }

    @Override
    public Optional<ConnectionSpec> connectionSpecOf(String toolName) {
        return delegate.connectionSpecOf(toolName);
    }

    @Override
    public Optional<ConnectionTester> connectionTesterOf(String provider) {
        return delegate.connectionTesterOf(provider);
    }

    @Override
    public void releaseSession(SessionId sessionId) {
        delegate.releaseSession(sessionId);
    }

    @Override
    public ToolRegistry source() {
        return delegate.source();
    }
}
