package ai.mindconnect.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Helper for setting SLF4J/MDC fields scoped to a single agent invocation.
 * <p>
 * Use as a try-with-resources block — the MDC slots are reset to whatever was
 * there before the call ended (so nested invocations stack correctly):
 * <pre>{@code
 * try (var ctx = LoggingContext.session(sessionId, conversationId, agentName)) {
 *     // every log line in this block has sessionId/agentName in its MDC
 *     ...
 *     try (var sub = LoggingContext.subagent("answer-relevance-checker")) {
 *         // sub-agent block — agentName stays, subAgent is set
 *         ...
 *     }
 *     // sub-agent slot cleared again here
 * }
 * }</pre>
 *
 * <p>The Logback pattern in {@code logback-spring.xml} can read these via
 * {@code %X{sessionId}}, {@code %X{agentName}}, {@code %X{subAgent}} etc.
 */
public final class LoggingContext implements AutoCloseable {

    /** MDC key for the agent session UUID. */
    public static final String SESSION_ID = "sessionId";
    /** MDC key for the conversation UUID. */
    public static final String CONVERSATION_ID = "conversationId";
    /** MDC key for the currently running agent's display name. */
    public static final String AGENT_NAME = "agentName";
    /** MDC key for a sub-agent that was invoked from inside the current agent. */
    public static final String SUB_AGENT = "subAgent";
    /** MDC key for the stateless task name (e.g. "answer-relevance-checker"). */
    public static final String TASK = "task";
    /** MDC key for the current tool being executed. */
    public static final String TOOL = "tool";

    private final String[] keys;
    private final String[] previousValues;

    private LoggingContext(String[] keys, String[] previousValues) {
        this.keys = keys;
        this.previousValues = previousValues;
    }

    /** Sets sessionId, conversationId and agentName for the duration of the block. */
    public static LoggingContext session(UUID sessionId, UUID conversationId, String agentName) {
        return push(
                SESSION_ID, sessionId != null ? sessionId.toString() : null,
                CONVERSATION_ID, conversationId != null ? conversationId.toString() : null,
                AGENT_NAME, agentName);
    }

    /** Sets the subAgent + task slot. The parent agentName stays untouched. */
    public static LoggingContext subagent(String subAgentName, String task) {
        return push(SUB_AGENT, subAgentName, TASK, task);
    }

    /** Sets the tool slot for the duration of one tool invocation. */
    public static LoggingContext tool(String toolName) {
        return push(TOOL, toolName);
    }

    /** Generic factory: pairs of (key, value). Null values still go through put-then-restore. */
    private static LoggingContext push(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of arguments (key,value pairs)");
        }
        int n = keyValuePairs.length / 2;
        String[] keys = new String[n];
        String[] previous = new String[n];
        for (int i = 0; i < n; i++) {
            String key = keyValuePairs[2 * i];
            String value = keyValuePairs[2 * i + 1];
            keys[i] = key;
            previous[i] = MDC.get(key);
            if (value != null) {
                MDC.put(key, value);
            } else {
                MDC.remove(key);
            }
        }
        return new LoggingContext(keys, previous);
    }

    @Override
    public void close() {
        for (int i = 0; i < keys.length; i++) {
            if (previousValues[i] != null) {
                MDC.put(keys[i], previousValues[i]);
            } else {
                MDC.remove(keys[i]);
            }
        }
    }
}
