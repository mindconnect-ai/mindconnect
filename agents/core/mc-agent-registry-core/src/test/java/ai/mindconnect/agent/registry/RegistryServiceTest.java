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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The import walk: dependencies before the entry, a package as its member
 * list, every entry once, and a failing member that does not take the rest
 * of the import with it.
 */
class RegistryServiceTest {

    private static final RegistrySource SOURCE = RegistrySource.of("acme/registry");

    private final Map<String, RegistryEntry> entries = new LinkedHashMap<>();
    private final Map<String, RegistryPackage> packages = new HashMap<>();
    private RecordingInstaller agents;
    private RecordingInstaller configs;
    private RegistryService service;

    @BeforeEach
    void setUp() {
        agents = new RecordingInstaller(RegistryItemType.AGENT);
        configs = new RecordingInstaller(RegistryItemType.LLM_CONFIG);
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

        RecordingInstaller(RegistryItemType type) {
            this.type = type;
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
