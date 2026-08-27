package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.List;
import java.util.Map;

/**
 * Exposes the agent's tool list to system-prompt templates so the prompt can
 * iterate over them, e.g. to render a help block.
 * <p>
 * Variables provided:
 * <ul>
 *   <li>{@code tools} — list of {@code {name, description}} entries</li>
 * </ul>
 * Example template usage:
 * <pre>
 * Available tools:
 * {% for t in tools %}- {{ t.name }}: {{ t.description }}
 * {% endfor %}
 * </pre>
 */
public class AgentToolsProvider implements PromptContextProvider {

    @Override
    public void contribute(Map<String, Object> ctx,
                           AgentDefinition def,
                           AgentSession session,
                           AuthenticationInfo auth) {
        if (def == null || def.tools() == null) {
            ctx.put("tools", List.of());
            return;
        }
        List<Map<String, Object>> tools = def.tools().stream()
                .filter(AgentTool::enabled)
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "description", t.description() != null ? t.description() : ""))
                .toList();
        ctx.put("tools", tools);
    }
}
