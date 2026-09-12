package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code skill} tool: hands the model the full instructions of one
 * skill, by name.
 *
 * <p>This is the second half of what makes skills cheap. The system prompt
 * carries a line per skill — name and description — and nothing else; the
 * body arrives only when the model asks for it, and only for the one skill
 * it asked about. An installation can therefore write its skills as long as
 * they need to be without every chat paying for them.
 *
 * <p>A skill read off disk is handed over with its directory named, so the
 * files beside the {@code SKILL.md} — a template, a checklist, a script —
 * can be opened with the file tools. Naming tools a skill expects is not a
 * grant: the agent's own bindings still decide what it may call, and a
 * skill that asks for a tool the agent does not have says so plainly rather
 * than letting the model discover it one failed call later.
 */
public final class SkillTool implements Tool {

    public static final String NAME = "skill";

    private final SkillCatalog catalog;
    /** The names this agent's binding names; empty = every skill there is. */
    private final List<String> names;
    private final UserId userId;
    private final String workingDir;

    public SkillTool(SkillCatalog catalog, List<String> names, UserId userId, String workingDir) {
        this.catalog = catalog;
        this.names = names == null ? List.of() : List.copyOf(names);
        this.userId = userId;
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        StringBuilder text = new StringBuilder(
                "Loads the full instructions of one skill — a way of working this installation, "
                + "this user or this project has written down. Call it BEFORE doing work a skill "
                + "covers, then follow what it says.");
        List<Skill> available = available();
        if (!available.isEmpty()) {
            text.append(" Available skills: ");
            for (int i = 0; i < available.size(); i++) {
                Skill skill = available.get(i);
                if (i > 0) text.append("; ");
                text.append(skill.name());
                if (!skill.description().isBlank()) text.append(" — ").append(skill.description());
            }
            text.append('.');
        }
        return text.toString();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "The name of the skill to load, exactly as listed.");
        List<String> names = available().stream().map(Skill::name).toList();
        // The names as an enum when there are any: a model cannot then invent
        // one, and the round that would have been spent on the error message
        // is spent on the work instead.
        if (!names.isEmpty()) name.put("enum", names);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", name));
        schema.put("required", List.of("name"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("name");
        if (!(raw instanceof String wanted) || wanted.isBlank()) {
            return "Error: 'name' must be the name of a skill. " + availableSummary();
        }
        return catalog.find(names, userId, workingDir, wanted)
                .map(this::render)
                .orElse("No skill named '" + wanted.strip() + "'. " + availableSummary());
    }

    /** The skill as the model receives it: what it is, then the instructions themselves. */
    private String render(Skill skill) {
        StringBuilder out = new StringBuilder("# Skill: ").append(skill.name()).append('\n');
        if (!skill.description().isBlank()) {
            out.append(skill.description()).append('\n');
        }
        out.append("Source: ").append(sourceLabel(skill)).append('\n');
        if (skill.directory() != null) {
            out.append("Directory: `").append(skill.directory()).append("` — files this skill refers to "
                    + "are in there; open them by path with the file tools.\n");
        }
        if (!skill.tools().isEmpty()) {
            out.append("Tools it expects: ").append(String.join(", ", skill.tools()))
                    .append(" — use the ones you have; this list grants you nothing.\n");
        }
        out.append("\nFollow these instructions for this task. They are more specific than your "
                + "general guidance and take precedence over it, but never over the user's own "
                + "request or the rules you work under.\n\n")
                .append(skill.loadedInstructions());
        return out.toString();
    }

    private static String sourceLabel(Skill skill) {
        return switch (skill.source()) {
            case MANAGED -> "this installation";
            case USER -> "the user's own skills";
            case PROJECT -> "the project in the working directory";
        };
    }

    private List<Skill> available() {
        return catalog.availableTo(names, userId, workingDir);
    }

    private String availableSummary() {
        List<String> names = available().stream().map(Skill::name).toList();
        return names.isEmpty() ? "This agent has no skills." : "Available: " + String.join(", ", names) + ".";
    }
}
