package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.runtime.markdown.FrontMatter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A skill: a piece of know-how an agent loads when it needs it, instead of
 * carrying it in its system prompt from the first token on.
 *
 * <p>Only the {@link #name} and the {@link #description} stand in the prompt
 * — a line each, so the model can tell whether a skill is worth having. The
 * {@link #instructions} arrive only when it calls the {@code skill} tool for
 * that name. Ten skills therefore cost ten lines of context until one is
 * used, which is what makes it worth writing them down at length: the
 * checklist a report has to satisfy, the steps of a release, the way this
 * team words a customer reply.
 *
 * <p>The wire format is the one other tools already use, so a skill can be
 * moved between them — a {@code SKILL.md} whose front matter carries the
 * fields and whose body is the instructions:
 *
 * <pre>
 * ---
 * name: weekly-report
 * description: Use when writing the weekly status report for a customer
 * tools: file_read, vector_search
 * ---
 * ## Weekly report
 * 1. Read last week's report under reports/.
 * ...
 * </pre>
 *
 * @param name         how the model names it in the {@code skill} tool call —
 *                     lower case, digits and dashes
 * @param description  when to reach for it, in one line; this is what the
 *                     model decides on, so it says WHEN, not what
 * @param instructions the body, handed over whole on use
 * @param tools        the tools the instructions expect, if any — named to
 *                     the model when the skill is loaded, never granted:
 *                     a skill cannot widen what its agent may do
 * @param source       where it came from; see {@link SkillSource}
 * @param directory    the skill's own directory, for one read off disk —
 *                     the instructions may point at files beside the
 *                     {@code SKILL.md}, and the model needs the path to
 *                     open them. {@code null} for a stored skill
 * @param version      the stored version this was read with — optimistic
 *                     locking, see {@code ai.mindconnect.common.Versions}
 */
public record Skill(
        SkillId id,
        String name,
        String description,
        String instructions,
        List<String> tools,
        boolean enabled,
        SkillSource source,
        String directory,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {

    /** What a name may look like: the model types it, so it stays plain. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** Past this the instructions are a manual, not a skill, and are cut on load. */
    public static final int MAX_INSTRUCTION_CHARS = 50_000;

    public Skill {
        name = normalisedName(name);
        description = description == null ? "" : description.strip();
        instructions = instructions == null ? "" : instructions.strip();
        tools = tools == null ? List.of() : List.copyOf(tools);
        source = source == null ? SkillSource.MANAGED : source;
    }

    /**
     * Trimmed and folded to lower case — the name is machine-readable, and
     * {@code Weekly-Report} typed into the admin form has to reach the same
     * skill as the {@code weekly-report} a model types.
     */
    private static String normalisedName(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    /** A new skill, as created in the admin UI. */
    public static Skill create(String name, String description, String instructions, List<String> tools) {
        Instant now = Instant.now();
        return new Skill(SkillId.random(), name, description, instructions, tools, true,
                SkillSource.MANAGED, null, now, now, null);
    }

    /** Whether {@link #name} is one a model can name and a file system can hold. */
    @JsonIgnore
    public boolean hasValidName() {
        return name != null && NAME.matcher(name).matches();
    }

    /** Refuses a skill whose name no model could type; returns it unchanged otherwise. */
    public Skill validated() {
        if (!hasValidName()) {
            throw new IllegalArgumentException("Skill name '" + name + "' is not usable: use lower-case "
                    + "letters, digits and dashes, starting with a letter or digit, at most 64 characters");
        }
        return this;
    }

    /** This skill as read with, or to be saved against, {@code version}. */
    public Skill withVersion(Long version) {
        return new Skill(id, name, description, instructions, tools, enabled, source, directory,
                createdAt, updatedAt, version);
    }

    /** The same skill marked as coming from {@code source}, out of {@code directory}. */
    public Skill from(SkillSource source, String directory) {
        return new Skill(id, name, description, instructions, tools, enabled, source, directory,
                createdAt, updatedAt, version);
    }

    /** Everything the admin form can change, with {@code updatedAt} moved on. */
    public Skill withFields(String name, String description, String instructions,
                            List<String> tools, boolean enabled) {
        return new Skill(id, name, description, instructions, tools, enabled, source, directory,
                createdAt, Instant.now(), version);
    }

    /** The line the system prompt carries for this skill. */
    public String promptLine() {
        return "- " + name + (description.isBlank() ? "" : ": " + description);
    }

    /**
     * The instructions as the {@code skill} tool hands them over, cut at
     * {@link #MAX_INSTRUCTION_CHARS} — cut rather than refused, because the
     * opening of a long skill is still worth more than an error.
     */
    public String loadedInstructions() {
        if (instructions.length() <= MAX_INSTRUCTION_CHARS) return instructions;
        return instructions.substring(0, MAX_INSTRUCTION_CHARS)
                + "\n\n[…] These instructions are longer than " + MAX_INSTRUCTION_CHARS
                + " characters and were cut here.";
    }

    /**
     * The skill as a {@code SKILL.md}: front matter, then the instructions.
     * What {@link #fromMarkdown} reads back, and what the admin UI offers for
     * download so a skill can be moved into a repository.
     */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder("---\n");
        out.append("name: ").append(name).append('\n');
        if (!description.isBlank()) out.append("description: ").append(description).append('\n');
        if (!tools.isEmpty()) out.append("tools: ").append(String.join(", ", tools)).append('\n');
        return out.append("---\n\n").append(instructions).append('\n').toString();
    }

    /**
     * One {@code SKILL.md}. {@code fallbackName} is the name of the
     * directory (or file) it was found in, used when the front matter names
     * none. {@code null} when there are no instructions to hand over — an
     * empty file is not a skill — or when the name is one no model could
     * type, which the caller reports: a skill nobody can name is a file the
     * author expected to work.
     *
     * <p>A skill off disk is identified by its own name. Nothing else could
     * be stable: a random id would be a different one after every restart,
     * and the admin UI links to it by id.
     */
    public static Skill fromMarkdown(String fallbackName, String content,
                                     SkillSource source, String directory) {
        FrontMatter.Parsed parsed = FrontMatter.parse(content);
        String instructions = parsed.body().strip();
        if (instructions.isEmpty()) return null;
        String name = parsed.get("name", fallbackName);
        if (name == null || name.strip().isEmpty()) name = fallbackName;
        if (name == null) return null;
        String normalised = name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(normalised).matches()) return null;
        List<String> tools = parsed.list("tools");
        return new Skill(SkillId.of(normalised), normalised, parsed.get("description", ""), instructions,
                tools == null ? List.of() : tools, true, source, directory, null, null, null);
    }
}
