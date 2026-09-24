package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The records the classpath ships under {@code initial-data/} — in every jar,
 * the app's own and every module or extension added to it — read into the
 * types the stores save. The one place that knows the layout and how each
 * kind is parsed; the start-up seeding, the per-namespace seeding and the
 * Migrations screen all read through here.
 *
 * <ul>
 *   <li>{@code llm-configs/*.json} — an {@link LlmConfig}, identified by its {@code name};</li>
 *   <li>{@code agent-definitions/*.json} — an {@link AgentDefinition}, by its {@code name};</li>
 *   <li>{@code skills/*.md} — a {@code SKILL.md}, by the name in its front matter
 *       (the file name when it has none);</li>
 *   <li>{@code workflows/*.json} — a {@link WorkflowData}, by its file name without
 *       {@code .json}: workflows are stored under an id, not an in-record name.</li>
 * </ul>
 * A file that cannot be read is logged and left out. Nothing is cached: the
 * callers read seldom, and each gets fresh instances to save.
 */
public final class BundledSeeds {

    private static final Logger log = LoggerFactory.getLogger(BundledSeeds.class);

    /** Where the seeds are: every jar's {@code initial-data/}. */
    public static final String CLASSPATH = "classpath*:initial-data/";

    /**
     * Workflows carry {@code @class} type info and legacy shapes a plain mapper
     * cannot read — same mapper the workflow stores use.
     */
    static final ObjectMapper WORKFLOW_MAPPER = WorkflowObjectMapperFactory.create();

    /** The kinds of seed, with the slug the Migrations screen and the installed-seed records use. */
    public enum Kind {
        LLM_CONFIG("llm-config", "llm-configs/*.json"),
        AGENT("agent", "agent-definitions/*.json"),
        SKILL("skill", "skills/*.md"),
        WORKFLOW("workflow", "workflows/*.json");

        private final String slug;
        private final String pattern;

        Kind(String slug, String pattern) {
            this.slug = slug;
            this.pattern = pattern;
        }

        public String slug() {
            return slug;
        }
    }

    /** One shipped record: its kind, its identity in the store, and the record itself. */
    public record Seed<T>(Kind kind, String name, T record) {
        public Seed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(record, "record");
        }

        /** {@code kind:name} — the Migrations screen's id for the record. */
        public String key() {
            return kind.slug() + ":" + name;
        }
    }

    private final ObjectMapper objectMapper;
    private final String location;

    /** The seeds of {@code initial-data/} on the classpath. */
    public BundledSeeds(ObjectMapper objectMapper) {
        this(objectMapper, CLASSPATH);
    }

    /**
     * @param location where the kind folders are, ending in {@code /} —
     *                 {@code classpath*:initial-data/}, or another root a test ships
     */
    public BundledSeeds(ObjectMapper objectMapper, String location) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.location = location.endsWith("/") ? location : location + "/";
    }

    /** Every seed of every kind, LLM configs first — agents refer to them. */
    public List<Seed<?>> all() {
        List<Seed<?>> all = new ArrayList<>();
        all.addAll(llmConfigs());
        all.addAll(agents());
        all.addAll(skills());
        all.addAll(workflows());
        return all;
    }

    public List<Seed<LlmConfig>> llmConfigs() {
        List<Seed<LlmConfig>> seeds = new ArrayList<>();
        for (Resource resource : scan(Kind.LLM_CONFIG)) {
            read(resource, LlmConfig.class, objectMapper)
                    .ifPresent(config -> seeds.add(new Seed<>(Kind.LLM_CONFIG, config.name(), config)));
        }
        return seeds;
    }

    public List<Seed<AgentDefinition>> agents() {
        List<Seed<AgentDefinition>> seeds = new ArrayList<>();
        for (Resource resource : scan(Kind.AGENT)) {
            read(resource, AgentDefinition.class, objectMapper)
                    .ifPresent(agent -> seeds.add(new Seed<>(Kind.AGENT, agent.name(), agent)));
        }
        return seeds;
    }

    /**
     * Shipped skills, as {@link SkillSource#MANAGED}. A file without
     * instructions, or with a name that is not lower-case letters, digits and
     * dashes, is logged and left out.
     */
    public List<Seed<Skill>> skills() {
        List<Seed<Skill>> seeds = new ArrayList<>();
        for (Resource resource : scan(Kind.SKILL)) {
            String fileName = resource.getFilename() == null ? "skill" : resource.getFilename();
            String fallback = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
            try {
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Skill skill = Skill.fromMarkdown(fallback, content, SkillSource.MANAGED, null);
                if (skill == null) {
                    log.warn("Initial skill {} has no instructions, or a name that is not lower-case "
                            + "letters, digits and dashes — skipping", fileName);
                    continue;
                }
                seeds.add(new Seed<>(Kind.SKILL, skill.name(), skill));
            } catch (Exception e) {
                log.warn("Failed to read skill from {}: {}", fileName, e.getMessage());
            }
        }
        return seeds;
    }

    public List<Seed<WorkflowData>> workflows() {
        List<Seed<WorkflowData>> seeds = new ArrayList<>();
        for (Resource resource : scan(Kind.WORKFLOW)) {
            String id = fileId(resource);
            if (id == null) continue;
            read(resource, WorkflowData.class, WORKFLOW_MAPPER)
                    .ifPresent(workflow -> seeds.add(new Seed<>(Kind.WORKFLOW, id, workflow)));
        }
        return seeds;
    }

    public Optional<LlmConfig> llmConfig(String name) {
        return llmConfigs().stream().filter(seed -> seed.name().equals(name)).map(Seed::record).findFirst();
    }

    public Optional<AgentDefinition> agent(String name) {
        return agents().stream().filter(seed -> seed.name().equals(name)).map(Seed::record).findFirst();
    }

    public Optional<WorkflowData> workflow(String id) {
        for (Resource resource : scan(Kind.WORKFLOW)) {
            if (id.equals(fileId(resource))) return read(resource, WorkflowData.class, WORKFLOW_MAPPER);
        }
        return Optional.empty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** The seed's identity: its file name without the {@code .json} extension. */
    private static String fileId(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".json")) return null;
        return filename.substring(0, filename.length() - ".json".length());
    }

    private static <T> Optional<T> read(Resource resource, Class<T> type, ObjectMapper mapper) {
        try {
            return Optional.of(mapper.readerFor(type).<T>readValue(resource.getInputStream()));
        } catch (Exception e) {
            log.warn("Failed to read {} from {}: {}", type.getSimpleName(), resource.getFilename(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<Resource> scan(Kind kind) {
        String pattern = location + kind.pattern;
        try {
            return List.of(new PathMatchingResourcePatternResolver().getResources(pattern));
        } catch (Exception e) {
            log.debug("No resources found for pattern {}: {}", pattern, e.getMessage());
            return List.of();
        }
    }
}
