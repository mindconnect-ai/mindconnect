package ai.mindconnect.agent.management.api;

import java.util.List;

/**
 * Where a session's tools work.
 *
 * @param working    the directory tools start from; {@code null} for a
 *                   session that has none
 * @param additional further directories its tools may reach
 */
public record SessionDirectories(String working, List<String> additional) {

    public static final SessionDirectories NONE = new SessionDirectories(null, List.of());

    public SessionDirectories {
        additional = List.copyOf(additional);
    }
}
