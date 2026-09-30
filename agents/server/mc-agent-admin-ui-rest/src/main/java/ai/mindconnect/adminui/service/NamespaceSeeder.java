package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.service.BundledSeeds.Kind;
import ai.mindconnect.adminui.service.BundledSeeds.Seed;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Installs into the namespace at hand the bundled records ({@code initial-data/**}
 * of every jar, see {@link BundledSeeds}) that the namespace <em>never had</em>.
 *
 * <p>Per seed, in this order:
 * <ol>
 *   <li><b>Whose is it?</b> A seed whose name an extension's manifest lists under
 *       {@code contributes.content} ({@code agents}, {@code skills}, {@code workflows})
 *       is that extension's; every other seed — LLM configs always — is the
 *       host's. An extension's seed is left alone while the extension is off
 *       here (by the namespace, the brand or the operator) and not recorded, so
 *       switching it on later installs it. A name several extensions list is
 *       installed when any of them is on.</li>
 *   <li><b>Did the namespace get it once?</b> The {@link InstalledSeedRepository}
 *       remembers every seed the namespace got; one it remembers is skipped
 *       without looking at the store. That is what keeps a record an admin
 *       deleted from coming back.</li>
 *   <li><b>Is it there?</b> A record of that name (a workflow: that id) is never
 *       touched, whatever it holds — a differing one stays a CHANGED entry on
 *       the Migrations screen, as before. It is recorded as got, so deleting it
 *       later sticks as well.</li>
 *   <li>Otherwise it is <b>saved</b>, and recorded.</li>
 * </ol>
 * The caller has the namespace bound; every repository here is routed by it.
 * One seed that cannot be saved is logged and not recorded — the next run
 * tries it again.
 */
public class NamespaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(NamespaceSeeder.class);

    /** What one run did in one namespace. */
    public record Report(Namespace namespace, List<String> installed, int present, int installedBefore,
                         Map<String, List<String>> off, List<String> failed) {

        public Report {
            installed = List.copyOf(installed);
            off = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(off));
            failed = List.copyOf(failed);
        }

        /** The one line the log gets per namespace. */
        public String summary() {
            StringBuilder line = new StringBuilder("Seeded namespace '").append(namespace.value()).append("': ");
            line.append(installed.isEmpty() ? "nothing new" : installed.size() + " installed " + installed);
            line.append(", ").append(present).append(" already there");
            line.append(", ").append(installedBefore).append(" installed before");
            if (!off.isEmpty()) {
                line.append(", left out while their extension is off: ").append(off);
            }
            if (!failed.isEmpty()) {
                line.append(", failed: ").append(failed);
            }
            return line.toString();
        }
    }

    private final BundledSeeds seeds;
    private final LlmConfigRepository llmConfigs;
    private final AgentDefinitionRepository agents;
    private final SkillRepository skills;
    private final WorkflowDataRepository workflows;
    private final InstalledSeedRepository installedSeeds;
    private final ExtensionService extensions;

    public NamespaceSeeder(BundledSeeds seeds, LlmConfigRepository llmConfigs, AgentDefinitionRepository agents,
                           SkillRepository skills, WorkflowDataRepository workflows,
                           InstalledSeedRepository installedSeeds, ExtensionService extensions) {
        this.seeds = Objects.requireNonNull(seeds, "seeds");
        this.llmConfigs = Objects.requireNonNull(llmConfigs, "llmConfigs");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.installedSeeds = Objects.requireNonNull(installedSeeds, "installedSeeds");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /**
     * Installs what {@code namespace} — bound by the caller — never had, and
     * logs one line about it.
     */
    public Report installMissing(Namespace namespace) {
        Map<String, List<ExtensionService.Status>> claims = claims();
        Set<String> before = installedSeeds.keys();
        Set<String> seen = new HashSet<>();
        List<InstalledSeed> got = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, List<String>> off = new LinkedHashMap<>();
        int present = 0;
        int installedBefore = 0;

        for (Seed<?> seed : seeds.all()) {
            String key = seed.key();
            if (!seen.add(key)) continue; // two jars ship one name: the first one found counts
            List<ExtensionService.Status> owners = claims.getOrDefault(key, List.of());
            String source = owners.stream().filter(ExtensionService.Status::enabled)
                    .map(status -> status.id().value()).findFirst().orElse(null);
            if (!owners.isEmpty() && source == null) {
                for (ExtensionService.Status owner : owners) {
                    off.computeIfAbsent(owner.id().value(), id -> new ArrayList<>()).add(key);
                }
                continue;
            }
            if (source == null) source = InstalledSeed.HOST;
            if (before.contains(key)) {
                installedBefore++;
                continue;
            }
            try {
                if (exists(seed)) {
                    present++;
                } else {
                    save(seed);
                    installed.add(key);
                }
                got.add(InstalledSeed.of(seed.kind().slug(), seed.name(), source));
            } catch (RuntimeException e) {
                log.warn("Could not install {} into namespace '{}': {}", key, namespace.value(), e.getMessage());
                failed.add(key);
            }
        }
        installedSeeds.record(got);
        Report report = new Report(namespace, installed, present, installedBefore, off, failed);
        log.info(report.summary());
        return report;
    }

    /** {@code kind:name} → the extensions whose manifest lists it, with their state in the namespace at hand. */
    private Map<String, List<ExtensionService.Status>> claims() {
        Map<String, List<ExtensionService.Status>> claims = new LinkedHashMap<>();
        for (ExtensionService.Status status : extensions.list()) {
            ExtensionManifest.Content content = status.manifest().contributes().content();
            claim(claims, Kind.AGENT, content.agents(), status);
            claim(claims, Kind.SKILL, content.skills(), status);
            claim(claims, Kind.WORKFLOW, content.workflows(), status);
        }
        return claims;
    }

    private static void claim(Map<String, List<ExtensionService.Status>> claims, Kind kind, List<String> names,
                              ExtensionService.Status status) {
        for (String name : new LinkedHashSet<>(names)) {
            claims.computeIfAbsent(kind.slug() + ":" + name, key -> new ArrayList<>()).add(status);
        }
    }

    private boolean exists(Seed<?> seed) {
        return switch (seed.kind()) {
            case LLM_CONFIG -> llmConfigs.findByName(seed.name()).isPresent();
            case AGENT -> agents.findByName(seed.name()).isPresent();
            case SKILL -> skills.findByName(seed.name()).isPresent();
            case WORKFLOW -> workflows.exists(seed.name());
        };
    }

    private void save(Seed<?> seed) {
        switch (seed.kind()) {
            case LLM_CONFIG -> llmConfigs.save((LlmConfig) seed.record());
            case AGENT -> agents.save((AgentDefinition) seed.record());
            case SKILL -> skills.save((Skill) seed.record());
            case WORKFLOW -> workflows.save(seed.name(), (WorkflowData) seed.record());
        }
    }
}
