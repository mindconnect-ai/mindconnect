package ai.mindconnect.agent.loop.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something that happens while an agent works, told as it happens — for
 * whoever watches: a client's stream, a trace, a progress display.
 * Observation only: nothing a watcher does with an event changes the work.
 *
 * <p>Every event has one generic form beside its typed one: a kind and the
 * values that belong to it. In that form it is sent, kept and passed on by
 * somebody who does not know the kind.
 *
 * <p>An open family. What the loop itself reports is a
 * {@link TurnStreamEvent}; whoever runs the loop adds what only it knows.
 */
public interface StreamEvent {

    /** What happened, by its name on a stream, e.g. {@code token}. */
    String kind();

    /** What belongs to the kind, in values that survive being sent: strings, numbers, booleans, lists and maps. */
    Map<String, Object> toDetails();

    /** A map of the pairs given, in their order; a value may be absent. */
    static Map<String, Object> detailsOf(Object... pairs) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            details.put((String) pairs[i], pairs[i + 1]);
        }
        return details;
    }
}
