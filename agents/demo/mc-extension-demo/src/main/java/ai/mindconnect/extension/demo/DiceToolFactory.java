package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

import java.util.Random;

/**
 * Registered in {@code META-INF/services/ai.mindconnect.agent.tool.ToolFactory}
 * and named in the manifest under {@code contributes.tools.providers} — the
 * classpath finds it, the manifest answers for it.
 */
public final class DiceToolFactory implements ToolFactory {

    /** The extension's store, handed over by the runtime at bind time; absent on a host without the demo feature. */
    private DiceRollRepository rolls;

    @Override
    public void bind(ToolEnvironment env) {
        rolls = env.get(DiceRollRepository.class).orElse(null);
    }

    @Override
    public String name() {
        return "demo_dice";
    }

    @Override
    public String group() {
        return "demo";
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        DiceRollRepository store = rolls;
        return new DiceTool(new Random(), store == null ? roll -> { } : store::append);
    }
}
