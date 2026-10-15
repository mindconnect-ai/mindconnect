package ai.mindconnect.llm.adapter;

import ai.mindconnect.common.Cancellation;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Reads a server-sent event stream block by block: the lines up to a blank
 * line are one event, as the SSE specification defines it. Every provider
 * streams its answer this way, so the block is what a gateway parses and
 * what a trace keeps verbatim. The caller's cancellation is checked before
 * every line, so an aborted call stops at the next line that arrives instead
 * of draining the stream.
 */
public final class SseBlockReader {

    private final BufferedReader reader;
    private final Cancellation cancellation;
    private final StringBuilder block = new StringBuilder();
    private boolean cancelled;
    private String trailing;

    public SseBlockReader(BufferedReader reader, Cancellation cancellation) {
        this.reader = reader;
        this.cancellation = cancellation;
    }

    /** The next complete block, or {@code null} once the stream ended or the caller cancelled. */
    public String next() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (cancellation.isCancelled()) {
                cancelled = true;
                return null;
            }
            if (line.isEmpty()) {
                if (block.length() > 0) {
                    String complete = block.toString();
                    block.setLength(0);
                    return complete;
                }
                continue;
            }
            if (block.length() > 0) block.append('\n');
            block.append(line);
        }
        if (block.length() > 0) {
            trailing = block.toString();
            block.setLength(0);
        }
        return null;
    }

    /** Whether reading stopped because the caller cancelled, not because the stream ended. */
    public boolean cancelled() {
        return cancelled;
    }

    /**
     * A block the stream ended on without the blank line that closes it, or
     * {@code null}. Some providers omit the last blank line; the block is
     * still part of the answer.
     */
    public String trailing() {
        return trailing;
    }

    /** The payload of the block's {@code data:} line, trimmed, or {@code null} when it has none. */
    public static String dataLine(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("data: ")) return line.substring(6).trim();
        }
        return null;
    }

    /** The block's {@code event:} name, or an empty string when it has none. */
    public static String eventType(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("event: ")) return line.substring(7).trim();
        }
        return "";
    }
}
