package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class CurrentDateTimeTool implements Tool {

    @Override
    public String name() {
        return "get_current_datetime";
    }

    @Override
    public String description() {
        return "Returns the current date and time in ISO-8601 format with timezone.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", new String[0]
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
