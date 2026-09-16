package ai.mindconnect.agent.runtime.feature.skills;

import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Stored skills and the catalog an agent with skills switched on loads from:
 * the stored ones, the user's own {@code SKILL.md} files and the session
 * project's. Without this feature the runtime knows no skills.
 */
public class SkillsFeature extends ConfigurableFeature {

    private final List<Skill> skills = new ArrayList<>();
    private String userDir;

    /** Saved into the repository on start. */
    public SkillsFeature skill(Skill skill) {
        changing();
        skills.add(skill);
        return this;
    }

    /** A {@code SKILL.md} on the classpath, its name from the front matter or the file name; saved on start. */
    public SkillsFeature skillFromClasspath(String resource) {
        String fileName = java.nio.file.Path.of(resource).getFileName().toString();
        String fallback = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        String content;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SkillsFeature.class.getClassLoader();
        try (java.io.InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("Classpath resource not found: " + resource);
            content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read classpath resource: " + resource, e);
        }
        Skill skill = Skill.fromMarkdown(fallback, content, ai.mindconnect.agent.runtime.skill.SkillSource.MANAGED, null);
        if (skill == null) {
            throw new IllegalArgumentException("Not a usable SKILL.md (no instructions, or a name that is "
                    + "not lower-case letters, digits and dashes): " + resource);
        }
        return skill(skill);
    }

    /** Template of the directory a user's own skills live in; published as {@code skillsUserDir}. */
    public SkillsFeature userDir(String template) {
        changing();
        this.userDir = template;
        return this;
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    protected void install(FeatureContext ctx) {
        if (userDir != null) ctx.property("skillsUserDir", userDir);
        ctx.bean(SkillRepository.class, () -> ctx.require(NamespaceRouting.class).route(SkillRepository.class,
                ns -> ctx.runtime().feature(CoreFeature.class).agentRepositories(ns).skillRepository()));
        ctx.bean(SkillCatalog.class, () -> SkillCatalog.of(
                ctx.require(SkillRepository.class), ctx.property("skillsUserDir").orElse("")));
        ctx.onStart(() -> {
            var repository = ctx.require(SkillRepository.class);
            skills.forEach(repository::save);
        });
    }
}
