package ai.mindconnect.agent.adapter.llm;

import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.service.StatelessAgentSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-based implementation of {@link ToolResultSummarizer}.
 * <p>
 * Delegates to the {@link AgentTaskRunner} stateless agent using the logical task name
 * {@code "tool-summarizer"}. If a matching {@code AgentDefinition} exists it will be used;
 * otherwise the globally configured default stateless agent acts as the fallback.
 * <p>
 * Falls back to a truncated preview if the LLM call fails, so the agent
 * pipeline is never blocked by a summarization failure.
 */
public class LlmToolResultSummarizer implements ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmToolResultSummarizer.class);
    private static final String TASK = StatelessAgentSeeder.TOOL_SUMMARIZER;
    private static final int FALLBACK_PREVIEW_CHARS = 200;

    private final AgentTaskRunner runTask;

    public LlmToolResultSummarizer(AgentTaskRunner runTask) {
        this.runTask = runTask;
    }

    @Override
    public String summarize(String toolName, String fullResult) {
        try {
            String userMessage = "Tool: " + toolName + "\n\nResult:\n" + fullResult;
            String result = runTask.run(TASK, userMessage);
            if (result.isEmpty()) {
                log.warn("LLM summarizer returned empty result for tool '{}' — using fallback", toolName);
                return fallback(toolName, fullResult);
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM summarizer failed for tool '{}': {} — using fallback", toolName, e.getMessage());
            return fallback(toolName, fullResult);
        }
    }

    private String fallback(String toolName, String fullResult) {
        String preview = fullResult.length() <= FALLBACK_PREVIEW_CHARS
                ? fullResult.strip()
                : fullResult.strip().substring(0, FALLBACK_PREVIEW_CHARS) + "…";
        return "[" + toolName + " — summary unavailable]\nPreview: " + preview;
    }
}
