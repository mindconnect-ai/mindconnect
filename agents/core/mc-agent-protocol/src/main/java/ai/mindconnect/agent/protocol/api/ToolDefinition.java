package ai.mindconnect.agent.protocol.api;

import java.util.Map;

/**
 * One function tool as the model is told about it: a name, what it is for, and
 * the shape of its arguments. That description is the same whoever runs the
 * tool, which is why one type serves both sides.
 *
 * <p><b>On a request</b> it declares a tool of the CLIENT — the OpenAI-style
 * complement to the agent's own tool set. When the model calls one, the runtime
 * cannot execute it: the response ends
 * {@code INCOMPLETE(WAITING_FOR_TOOL_OUTPUT)} with the open
 * {@code FunctionCall} item, the client executes locally and sends the
 * {@code FunctionCallOutput} as input of the next request. Same resume
 * mechanic as approvals — no extra machinery.
 *
 * <p><b>Inside the runtime</b> the same record describes the server-side tools
 * an agent offers, on their way to the model. Those never appear on a request:
 * they are part of the agent definition, and in the output they show up only as
 * {@code FunctionCall} / {@code FunctionCallOutput} item pairs.
 *
 * @param parametersSchema JSON-Schema-shaped description of the arguments
 */
public record ToolDefinition(String name, String description, Map<String, Object> parametersSchema) {}
