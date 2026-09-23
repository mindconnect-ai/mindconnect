package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.tool.Tool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The game master's way of saving the game: called after a turn that changed
 * something, it writes the character's state into the adventure that belongs
 * to the session the tool was resolved for. Adventures are keyed by their
 * session, so the model never handles ids and the lookup is one read.
 */
final class StateTool implements Tool {

    private final DemoStore<Adventure> adventures;
    private final String sessionId;

    StateTool(DemoStore<Adventure> adventures, String sessionId) {
        this.adventures = adventures;
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "demo_state";
    }

    @Override
    public String description() {
        return "Saves the player's state for this adventure: hitPoints, inventory (comma-separated), note "
                + "(one line: where the player is and what is going on) and status (RUNNING, WON or LOST). "
                + "Call it after every turn that changed something.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "hitPoints", Map.of("type", "integer", "description", "Current hit points"),
                        "inventory", Map.of("type", "string", "description", "Everything the player carries, comma-separated"),
                        "note", Map.of("type", "string", "description", "Where the player is and what is going on, one line"),
                        "status", Map.of("type", "string", "enum", List.of("RUNNING", "WON", "LOST"))),
                "required", List.of("hitPoints", "status"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        // Adventures are keyed by their session, so the one of this session is one lookup.
        Optional<Adventure> current = sessionId == null ? Optional.empty() : adventures.find(sessionId);
        if (current.isEmpty()) {
            return "No adventure is running in this session; nothing saved.";
        }
        int hitPoints = arguments.get("hitPoints") instanceof Number n ? n.intValue()
                : Integer.parseInt(String.valueOf(arguments.getOrDefault("hitPoints", "10")));
        List<String> inventory = Arrays.stream(String.valueOf(arguments.getOrDefault("inventory", "")).split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        var state = new Adventure.CharacterState(hitPoints, inventory,
                String.valueOf(arguments.getOrDefault("note", "")), String.valueOf(arguments.getOrDefault("status", "RUNNING")));
        adventures.save(current.get().withState(state));
        return "Saved: " + hitPoints + " HP, " + inventory.size() + " item(s), status " + state.status() + ".";
    }
}
