package ai.mindconnect.agent.adapter.rule;

import ai.mindconnect.agent.port.out.ToolResultSummarizer;

/**
 * A fast, rule-based implementation of {@link ToolResultSummarizer}.
 * <p>
 * Does not call an LLM — produces a stub summary from simple heuristics:
 * estimated token count, line count, and a short content preview.
 * <p>
 * This is the default implementation. An LLM-based summarizer can be wired in
 * as an alternative when higher-quality summaries are needed.
 */
public class RuleBasedToolResultSummarizer implements ToolResultSummarizer {

    private static final int PREVIEW_CHARS = 500;

    @Override
    public String summarize(String toolName, String fullResult) {
        int chars = fullResult.length();
        int estimatedTokens = chars / 4;
        int lines = countLines(fullResult);
        String preview = buildPreview(fullResult);

        // The hint is for the MODEL: the full output is gone from its window
        // (not from the store), and re-running the tool is the way back.
        return String.format(
                "[%s — %d lines, ~%d tokens — result compressed; call the tool again if you need the full output]\nPreview: %s",
                toolName, lines, estimatedTokens, preview
        );
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private String buildPreview(String text) {
        String trimmed = text.strip();
        if (trimmed.length() <= PREVIEW_CHARS) {
            return trimmed;
        }
        // Try to cut at a word boundary
        int cutAt = trimmed.lastIndexOf(' ', PREVIEW_CHARS);
        if (cutAt < PREVIEW_CHARS / 2) cutAt = PREVIEW_CHARS;
        return trimmed.substring(0, cutAt) + "…";
    }
}
