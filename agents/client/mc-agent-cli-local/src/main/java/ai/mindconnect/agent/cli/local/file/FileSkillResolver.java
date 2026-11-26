package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;

/**
 * Skills as folders: {@code .mc/skills/<name>/SKILL.md} in the working
 * directory and in the home. Every agent is offered all of them; where both
 * places have a skill of one name, the working directory's is the skill.
 *
 * <pre>
 * ---
 * description: How a briefing is laid out. Load before writing one.
 * allowed-tools: write_file
 * ---
 * A briefing starts with …
 * </pre>
 */
public final class FileSkillResolver implements SkillResolver {

    private static final String SKILL_FILE = "SKILL.md";

    private final HarnessDirectories directories;

    public FileSkillResolver(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public List<SkillSummary> skills(Session session) {
        Map<String, SkillSummary> byName = new LinkedHashMap<>();
        for (Path config : directories.configDirectories(session)) {
            for (String name : skillNames(config.resolve("skills"))) {
                if (!byName.containsKey(name)) {
                    read(config, name).ifPresent(skill ->
                            byName.put(name, new SkillSummary(name, skill.description())));
                }
            }
        }
        return List.copyOf(byName.values());
    }

    @Override
    public Optional<SkillDefinition> resolve(String skillName, Session session) {
        return directories.configDirectories(session).stream()
                .map(config -> read(config, skillName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<SkillDefinition> read(Path config, String name) {
        return FrontMatterDocument.read(config.resolve("skills").resolve(name).resolve(SKILL_FILE))
                .map(document -> new SkillDefinition(name, document.value("description", ""),
                        document.body(), document.list("allowed-tools")));
    }

    private static List<String> skillNames(Path skills) {
        if (!Files.isDirectory(skills)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(skills)) {
            return new ArrayList<>(folders.filter(Files::isDirectory)
                    .map(folder -> folder.getFileName().toString()).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + skills, e);
        }
    }
}
