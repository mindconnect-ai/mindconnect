package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The time zone a person lives in — what a time without an offset means when
 * they say it, and how a time is written back to them.
 *
 * <p>A server runs in one zone (often UTC) and its users in many. A tool that
 * reads {@code 2026-09-25T16:16} in the server's zone books a train in Zurich
 * two hours late; so a tool that reads or writes a local time asks this, per
 * call, for the user the call runs for ({@link ToolCallScope#userId()}), and
 * the system prompt states the time in the same zone.
 *
 * <p>Tools reach it through the {@link ToolEnvironment} ({@link #of}); a host
 * that knows its users registers one there, everything else gets the JVM's
 * zone — which is exactly what the tools did before.
 */
@FunctionalInterface
public interface TimeZones {

    /**
     * The zone of {@code user}; the installation's default for a user who has
     * none, and for {@code null} — a run on nobody's behalf. Never null.
     */
    ZoneId zoneOf(UserId user);

    /** The same zone for everyone — a library embedding, a test. */
    static TimeZones fixed(ZoneId zone) {
        if (zone == null) throw new IllegalArgumentException("zone must not be null");
        return user -> zone;
    }

    /** The JVM's zone for everyone, read on every call: what a host without users gets. */
    static TimeZones system() {
        return user -> ZoneId.systemDefault();
    }

    /** The host's resolver from the tool environment, or {@link #system()} when it has none. */
    static TimeZones of(ToolEnvironment env) {
        return env == null ? system() : env.get(TimeZones.class).orElseGet(TimeZones::system);
    }

    /**
     * A zone id as a person or a browser writes it — {@code Europe/Zurich},
     * {@code America/New_York}, {@code UTC} — or empty when it is none.
     * Blank, unknown and malformed ids are all empty; nothing throws.
     */
    static Optional<ZoneId> parse(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return Optional.of(ZoneId.of(id.strip()));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
