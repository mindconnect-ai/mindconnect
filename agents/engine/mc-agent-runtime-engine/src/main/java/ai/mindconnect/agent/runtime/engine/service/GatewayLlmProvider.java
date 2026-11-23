package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.port.out.LlmProvider;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;

/**
 * The loop's model, for one turn: puts the request together and asks the
 * gateway. Instructions, the tools on offer and the call itself are each a
 * hook point, put together anew for every call — a skill loaded or a tool
 * found mid-turn shows in the next one.
 */
final class GatewayLlmProvider implements LlmProvider {

    private final ModelGateway gateway;
    private final ToolResolver tools;
    private final HookRunner hooks;
    private final AgentContext context;
    private final InstructionRenderer instructions;

    GatewayLlmProvider(ModelGateway gateway, ToolResolver tools, HookRunner hooks, AgentContext context,
                       InstructionRenderer instructions) {
        this.gateway = gateway;
        this.tools = tools;
        this.hooks = hooks;
        this.context = context;
        this.instructions = instructions;
    }

    @Override
    public LlmAnswer ask(String requestId, String sessionId, List<TurnMessage> history, TurnEventListener events) {
        String rendered = hooks.run(RuntimeHook.INSTRUCTIONS, context, instructions::render);
        // Starts from what exists and the agent names; who may see what is the hooks' business.
        List<ToolDefinition> offered = hooks.run(RuntimeHook.TOOL_OFFER, context, asked ->
                tools.tools(asked.session()).stream().filter(tool -> asked.agent().uses(tool.name())).toList());
        ModelRequest request = new ModelRequest(context.turnId(), context.agent().modelSettings(), rendered, history, offered);
        return hooks.run(RuntimeHook.MODEL_CALL, new ModelCall(context, request),
                call -> gateway.ask(call.request(), events));
    }

    @Override
    public void cancel(String requestId) {
        gateway.cancel(TurnId.of(requestId));
    }
}
