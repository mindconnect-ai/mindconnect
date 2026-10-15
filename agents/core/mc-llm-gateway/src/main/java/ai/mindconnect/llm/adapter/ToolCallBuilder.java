package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reassembles one tool call from the fragments a stream delivers: the id and
 * the name arrive with the first fragment, the arguments as pieces of one
 * JSON object. Every provider streams a call this way; which event carries
 * which piece differs, and that stays in the gateway.
 */
public final class ToolCallBuilder {

    private String id;
    private String name;
    private final StringBuilder argsJson = new StringBuilder();

    /** Takes whatever the fragment carries; a {@code null} part leaves what is already known. */
    public void feed(String id, String name, String argsFrag) {
        if (id != null) this.id = id;
        if (name != null) this.name = name;
        if (argsFrag != null) this.argsJson.append(argsFrag);
    }

    /**
     * The call, or {@code null} while the id or the name is still missing.
     * Arguments that are not a JSON object become an empty map: the call is
     * still reported, so a trace shows that the model made it.
     */
    public ToolCall build(ObjectMapper mapper) {
        if (id == null || name == null) return null;
        try {
            Map<String, Object> args = argsJson.isEmpty()
                    ? new HashMap<>()
                    : mapper.readValue(argsJson.toString(), new TypeReference<>() {});
            return new ToolCall(id, name, args);
        } catch (Exception e) {
            return new ToolCall(id, name, new HashMap<>());
        }
    }

    /** Every complete call among {@code builders}, in their order. */
    public static List<ToolCall> buildAll(Collection<ToolCallBuilder> builders, ObjectMapper mapper) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallBuilder builder : builders) {
            ToolCall built = builder.build(mapper);
            if (built != null) toolCalls.add(built);
        }
        return toolCalls;
    }
}
