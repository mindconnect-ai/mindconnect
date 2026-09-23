package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tools around the skills this installation stores: list them, read
 * one as its {@code SKILL.md}, write one, delete one. What an agent needs
 * to keep its own know-how — the rules a person gives it for clearing out
 * their inbox, the way this team words a reply — without a form.
 *
 * <p>Writes go to the namespace's own store, so a skill written in a chat
 * is the same skill the admin UI shows at {@code /admin/skills} and every
 * agent on {@code ALL} has it on its next turn. Only <em>managed</em>
 * skills can be written: one read off a user's or a project's directory is
 * edited where it lies. Whether an agent may write at all is the binding's
 * decision — a host that wants each save confirmed binds {@code skills_save}
 * with approval, like any other change.
 *
 * <p>Group {@code skills}, beside the {@code skill} tool that loads one:
 * the names are {@code skills_list}, {@code skills_get}, {@code skills_save},
 * {@code skills_delete}.
 */
public final class SkillToolProvider implements MultiToolProvider {

    public static final String GROUP = "skills";
    public static final String LIST = "skills_list";
    public static final String GET = "skills_get";
    public static final String SAVE = "skills_save";
    public static final String DELETE = "skills_delete";

    private static final Set<String> NAMES = new LinkedHashSet<>(List.of(LIST, GET, SAVE, DELETE));

    private SkillRepository skills;

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public Set<String> toolNames() {
        return NAMES;
    }

    @Override
    public void bind(ToolEnvironment env) {
        this.skills = env.get(SkillRepository.class).orElse(null);
    }

    @Override
    public boolean isAvailable() {
        return skills != null;
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        if (skills == null) return Optional.empty();
        return switch (toolName) {
            case LIST -> Optional.of(new ListSkills(skills));
            case GET -> Optional.of(new GetSkill(skills));
            case SAVE -> Optional.of(new SaveSkill(skills));
            case DELETE -> Optional.of(new DeleteSkill(skills));
            default -> Optional.empty();
        };
    }

    // ── the four ─────────────────────────────────────────────────────────────

    static final class ListSkills implements Tool {
        private final SkillRepository skills;

        ListSkills(SkillRepository skills) {
            this.skills = skills;
        }

        @Override public String name() { return LIST; }

