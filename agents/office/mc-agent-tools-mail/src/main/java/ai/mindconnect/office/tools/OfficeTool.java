package ai.mindconnect.office.tools;

import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * One Office tool: a name, what it is for, its parameters, and the work.
 *
 * <p><b>A failure is a result, not an exception.</b> The runtime hands a tool
 * result to the model; a thrown exception is a stack trace nobody can act on.
 * Every error comes back as {@code Error: …} with the sentence that says what
 * to do about it — a wrong account, a folder that is not there, a provider
 * that did not answer.
 */
public final class OfficeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(OfficeTool.class);

    /** Dates and times as the tools write them: in the server's zone, to the minute. */
    public static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final Function<Map<String, Object>, String> work;

    public OfficeTool(String name, String description, Map<String, Object> schema,
               Function<Map<String, Object>, String> work) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.work = Objects.requireNonNull(work, "work");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            return work.apply(arguments == null ? Map.of() : arguments);
        } catch (Refused e) {
            return "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("{} failed", name, e);
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "Error: " + name + " failed: " + why;
        }
    }

    /** A call that cannot be done as asked; its message is what the model is told. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    // ── schema ──────────────────────────────────────────────────────────────

    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        if (required.length > 0) out.put("required", List.of(required));
        return out;
    }

    /** Properties in the order given — the order a model reads them in. */
    public static Map<String, Object> props(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> oneOf(List<String> values, String description) {
        return values.isEmpty() ? string(description)
                : Map.of("type", "string", "enum", values, "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> strings(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    // ── arguments ───────────────────────────────────────────────────────────

    public static String str(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    public static String required(Map<String, Object> args, String name) {
        String value = str(args, name);
        if (value == null) throw new Refused("\"" + name + "\" is required.");
        return value;
    }

    public static boolean flag(Map<String, Object> args, String name, boolean fallback) {
        Object value = args.get(name);
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value).strip());
    }

    public static int number(Map<String, Object> args, String name, int fallback, int max) {
        Object value = args.get(name);
        int parsed;
        try {
            parsed = value instanceof Number n ? n.intValue()
                    : value == null ? fallback : Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new Refused("\"" + name + "\" is a whole number, not \"" + value + "\".");
        }
        return Math.max(0, Math.min(parsed, max));
    }

    /** A list argument: a JSON array, or one comma-separated string. */
    public static List<String> list(Map<String, Object> args, String name) {
        Object value = args.get(name);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) if (item != null && !String.valueOf(item).isBlank()) out.add(String.valueOf(item).strip());
        } else if (value != null) {
            for (String part : String.valueOf(value).split(",")) if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }

    /**
     * A point in time from what a model writes: {@code 2026-09-22} (the start
     * of that day here), {@code 2026-09-22T14:00} (local), or with an offset.
     */
    public static Instant instant(Map<String, Object> args, String name, ZoneId zone) {
        String value = str(args, name);
        if (value == null) return null;
        try {
            if (value.length() == 10) return LocalDate.parse(value).atStartOfDay(zone).toInstant();
            if (value.endsWith("Z") || value.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new Refused("\"" + name + "\" is a date like 2026-09-22 or a time like 2026-09-22T14:00, not \""
                    + value + "\".");
        }
    }

    public static LocalDate date(Map<String, Object> args, String name) {
        String value = str(args, name);
        if (value == null) return null;
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            throw new Refused("\"" + name + "\" is a date like 2026-09-22, not \"" + value + "\".");
        }
    }

    public static String when(Instant instant, ZoneId zone) {
        return instant == null ? "" : WHEN.format(instant.atZone(zone));
    }

    /** Text cut at {@code max} characters, saying so. */
    public static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n[… cut at " + max + " characters]";
    }
}
