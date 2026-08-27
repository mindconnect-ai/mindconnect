package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Computes and applies "migrations" — pending imports of bundled initial data
 * (classpath {@code initial-data/**}) against what is currently stored.
 *
 * <p>Where {@code ai.mindconnect.adminui.InitialDataLoader} silently imports new
 * records at startup and skips differing ones, this service surfaces every
 * pending change (new <em>and</em> changed) so an admin can review the diff in
 * the Migrations tab and apply or ignore each one individually.
 *
 * <p>A pending migration is identified by a stable {@link PendingMigration#id()}
 * derived from its entity type and name, so the UI can round-trip an "apply"
 * request without holding server-side state between calls.
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    /**
     * Workflows carry {@code @class} type info and legacy shapes a plain mapper
     * cannot read — same mapper the workflow stores use.
     */
    private static final ObjectMapper WORKFLOW_MAPPER = WorkflowObjectMapperFactory.create();

    /** Whether a stored record is absent (NEW) or present-but-different (CHANGED). */
    public enum Status { NEW, CHANGED }

    /** Supported entity types — also used as the id prefix and UI grouping label. */
    public enum EntityType {
        LLM_CONFIG("llm-config", "LLM Configs"),
        AGENT("agent", "Agents"),
        WORKFLOW("workflow", "Workflows");

        private final String slug;
        private final String label;

        EntityType(String slug, String label) { this.slug = slug; this.label = label; }

        public String slug()  { return slug; }
        public String label() { return label; }

        static EntityType fromSlug(String slug) {
            for (EntityType t : values()) if (t.slug.equals(slug)) return t;
            throw new IllegalArgumentException("Unknown migration entity type: " + slug);
        }
    }

    /**
     * One top-level field that differs. {@code before} is null when the field
     * is new, {@code after} is null when the bundled version dropped it. Values
     * are JSON, pretty-printed for objects and arrays.
     */
    public record FieldDiff(String field, String before, String after) {}

    /**
     * One reviewable change. {@code diffs} is empty for NEW records, where
     * there is nothing to compare against.
     */
    public record PendingMigration(
            EntityType entityType,
            String name,
            Status status,
            List<FieldDiff> diffs) {

        /** Stable, URL-safe id used to round-trip an apply request. */
        public String id() {
            return entityType.slug() + ":" + name;
        }
    }

    private final LlmConfigRepository llmConfigRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final WorkflowDataRepository workflowDataRepository;
    private final ObjectMapper objectMapper;

    public MigrationService(LlmConfigRepository llmConfigRepository,
                            AgentDefinitionRepository agentDefinitionRepository,
                            WorkflowDataRepository workflowDataRepository,
                            ObjectMapper objectMapper) {
        this.llmConfigRepository = llmConfigRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.workflowDataRepository = workflowDataRepository;
        this.objectMapper = objectMapper;
    }

    // ── Read: pending list ──────────────────────────────────────────────────────

    /** All pending migrations across every supported entity type. */
    public List<PendingMigration> pending() {
        List<PendingMigration> all = new ArrayList<>();
        all.addAll(pendingLlmConfigs());
        all.addAll(pendingAgents());
        all.addAll(pendingWorkflows());
        return all;
    }

    private List<PendingMigration> pendingLlmConfigs() {
        List<PendingMigration> result = new ArrayList<>();
        for (Resource resource : scan("classpath:initial-data/llm-configs/*.json")) {
            readEach(resource, LlmConfig.class, objectMapper).ifPresent(incoming -> {
                Optional<LlmConfig> existing = llmConfigRepository.findByName(incoming.name());
                pendingFor(EntityType.LLM_CONFIG, incoming.name(), existing.orElse(null), incoming, objectMapper)
                        .ifPresent(result::add);
            });
        }
        return result;
    }

    private List<PendingMigration> pendingAgents() {
        List<PendingMigration> result = new ArrayList<>();
        for (Resource resource : scan("classpath:initial-data/agent-definitions/*.json")) {
            readEach(resource, AgentDefinition.class, objectMapper).ifPresent(incoming -> {
                Optional<AgentDefinition> existing =
                        agentDefinitionRepository.findByName(incoming.namespace(), incoming.name());
                pendingFor(EntityType.AGENT, incoming.name(), existing.orElse(null), incoming, objectMapper)
                        .ifPresent(result::add);
            });
        }
        return result;
    }

    /**
     * Workflows are stored under a file id, not an in-record name, so the seed's
     * file name (minus {@code .json}) is the identity — the same id
     * {@code FileCopyInitialDataInstaller} installs it under at startup.
     */
    private List<PendingMigration> pendingWorkflows() {
        List<PendingMigration> result = new ArrayList<>();
        for (Resource resource : scan("classpath:initial-data/workflows/*.json")) {
            String id = fileId(resource);
            if (id == null) continue;
            readEach(resource, WorkflowData.class, WORKFLOW_MAPPER).ifPresent(incoming -> {
                Optional<WorkflowData> existing = workflowDataRepository.findById(id);
                pendingFor(EntityType.WORKFLOW, id, existing.orElse(null), incoming, WORKFLOW_MAPPER)
                        .ifPresent(result::add);
            });
        }
        return result;
    }

    /** NEW if no stored record, CHANGED if it differs, empty if identical. */
    private Optional<PendingMigration> pendingFor(EntityType type, String name,
                                                  Object existing, Object incoming, ObjectMapper mapper) {
        if (existing == null) {
            return Optional.of(new PendingMigration(type, name, Status.NEW, List.of()));
        }
        List<FieldDiff> diffs = diffFields(existing, incoming, mapper);
        if (diffs == null) return Optional.empty(); // up to date
        return Optional.of(new PendingMigration(type, name, Status.CHANGED, diffs));
    }

    // ── Write: apply one ────────────────────────────────────────────────────────

    /**
     * Applies the pending migration with the given {@link PendingMigration#id()}
     * by saving the bundled (classpath) version over the stored one.
     *
     * @return true if a matching pending migration was found and applied
     */
    public boolean apply(String migrationId) {
        int sep = migrationId.indexOf(':');
        if (sep < 0) throw new IllegalArgumentException("Malformed migration id: " + migrationId);
        EntityType type = EntityType.fromSlug(migrationId.substring(0, sep));
        String name = migrationId.substring(sep + 1);

        return switch (type) {
            case LLM_CONFIG -> applyLlmConfig(name);
            case AGENT -> applyAgent(name);
            case WORKFLOW -> applyWorkflow(name);
        };
    }

    /** Applies every currently-pending migration. Returns the number applied. */
    public int applyAll() {
        int applied = 0;
        for (PendingMigration p : pending()) {
            if (apply(p.id())) applied++;
        }
        return applied;
    }

    private boolean applyLlmConfig(String name) {
        for (Resource resource : scan("classpath:initial-data/llm-configs/*.json")) {
            Optional<LlmConfig> incoming = readEach(resource, LlmConfig.class, objectMapper)
                    .filter(c -> c.name().equals(name));
            if (incoming.isPresent()) {
                llmConfigRepository.save(incoming.get());
                log.info("Applied migration for LLM config '{}'", name);
                return true;
            }
        }
        return false;
    }

    private boolean applyAgent(String name) {
        for (Resource resource : scan("classpath:initial-data/agent-definitions/*.json")) {
            Optional<AgentDefinition> incoming = readEach(resource, AgentDefinition.class, objectMapper)
                    .filter(a -> a.name().equals(name));
            if (incoming.isPresent()) {
                agentDefinitionRepository.save(incoming.get());
                log.info("Applied migration for agent '{}'", name);
                return true;
            }
        }
        return false;
    }

    private boolean applyWorkflow(String id) {
        for (Resource resource : scan("classpath:initial-data/workflows/*.json")) {
            if (!id.equals(fileId(resource))) continue;
            Optional<WorkflowData> incoming = readEach(resource, WorkflowData.class, WORKFLOW_MAPPER);
            if (incoming.isPresent()) {
                workflowDataRepository.save(id, incoming.get());
                log.info("Applied migration for workflow '{}'", id);
                return true;
            }
        }
        return false;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** The seed's identity: its file name without the {@code .json} extension. */
    private static String fileId(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".json")) return null;
        return filename.substring(0, filename.length() - ".json".length());
    }

    private <T> Optional<T> readEach(Resource resource, Class<T> type, ObjectMapper mapper) {
        try {
            return Optional.of(mapper.readValue(resource.getInputStream(), type));
        } catch (Exception e) {
            log.warn("Failed to read {} from {}: {}", type.getSimpleName(), resource.getFilename(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Field-level before/after diff between stored and incoming, or {@code null}
     * if identical (ignoring {@code createdAt} / {@code updatedAt}).
     */
    private List<FieldDiff> diffFields(Object stored, Object incoming, ObjectMapper mapper) {
        try {
            JsonNode storedNode   = mapper.valueToTree(stored);
            JsonNode incomingNode = mapper.valueToTree(incoming);
            for (String field : List.of("createdAt", "updatedAt")) {
                ((ObjectNode) storedNode).remove(field);
                ((ObjectNode) incomingNode).remove(field);
            }
            if (storedNode.equals(incomingNode)) return null;

            // Incoming's field order first, then anything only the stored version has.
            Set<String> fields = new LinkedHashSet<>();
            incomingNode.fieldNames().forEachRemaining(fields::add);
            storedNode.fieldNames().forEachRemaining(fields::add);

            List<FieldDiff> diffs = new ArrayList<>();
            for (String field : fields) {
                JsonNode oldVal = storedNode.get(field);
                JsonNode newVal = incomingNode.get(field);
                if (oldVal == null ? newVal == null : oldVal.equals(newVal)) continue;
                diffs.add(new FieldDiff(field, render(oldVal), render(newVal)));
            }
            if (diffs.isEmpty()) {
                diffs.add(new FieldDiff("(structural difference)", null, null));
            }
            return diffs;
        } catch (Exception e) {
            return List.of(new FieldDiff("(could not diff)", null, e.getMessage()));
        }
    }

    /** Containers pretty-printed so the diff table stays readable; scalars as-is. */
    private static String render(JsonNode value) {
        if (value == null) return null;
        return value.isContainerNode() ? value.toPrettyString() : value.asText();
    }

    private List<Resource> scan(String pattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            return List.of(resolver.getResources(pattern));
        } catch (Exception e) {
            log.debug("No resources found for pattern {}: {}", pattern, e.getMessage());
            return List.of();
        }
    }
}