        @Override
        public String description() {
            return "Lists the skills this installation stores: name, group, when to use it, whether it is on. "
                    + "Use " + GET + " for the instructions of one, or the skill tool to load it into this chat.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return object(Map.of("group", string("Only the skills of this group, e.g. \"office\"; all when omitted.")));
        }

        @Override
        public String execute(Map<String, Object> args) {
            String group = str(args, "group");
            List<Skill> all = new ArrayList<>(skills.findAll());
            all.sort((a, b) -> a.group().equals(b.group()) ? a.name().compareTo(b.name()) : a.group().compareTo(b.group()));
            StringBuilder out = new StringBuilder();
            for (Skill s : all) {
                if (group != null && !group.equalsIgnoreCase(s.group())) continue;
                out.append("- ").append(s.name()).append(" [").append(s.group()).append("]")
                        .append(s.enabled() ? "" : " (off)")
                        .append(s.description().isBlank() ? "" : " — " + s.description()).append('\n');
            }
            return out.isEmpty() ? (group == null ? "No skills are stored." : "No skills in group \"" + group + "\".")
                    : out.toString();
        }
    }

    static final class GetSkill implements Tool {
        private final SkillRepository skills;

        GetSkill(SkillRepository skills) {
            this.skills = skills;
        }

        @Override public String name() { return GET; }

        @Override
        public String description() {
            return "One stored skill as its SKILL.md: the front matter (name, group, description, tools) and "
                    + "the instructions — to read or to change before " + SAVE + ".";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return object(Map.of("name", string("The skill's name.")), "name");
        }

        @Override
        public String execute(Map<String, Object> args) {
            return find(skills, required(args, "name")).toMarkdown();
        }
    }

    static final class SaveSkill implements Tool {
        private final SkillRepository skills;

        SaveSkill(SkillRepository skills) {
            this.skills = skills;
        }

        @Override public String name() { return SAVE; }

        @Override
        public String description() {
            return "Creates a stored skill, or updates the one of that name; only the fields passed change. "
                    + "The description is the one line a model decides on — say WHEN to use the skill; the "
                    + "instructions are loaded only then, so they may be long. To add a rule to an existing "
                    + "skill, read it with " + GET + " and pass the whole new instructions.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("name", string("Lower-case letters, digits and dashes, at most 64 characters, e.g. "
                    + "\"mail-rules\". Naming an existing skill updates it."));
            props.put("group", string("Which agents' skill this is, e.g. \"office\"; \"general\" when omitted."));
            props.put("description", string("When to reach for the skill, in one line."));
            props.put("instructions", string("The whole body in Markdown. Required for a new skill."));
            props.put("tools", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Tool names the instructions expect; informs the model, grants nothing."));
            props.put("enabled", Map.of("type", "boolean", "description", "false parks the skill without deleting it."));
            return object(props, "name");
        }

        @Override
        public String execute(Map<String, Object> args) {
            String name = required(args, "name").toLowerCase(Locale.ROOT);
            Skill base = skills.findByName(name).orElse(null);
            if (base != null && base.source() != SkillSource.MANAGED) {
                throw new IllegalArgumentException("Skill \"" + name + "\" is read from a "
                        + base.source().name().toLowerCase(Locale.ROOT) + " directory (" + base.directory()
                        + ") and is edited there, not here.");
            }
            String instructions = str(args, "instructions");
            if (instructions == null) {
                if (base == null) throw new IllegalArgumentException("A new skill needs instructions.");
                instructions = base.instructions();
            }
            String description = str(args, "description");
            if (description == null) description = base == null ? "" : base.description();
            String group = str(args, "group");
            if (group == null) group = base == null ? null : base.group();
            List<String> tools = strings(args, "tools");
            if (tools == null) tools = base == null ? List.of() : base.tools();
            Boolean enabled = args.get("enabled") instanceof Boolean b ? b : null;
            if (enabled == null) enabled = base == null || base.enabled();

            Skill skill = base == null
                    ? Skill.create(name, group, description, instructions, tools)
                    : base.withFields(name, description, instructions, tools, enabled).withGroup(group);
            if (base == null && !enabled) skill = skill.withFields(name, description, instructions, tools, false);
            Skill saved = skills.save(skill.validated());
            return (base == null ? "Created" : "Updated") + " skill \"" + saved.name() + "\" [" + saved.group() + "]"
                    + (saved.enabled() ? "" : " (switched off)") + ". It is at /admin/skills/" + saved.id().value()
                    + "; an agent set to all skills has it from its next turn on.";
        }
    }

    static final class DeleteSkill implements Tool {
        private final SkillRepository skills;

        DeleteSkill(SkillRepository skills) {
            this.skills = skills;
        }

        @Override public String name() { return DELETE; }

        @Override
        public String description() {
            return "Deletes the stored skill of that name. Cannot be undone — confirm with the person first. "
                    + "A skill the installation ships comes back on the next start; switch it off instead.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return object(Map.of("name", string("The skill's name.")), "name");
        }

        @Override
        public String execute(Map<String, Object> args) {
            Skill skill = find(skills, required(args, "name"));
            if (skill.source() != SkillSource.MANAGED) {
                throw new IllegalArgumentException("Skill \"" + skill.name() + "\" is read from a directory and is "
                        + "deleted there, not here.");
            }
            skills.deleteById(skill.id());
            return "Deleted skill \"" + skill.name() + "\".";
        }
    }

    // ── the small things every tool needs ────────────────────────────────────

    static Skill find(SkillRepository skills, String name) {
        return skills.findByName(name.strip().toLowerCase(Locale.ROOT)).orElseThrow(() ->
                new IllegalArgumentException("There is no stored skill named \"" + name + "\". "
                        + LIST + " shows the ones there are."));
    }

    static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) schema.put("required", List.of(required));
        return schema;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static String required(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null) throw new IllegalArgumentException("\"" + key + "\" is required.");
        return value;
    }

    static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    static List<String> strings(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).strip());
            return out;
        }
        if (value instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) if (!part.isBlank()) out.add(part.strip());
            return out;
        }
        return null;
    }
}
