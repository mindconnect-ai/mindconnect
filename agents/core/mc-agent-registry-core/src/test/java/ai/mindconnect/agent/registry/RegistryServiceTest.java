package ai.mindconnect.agent.registry;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryException;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistryPackage;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.port.out.RegistryClient;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.agent.registry.service.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The import walk: dependencies before the entry, a package as its member
 * list, every entry once, and a failing member that does not take the rest
 * of the import with it.
 */
class RegistryServiceTest {

    private static final RegistrySource SOURCE = RegistrySource.of("acme/registry");

    private final Map<String, RegistryEntry> entries = new LinkedHashMap<>();
    private final Map<String, RegistryPackage> packages = new HashMap<>();
    /** Every removal across all installers, in the order it happened. */
    private final List<String> removalLog = new ArrayList<>();
    private RecordingInstaller agents;
    private RecordingInstaller configs;
    private RegistryService service;

    @BeforeEach
    void setUp() {
        agents = new RecordingInstaller(RegistryItemType.AGENT, removalLog);
        configs = new RecordingInstaller(RegistryItemType.LLM_CONFIG, removalLog);
        service = new RegistryService(new FixedSources(), new FakeClient(),
                List.of(agents, configs));
    }

    // ------------------------------------------------------------------ cases

    @Test
    void installs_what_an_entry_requires_before_the_entry() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT, "default-llm");

        ImportReport report = importEntry("researcher");

        assertThat(report.items()).extracting(ImportedItem::entryId)
                .containsExactly("default-llm", "researcher");
        assertThat(report.ok()).isTrue();
        assertThat(report.summary()).isEqualTo("2 imported");
    }

    @Test
    void a_package_installs_its_members_in_order() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT);
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Research kit", null, "1.0",
                List.of("default-llm", "researcher"), List.of()));

        ImportReport report = importEntry("kit");

        assertThat(report.items()).extracting(ImportedItem::entryId)
                .containsExactly("default-llm", "researcher");
        assertThat(configs.installed).containsExactly("default-llm");
        assertThat(agents.installed).containsExactly("researcher");
    }

    @Test
    void an_entry_reached_twice_is_installed_once() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("one", RegistryItemType.AGENT, "default-llm");
        entry("two", RegistryItemType.AGENT, "default-llm");
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("one", "two"), List.of()));

        ImportReport report = importEntry("kit");

        assertThat(configs.installed).containsExactly("default-llm");
        assertThat(report.items()).extracting(ImportedItem::entryId)
                .containsExactly("default-llm", "one", "two");
    }

    @Test
    void a_cycle_terminates_instead_of_recursing() {
        entry("a", RegistryItemType.AGENT, "b");
        entry("b", RegistryItemType.AGENT, "a");

        ImportReport report = importEntry("a");

        assertThat(agents.installed).containsExactly("b", "a");
        assertThat(report.ok()).isTrue();
    }

    @Test
    void a_required_entry_the_index_does_not_have_fails_only_itself() {
        entry("researcher", RegistryItemType.AGENT, "missing-llm");

        ImportReport report = importEntry("researcher");

        assertThat(report.count(ImportStatus.FAILED)).isEqualTo(1);
        assertThat(agents.installed).containsExactly("researcher");
        ImportedItem unresolved = report.items().get(0);
        assertThat(unresolved.detail()).contains("not in this registry's index");
        // Its kind is exactly what nobody knows — the index has no line for it.
        assertThat(unresolved.type()).isNull();
        assertThat(unresolved).hasToString("Entry 'missing-llm' — failed ("
                + unresolved.detail() + ")");
    }

    @Test
    void a_member_whose_installer_throws_does_not_stop_the_others() {
        entry("bad", RegistryItemType.AGENT);
        entry("good", RegistryItemType.AGENT);
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("bad", "good"), List.of()));
        agents.failOn = "bad";

        ImportReport report = importEntry("kit");

        assertThat(report.ok()).isFalse();
        assertThat(report.count(ImportStatus.FAILED)).isEqualTo(1);
        assertThat(agents.installed).containsExactly("good");
        assertThat(report.summary()).isEqualTo("1 imported, 1 failed");
    }

    @Test
    void a_kind_without_an_installer_is_skipped_with_the_reason() {
        entry("summarize", RegistryItemType.WORKFLOW);

        ImportReport report = importEntry("summarize");

        assertThat(report.count(ImportStatus.SKIPPED)).isEqualTo(1);
        assertThat(report.items().get(0).detail()).contains("cannot import");
        assertThat(service.status(entries.get("summarize")).installable()).isFalse();
    }

    @Test
    void skip_existing_keeps_what_is_here_and_overwrite_replaces_it() {
        entry("researcher", RegistryItemType.AGENT);
        agents.present.add("researcher");

        assertThat(importEntry("researcher").count(ImportStatus.SKIPPED)).isEqualTo(1);
        assertThat(agents.installed).isEmpty();

        ImportReport overwritten = service.importEntry(SOURCE.id(), "researcher", ImportMode.OVERWRITE);
        assertThat(overwritten.count(ImportStatus.UPDATED)).isEqualTo(1);
        assertThat(agents.installed).containsExactly("researcher");
    }

    @Test
    void an_unknown_entry_or_source_is_an_error_rather_than_an_empty_report() {
        assertThatThrownBy(() -> importEntry("nope"))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("no entry 'nope'");
        assertThatThrownBy(() -> service.importEntry(RegistrySourceId.of("other"), "x", null))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("No registry");
    }

    @Test
    void a_disabled_registry_is_not_read() {
        RegistryService disabled = new RegistryService(
                new FixedSources(SOURCE.withEnabled(false)), new FakeClient(), List.of());

        assertThatThrownBy(() -> disabled.index(SOURCE.id()))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void a_packages_contents_are_everything_its_import_would_install_in_order() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT, "default-llm");
        entry("kit", RegistryItemType.PACKAGE);
        RegistryEntry inline = new RegistryEntry("helper", RegistryItemType.AGENT, "helper", null,
                null, "agents/helper.json", List.of(), null, null, List.of("default-llm"));
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("researcher", "gone"), List.of(inline)));

        RegistryService.PackageContents contents = service.packageContents(SOURCE.id(), entries.get("kit"));

        // What a member requires is installed too, so it is shown — once, before it is needed.
        assertThat(contents.members()).extracting(RegistryEntry::id)
                .containsExactly("default-llm", "researcher", "helper");
        assertThat(contents.unresolved()).containsExactly("gone");
        assertThat(configs.installed).isEmpty();
    }

    @Test
    void a_left_out_entry_is_installed_neither_as_a_member_nor_as_a_requirement() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT, "default-llm");
        entry("writer", RegistryItemType.AGENT);
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("default-llm", "researcher", "writer"), List.of()));

        ImportReport report = service.importEntry(SOURCE.id(), "kit", ImportMode.SKIP_EXISTING,
                Set.of("default-llm", "writer"));

        assertThat(agents.installed).containsExactly("researcher");
        assertThat(configs.installed).isEmpty();
        assertThat(report.items()).extracting(ImportedItem::entryId, ImportedItem::status)
                .containsExactly(
                        tuple("default-llm", ImportStatus.SKIPPED),
                        tuple("researcher", ImportStatus.IMPORTED),
                        tuple("writer", ImportStatus.SKIPPED));
    }

    @Test
    void removing_a_package_deletes_the_included_entries_that_are_here_dependents_first() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT, "default-llm");
        entry("writer", RegistryItemType.AGENT, "default-llm");
        entry("reviewer", RegistryItemType.AGENT);
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("researcher", "writer", "reviewer"), List.of()));
        configs.present.add("default-llm");
        agents.present.addAll(List.of("researcher", "writer"));

        ImportReport report = service.removeEntry(SOURCE.id(), "kit", Set.of("writer"));

        // The agents go before the config they run on; writer stays; reviewer was never here.
        assertThat(removalLog).containsExactly("researcher", "default-llm");
        assertThat(agents.present).containsExactly("writer");
        assertThat(report.items()).extracting(ImportedItem::entryId, ImportedItem::status)
                .containsExactly(
                        tuple("reviewer", ImportStatus.SKIPPED),
                        tuple("researcher", ImportStatus.REMOVED),
                        tuple("default-llm", ImportStatus.REMOVED),
                        tuple("writer", ImportStatus.SKIPPED));
    }

    @Test
    void a_member_here_says_what_outside_the_package_uses_it() {
        entry("default-llm", RegistryItemType.LLM_CONFIG);
        entry("researcher", RegistryItemType.AGENT, "default-llm");
        entry("writer", RegistryItemType.AGENT, "default-llm");
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("researcher", "writer"), List.of()));
        configs.present.add("default-llm");
        agents.present.add("researcher");
        agents.references.put("llm-config:default-llm", List.of("researcher", "default-chat"));
        agents.references.put("agent:writer", List.of("planner"));

        RegistryService.PackageContents contents = service.packageContents(SOURCE.id(), entries.get("kit"));

        // researcher is in the package, so only default-chat counts; writer is not
        // here, so what would call it is nothing to protect.
        assertThat(contents.usedBy("default-llm")).containsExactly("Agent 'default-chat'");
        assertThat(contents.usedBy("researcher")).isEmpty();
        assertThat(contents.usedBy("writer")).isEmpty();
    }

    @Test
    void what_a_member_used_from_outside_needs_counts_as_used_too() {
        entry("openai", RegistryItemType.LLM_CONFIG);
        entry("alias", RegistryItemType.LLM_CONFIG, "openai");
        entry("helper", RegistryItemType.AGENT, "alias");
        entry("kit", RegistryItemType.PACKAGE);
        packages.put("packages/kit.json", new RegistryPackage("Kit", null, null,
                List.of("helper"), List.of()));
        configs.present.addAll(List.of("openai", "alias"));
        agents.present.add("helper");
        configs.references.put("llm-config:openai", List.of("alias"));
        agents.references.put("llm-config:alias", List.of("helper", "default-chat"));

        RegistryService.PackageContents contents = service.packageContents(SOURCE.id(), entries.get("kit"));

        // The alias stays for default-chat, so the config it delegates to must stay as well;
        // helper is used by nothing outside and may go.
        assertThat(contents.usedBy("alias")).containsExactly("Agent 'default-chat'");
        assertThat(contents.usedBy("openai")).containsExactly("LLM config 'alias'");
        assertThat(contents.usedBy("helper")).isEmpty();
    }

    @Test
    void only_a_package_can_be_removed() {
        entry("researcher", RegistryItemType.AGENT);
        agents.present.add("researcher");

        assertThatThrownBy(() -> service.removeEntry(SOURCE.id(), "researcher", Set.of()))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("Only a package");
        assertThat(agents.present).containsExactly("researcher");
    }

    @Test
    void only_a_package_has_contents() {
        entry("researcher", RegistryItemType.AGENT);

        assertThatThrownBy(() -> service.packageContents(SOURCE.id(), entries.get("researcher")))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("not a package");
    }

    // ---------------------------------------------------------------- helpers

    private ImportReport importEntry(String entryId) {
        return service.importEntry(SOURCE.id(), entryId, ImportMode.SKIP_EXISTING);
    }

    private void entry(String id, RegistryItemType type, String... requires) {
        String path = (type == RegistryItemType.PACKAGE ? "packages/" : type.wireName() + "s/")
                + id + ".json";
        entries.put(id, new RegistryEntry(id, type, id, null, null, path, List.of(), null, null,
                List.of(requires)));
    }

    /** One configured source, and no other. */
    private static final class FixedSources implements RegistrySourceRepository {
        private final RegistrySource source;

        FixedSources() {
            this(SOURCE);
        }

        FixedSources(RegistrySource source) {
            this.source = source;
        }

        @Override
        public RegistrySource save(RegistrySource source) {
            return source;
        }

        @Override
        public Optional<RegistrySource> findById(RegistrySourceId id) {
            return source.id().equals(id) ? Optional.of(source) : Optional.empty();
        }

        @Override
        public List<RegistrySource> findAll() {
            return List.of(source);
        }

        @Override
        public void deleteById(RegistrySourceId id) {
        }
    }

    /** The index and the files, straight out of the test's maps. */
    private final class FakeClient implements RegistryClient {
        @Override
        public RegistryIndex fetchIndex(RegistrySource source) {
            return new RegistryIndex(1, "Fake", null, new ArrayList<>(entries.values()));
        }

        @Override
        public RegistryPackage fetchPackage(RegistrySource source, String path) {
            RegistryPackage found = packages.get(path);
            if (found == null) throw new RegistryException("no package at " + path);
            return found;
        }

        @Override
        public String fetchText(RegistrySource source, String path) {
            return "{\"path\":\"" + path + "\"}";
        }
    }

    /** An installer that only remembers what it was asked to do. */
    private static final class RecordingInstaller implements RegistryInstaller {
        private final RegistryItemType type;
        final List<String> installed = new ArrayList<>();
        final List<String> present = new ArrayList<>();
        String failOn;
        private final List<String> removalLog;

        RecordingInstaller(RegistryItemType type, List<String> removalLog) {
            this.type = type;
            this.removalLog = removalLog;
        }

        /** "llm-config:default-llm" → the names of this installer's entities that use it. */
        final Map<String, List<String>> references = new HashMap<>();

        @Override
        public List<String> referencesTo(RegistryItemType type, String name) {
            return references.getOrDefault(type.wireName() + ":" + name, List.of());
        }

        @Override
        public ImportedItem remove(RegistryEntry entry) {
            if (!present.remove(entry.name())) {
                return ImportedItem.skipped(entry, entry.name(), "not here");
            }
            removalLog.add(entry.name());
            return ImportedItem.removed(entry, entry.name());
        }

        @Override
        public RegistryItemType type() {
            return type;
        }

        @Override
        public boolean exists(String name) {
            return present.contains(name);
        }

        @Override
        public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) {
            if (entry.id().equals(failOn)) {
                throw new IllegalStateException("this one is broken");
            }
            if (present.contains(entry.name()) && mode == ImportMode.SKIP_EXISTING) {
                return ImportedItem.skipped(entry, entry.name(), "already here");
            }
            boolean update = present.contains(entry.name());
            installed.add(entry.name());
            return update ? ImportedItem.updated(entry, entry.name())
                    : ImportedItem.imported(entry, entry.name());
        }
    }
}
