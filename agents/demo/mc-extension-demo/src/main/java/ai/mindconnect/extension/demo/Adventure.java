package ai.mindconnect.extension.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A saved game: which scenario, whose, in which session of the runtime, what
 * was said so far, and the character's state as the game master last saved
 * it through {@code demo_state}. Kept in the extension's own store, so a
 * player can leave and come back — even after a restart.
 */
record Adventure(String id, String scenarioId, String scenarioTitle, String sessionId, String userId,
                 Instant startedAt, Instant updatedAt, List<Line> story, CharacterState state) {

    /** One line of the story: who spoke, and what. */
    record Line(String who, String text) {
        static Line master(String text) {
            return new Line("Game master", text);
        }

        static Line player(String text) {
            return new Line("You", text);
        }
    }

    /** What the game master saves: hit points, inventory, where things stand, and whether it is over. */
    record CharacterState(int hitPoints, List<String> inventory, String note, String status) {
        static final CharacterState FRESH = new CharacterState(10, List.of(), "", "RUNNING");

        CharacterState {
            inventory = inventory == null ? List.of() : List.copyOf(inventory);
            note = note == null ? "" : note;
            status = status == null || status.isBlank() ? "RUNNING" : status.toUpperCase();
        }

        boolean isOver() {
            return !"RUNNING".equals(status);
        }
    }

    Adventure {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(sessionId, "sessionId");
        story = story == null ? List.of() : List.copyOf(story);
        state = state == null ? CharacterState.FRESH : state;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
    }

    /** Keyed by the session: one adventure per session, and the state tool finds its adventure without a scan. */
    static Adventure start(Scenario scenario, String sessionId, String userId) {
        Instant now = Instant.now();
        return new Adventure(sessionId, scenario.id(), scenario.title(), sessionId, userId,
                now, now, List.of(), CharacterState.FRESH);
    }

    Adventure withLines(Line... lines) {
        List<Line> next = new ArrayList<>(story);
        next.addAll(List.of(lines));
        return new Adventure(id, scenarioId, scenarioTitle, sessionId, userId, startedAt, Instant.now(), next, state);
    }

    Adventure withState(CharacterState next) {
        return new Adventure(id, scenarioId, scenarioTitle, sessionId, userId, startedAt, Instant.now(), story, next);
    }
}
