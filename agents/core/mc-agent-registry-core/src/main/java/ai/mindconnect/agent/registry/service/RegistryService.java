package ai.mindconnect.agent.registry.service;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportReport;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Browsing and importing registries — the one place that knows the order of
 * things.
 *
 * <p>Importing an entry is rarely importing a file. An entry may
 * {@code require} others, and a package is nothing but a list of members, each
 * of which may require more. So an import is a walk: dependencies first, then
 * the entry, every node visited once, cycles broken by the visited set rather
 * than by a stack overflow. What each node's file means is the
 * {@link RegistryInstaller}'s business; this class never parses an entity.
 *
 * <p>Nothing installs itself. A registry is a repository on the internet whose
 * agents carry system prompts and whose packages pull in more of them —
 * reading one is safe, and installing one is a decision a person makes, per
 * entry, with the report in front of them.
 */
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

    /** Depth bound for the dependency walk — a guard, not a design limit. */
    private static final int MAX_DEPTH = 32;

    private final RegistrySourceRepository sources;
    private final RegistryClient client;
    private final Map<RegistryItemType, RegistryInstaller> installers =
            new EnumMap<>(RegistryItemType.class);

    public RegistryService(RegistrySourceRepository sources, RegistryClient client,
                           List<RegistryInstaller> installers) {
        this.sources = sources;
        this.client = client;
        for (RegistryInstaller installer : installers) {
            RegistryInstaller previous = this.installers.put(installer.type(), installer);
            if (previous != null) {
                log.warn("Two installers for {} — keeping {}", installer.type().wireName(),
                        installer.getClass().getSimpleName());
            }
        }
        log.info("Registry service ready: {} source(s), installers for {}",
                sources.findAll().size(), this.installers.keySet());
    }

    // ---------------------------------------------------------------- sources

    /** Every configured registry, enabled or not. */
    public List<RegistrySource> sources() {
        return sources.findAll();
    }

    public Optional<RegistrySource> findSource(RegistrySourceId id) {
        return sources.findById(id);
    }

    /** Adds a registry from the short form an operator types — see {@link RegistrySource#of}. */
    public RegistrySource addSource(String spec) {
        return sources.save(RegistrySource.of(spec));
    }

    public RegistrySource saveSource(RegistrySource source) {
        return sources.save(source);
    }

    public void deleteSource(RegistrySourceId id) {
        sources.deleteById(id);
    }

    // ---------------------------------------------------------------- reading

    /**
     * The source's index.
     *
     * @throws RegistryException when there is no such source, it is disabled,
     *         or the repository cannot be read
     */
    public RegistryIndex index(RegistrySourceId sourceId) {
        return client.fetchIndex(requireSource(sourceId));
    }

    /** The entries of that source matching {@code query} and, when given, {@code type}. */
    public List<RegistryEntry> search(RegistrySourceId sourceId, String query, RegistryItemType type) {
        return index(sourceId).search(query, type);
    }

    /** Drops what is cached for a source, so the next read goes to the repository. */
    public void refresh(RegistrySourceId sourceId) {
        client.refresh(requireSource(sourceId));
    }

    /**
     * What this installation would do with the entry right now: whether an
     * entity of that name is already here, and whether there is an installer
     * for its type at all. For a screen that wants to warn before anybody
     * presses Import.
     */
    public EntryStatus status(RegistryEntry entry) {
        if (entry.type() == RegistryItemType.PACKAGE) {
            return new EntryStatus(true, false);
        }
        RegistryInstaller installer = installers.get(entry.type());
        return installer == null
                ? new EntryStatus(false, false)
                : new EntryStatus(true, installer.exists(entry.name()));
    }

    /**
     * @param installable whether this installation can install this type at all
     * @param present     whether something of that name is already here
     */
    public record EntryStatus(boolean installable, boolean present) {
    }

    /** The types this installation can install — a registry may offer more. */
    public Set<RegistryItemType> installableTypes() {
        return Set.copyOf(installers.keySet());
    }

    // -------------------------------------------------------------- importing

    /**
     * Imports one entry and everything it needs: its {@code requires} first,
     * then the entry itself; a package instead walks its members, each with
     * their own requires.
     *
     * <p>Never throws for a member that fails — the report carries a
     * {@code FAILED} line and the walk goes on, because the members are
     * independent entities in independent stores and stopping at the first
     * failure would leave a half-installed set with no record of what landed.
     *
     * @throws RegistryException when there is no such source or entry, or the
     *         index cannot be read — nothing was installed then
     */
    public ImportReport importEntry(RegistrySourceId sourceId, String entryId, ImportMode mode) {
        RegistrySource source = requireSource(sourceId);
        RegistryIndex index = client.fetchIndex(source);
        RegistryEntry entry = index.find(entryId).orElseThrow(() -> new RegistryException(
                "Registry '" + source.coordinates() + "' has no entry '" + entryId + "'"));

        List<ImportedItem> items = new ArrayList<>();
        install(source, index, entry, mode == null ? ImportMode.SKIP_EXISTING : mode,
                new LinkedHashSet<>(), items, 0);
        ImportReport report = new ImportReport(sourceId.value(), entryId, items);
        log.info("Imported '{}' from {}: {}", entryId, source.coordinates(), report.summary());
        return report;
    }

    /**
     * One node of the walk: dependencies, then this entry. {@code visited}
     * holds the entry ids already handled, so a diamond (two agents requiring
     * the same LLM alias) installs it once and a cycle terminates.
     */
    private void install(RegistrySource source, RegistryIndex index, RegistryEntry entry,
                         ImportMode mode, Set<String> visited, List<ImportedItem> items, int depth) {
        if (!visited.add(entry.id())) {
            return;
        }
        if (depth > MAX_DEPTH) {
            items.add(ImportedItem.failed(entry,
                    "dependency chain deeper than " + MAX_DEPTH + " — stopped here"));
            return;
        }
        for (String required : entry.requires()) {
            Optional<RegistryEntry> dependency = index.find(required);
            if (dependency.isEmpty()) {
                items.add(ImportedItem.unresolved(required,
                        "required by '" + entry.id() + "' but not in this registry's index"));
                continue;
            }
            install(source, index, dependency.get(), mode, visited, items, depth + 1);
        }
        if (entry.type() == RegistryItemType.PACKAGE) {
            installPackage(source, index, entry, mode, visited, items, depth);
            return;
        }
        items.add(installOne(source, entry, mode));
    }

    /** A package: read its manifest, then walk every member as an entry of its own. */
    private void installPackage(RegistrySource source, RegistryIndex index, RegistryEntry entry,
                                ImportMode mode, Set<String> visited, List<ImportedItem> items,
                                int depth) {
        RegistryPackage manifest;
        try {
            manifest = client.fetchPackage(source, entry.path());
        } catch (RuntimeException e) {
            items.add(ImportedItem.failed(entry, "package manifest unreadable: " + e.getMessage()));
            return;
        }
        List<String> unresolved = new ArrayList<>();
        List<RegistryEntry> members = manifest.members(index, unresolved);
        for (String missing : unresolved) {
            items.add(ImportedItem.unresolved(missing,
                    "listed by package '" + entry.name() + "' but not in this registry's index"));
        }
        if (members.isEmpty() && unresolved.isEmpty()) {
            items.add(ImportedItem.skipped(entry, entry.name(), "the package lists nothing"));
            return;
        }
        for (RegistryEntry member : members) {
            install(source, index, member, mode, visited, items, depth + 1);
        }
    }

    /** One entity: fetch its file, hand it to the installer for its type. */
    private ImportedItem installOne(RegistrySource source, RegistryEntry entry, ImportMode mode) {
        RegistryInstaller installer = installers.get(entry.type());
        if (installer == null) {
            return ImportedItem.skipped(entry, entry.name(),
                    "this installation cannot import "
                            + entry.type().label().toLowerCase(Locale.ROOT) + "s");
        }
        try {
            String content = client.fetchText(source, entry.path());
            ImportedItem result = installer.install(entry, content, mode);
            return result != null ? result
                    : ImportedItem.failed(entry, "the installer reported nothing");
        } catch (Exception e) {
            log.warn("Importing '{}' from {} failed", entry.id(), source.coordinates(), e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ImportedItem.failed(entry, message);
        }
    }

    private RegistrySource requireSource(RegistrySourceId id) {
        RegistrySource source = sources.findById(id).orElseThrow(() -> new RegistryException(
                "No registry '" + (id == null ? "null" : id.value()) + "' is configured"));
        if (!source.enabled()) {
            throw new RegistryException("Registry '" + source.name() + "' is disabled");
        }
        return source;
    }
}
