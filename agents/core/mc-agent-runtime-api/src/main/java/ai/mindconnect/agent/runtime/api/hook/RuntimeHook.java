package ai.mindconnect.agent.runtime.api.hook;

import java.util.List;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.plugin.HookPoint;

/**
 * The places in a turn a capability may attach to. The engine runs each of
 * them and knows nothing of who listens: skills, permissions, memory, traces
 * and guards are all hooks at these points, which is why they need the API
 * and not the engine.
 *
 * <p>In the order a turn passes them.
 */
public final class RuntimeHook {

    /** A turn is submitted. A {@code before} hook that stops it refuses the input. */
    public static final HookPoint<TurnInput, TurnAdmission> TURN_INPUT = HookPoint.of("turn.input");

    /** The agent's instructions are put together. An {@code after} hook adds to them. */
    public static final HookPoint<AgentContext, String> INSTRUCTIONS = HookPoint.of("instructions");

    /**
     * The tools for the next model call are chosen, starting from what the
     * agent names. An {@code after} hook takes tools away or adds its own.
     */
    public static final HookPoint<AgentContext, List<ToolDefinition>> TOOL_OFFER = HookPoint.of("tool.offer");

    /** The model is asked. A {@code before} hook may rewrite the request — this is where memory works. */
    public static final HookPoint<ModelCall, LlmAnswer> MODEL_CALL = HookPoint.of("model.call");

    /** A tool is run. A {@code before} hook that stops it answers in the tool's place. */
    public static final HookPoint<ToolInvocation, ToolOutput> TOOL_CALL = HookPoint.of("tool.call");

    /** The turn's final answer stands. An {@code after} hook may replace it. */
    public static final HookPoint<AgentContext, TurnMessage> ANSWER = HookPoint.of("answer");

    /** The turn is over, however it ended. For listeners. */
    public static final HookPoint<AgentContext, Turn> TURN_END = HookPoint.of("turn.end");

    private RuntimeHook() {
    }
}
