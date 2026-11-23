package ai.mindconnect.agent.runtime.capabilities.toolsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Tool search: an agent's held-back tools are left out of the model call
 * until the agent has searched for them. Without this plugin a held-back
 * tool is offered like any other — the binding is a wish, this is what
 * grants it.
 */
public final class ToolSearchPlugin implements Plugin {

    private final ToolActivation activation;

    public ToolSearchPlugin(ToolActivation activation) {
        this.activation = activation;
    }

    @Override
    public String name() {
        return "tool-search";
    }

    @Override
    public void install(PluginRegistry registry) {
        ToolResolver everyTool = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));
        ToolSearchTool search = new ToolSearchTool(registry.supplier(TurnService.class), everyTool, activation);
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(search)));

        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            List<String> deferred = context.agent().tools().stream()
                    .filter(ToolBinding::enabled).filter(ToolBinding::deferred).map(ToolBinding::name).toList();
            if (deferred.isEmpty()) {
                return tools;
            }
            Set<String> found = activation.activated(context.session().id());
            List<ToolDefinition> offered = new ArrayList<>(tools.stream()
                    .filter(tool -> !deferred.contains(tool.name()) || found.contains(tool.name()))
                    .toList());
            offered.add(ToolSearchTool.DEFINITION);
            return offered;
        });
    }
}
