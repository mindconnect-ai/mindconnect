package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Computes and applies "migrations" — pending imports of bundled initial data
 * (classpath {@code initial-data/**}) against what is currently stored.
 *
 * <p>Where {@code ai.mindconnect.adminui.InitialDataLoader} silently imports new
 * records at startup and skips differing ones, this service surfaces every
 * pending change (new <em>and</em> changed) so an admin can review the diff in
 * the Migrations tab and apply or ignore each one individually — as a whole, or
 * one field at a time (see {@link #applyField}).
 *
 * <p>An LLM config's {@code apiKey} is compared by what it resolves to, not by
 * its stored form: the store encrypts keys ({@code enc:…}) and seeds carry
 * {@code ${ENV_VAR}} placeholders, so a byte-wise diff would flag every config
 * forever. Key values are never shown in the diff, only whether they differ.
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
    private final EncryptionHelper encryption;

    /**
     * @param encryption decrypts stored {@code enc:} keys for the diff; without
     *                   it such keys cannot be compared and always count as different
     */
    public MigrationService(LlmConfigRepository llmConfigRepository,
                            AgentDefinitionRepository agentDefinitionRepository,
                            WorkflowDataRepository workflowDataRepository,
                            ObjectMapper objectMapper,
                            Optional<EncryptionHelper> encryption) {
        this.llmConfigRepository = llmConfigRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.workflowDataRepository = workflowDataRepository;
        this.objectMapper = objectMapper;
        this.encryption = encryption.orElseGet(EncryptionHelper::noEncryption);
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
                LlmConfig compared = existing.map(stored -> withStoredKeyIfSame(stored, incoming)).orElse(incoming);
                pendingFor(EntityType.LLM_CONFIG, incoming.name(), existing.orElse(null), compared, objectMapper)
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
                        agentDefinitionRepository.findByName(incoming.name());
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
        Optional<LlmConfig> incoming = bundledLlmConfig(name);
        if (incoming.isEmpty()) return false;
        llmConfigRepository.save(incoming.get());
        log.info("Applied migration for LLM config '{}'", name);
        return true;
    }

    private boolean applyAgent(String name) {
        Optional<AgentDefinition> incoming = bundledAgent(name);
        if (incoming.isEmpty()) return false;
        agentDefinitionRepository.save(incoming.get());
        log.info("Applied migration for agent '{}'", name);
        return true;
    }

    private boolean applyWorkflow(String id) {
        Optional<WorkflowData> incoming = bundledWorkflow(id);
        if (incoming.isEmpty()) return false;
        workflowDataRepository.save(id, incoming.get());
        log.info("Applied migration for workflow '{}'", id);
        return true;
    }

    // ── Write: apply one field ──────────────────────────────────────────────────

    /**
     * Applies a single top-level field of a pending migration: the bundled value
     * of {@code field} is copied into the stored record, everything else the
     * stored record has stays as it is. This is how an admin takes, say, a new
     * model name from the bundle without losing the API key that only the
     * stored copy carries.
     *
     * <p>A field the bundled version dropped is removed from the stored record.
     * NEW records have nothing stored to merge into and are not applicable.
     *
     * @return true if a stored record, a bundled record and the field were all
     *         found and the merged record was saved
     */
    public boolean applyField(String migrationId, String field) {
        int sep = migrationId.indexOf(':');
        if (sep < 0) throw new IllegalArgumentException("Malformed migration id: " + migrationId);
        EntityType type = EntityType.fromSlug(migrationId.substring(0, sep));
        String name = migrationId.substring(sep + 1);

        boolean applied = switch (type) {
            case LLM_CONFIG -> bundledLlmConfig(name)
                    .flatMap(incoming -> llmConfigRepository.findByName(name)
                            .flatMap(stored -> mergeField(stored, incoming, field, LlmConfig.class, objectMapper)))
                    .map(merged -> { llmConfigRepository.save(merged); return true; })
                    .orElse(false);
            case AGENT -> bundledAgent(name)
                    .flatMap(incoming -> agentDefinitionRepository.findByName(name)
                            .flatMap(stored -> mergeField(stored, incoming, field, AgentDefinition.class, objectMapper)))
                    .map(merged -> { agentDefinitionRepository.save(merged); return true; })
                    .orElse(false);
            case WORKFLOW -> bundledWorkflow(name)
                    .flatMap(incoming -> workflowDataRepository.findById(name)
                            .flatMap(stored -> mergeField(stored, incoming, field, WorkflowData.class, WORKFLOW_MAPPER)))
                    .map(merged -> { workflowDataRepository.save(name, merged); return true; })
                    .orElse(false);
        };
        if (applied) log.info("Applied field '{}' of migration '{}'", field, migrationId);
        return applied;
    }

    /**
     * {@code stored} with only {@code field} taken from {@code incoming}, going
     * through JSON so it works for every entity type alike. Empty when neither
     * side has the field (nothing to apply — e.g. the diff's pseudo rows).
     */
    static <T> Optional<T> mergeField(T stored, T incoming, String field, Class<T> type, ObjectMapper mapper) {
        try {
            ObjectNode storedNode   = mapper.valueToTree(stored);
            ObjectNode incomingNode = mapper.valueToTree(incoming);
            if (!storedNode.has(field) && !incomingNode.has(field)) return Optional.empty();
            JsonNode value = incomingNode.get(field);
            if (value == null) storedNode.remove(field); else storedNode.set(field, value);
            return Optional.of(mapper.treeToValue(storedNode, type));
        } catch (Exception e) {
            log.warn("Could not merge field '{}' into {}: {}", field, type.getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Read: bundled records ───────────────────────────────────────────────────

    private Optional<LlmConfig> bundledLlmConfig(String name) {
        for (Resource resource : scan("classpath:initial-data/llm-configs/*.json")) {
            Optional<LlmConfig> incoming = readEach(resource, LlmConfig.class, objectMapper)
                    .filter(c -> c.name().equals(name));
            if (incoming.isPresent()) return incoming;
        }
        return Optional.empty();
    }

    private Optional<AgentDefinition> bundledAgent(String name) {
        for (Resource resource : scan("classpath:initial-data/agent-definitions/*.json")) {
            Optional<AgentDefinition> incoming = readEach(resource, AgentDefinition.class, objectMapper)
                    .filter(a -> a.name().equals(name));
            if (incoming.isPresent()) return incoming;
        }
        return Optional.empty();
    }

    private Optional<WorkflowData> bundledWorkflow(String id) {
        for (Resource resource : scan("classpath:initial-data/workflows/*.json")) {
            if (!id.equals(fileId(resource))) continue;
            return readEach(resource, WorkflowData.class, WORKFLOW_MAPPER);
        }
        return Optional.empty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * The bundled config carrying the stored key when both keys resolve to the
     * same secret, so the diff does not report a difference that is only the
     * store's encryption or a placeholder the environment fills in.
     */
    private LlmConfig withStoredKeyIfSame(LlmConfig stored, LlmConfig incoming) {
        return sameSecret(stored.apiKey(), incoming.apiKey()) ? incoming.withApiKey(stored.apiKey()) : incoming;
    }

    private boolean sameSecret(String a, String b) {
        return Objects.equals(plaintext(a), plaintext(b));
    }

    /** {@code ${VAR}} expanded and {@code enc:}/{@code plain:} stripped; the value itself when that fails. */
    private String plaintext(String key) {
        if (key == null) return null;
        try {
            // The process environment on purpose: the diff is the installation's, the same for whoever looks.
            return encryption.resolve(EnvVarResolver.system().resolve(key));
        } catch (RuntimeException e) {
            return key;
        }
    }

    /** A secret's value never reaches the diff — only whether and how it is stored. */
    static String maskSecret(JsonNode value) {
        if (value == null) return null;
        String text = value.asText();
        if (EnvVarResolver.containsPlaceholder(text)) return text;
        if (text.startsWith(EncryptionHelper.ENC)) return "(encrypted)";
        return "••••••••";
    }

    /** The seed's identity: its file name without the {@code .json} extension. */
    private static String fileId(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".json")) return null;
        return filename.substring(0, filename.length() - ".json".length());
    }

    private <T> Optional<T> readEach(Resource resource, Class<T> type, ObjectMapper mapper) {
        try {
            return Optional.of(mapper.readerFor(type)
                    .<T>readValue(resource.getInputStream()));
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
            // The version counts saves, not content: a stored agent has one, a bundled one never does.
            for (String field : List.of("createdAt", "updatedAt", "version")) {
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
                boolean secret = "apiKey".equals(field);
                diffs.add(new FieldDiff(field,
                        secret ? maskSecret(oldVal) : render(oldVal),
                        secret ? maskSecret(newVal) : render(newVal)));
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
