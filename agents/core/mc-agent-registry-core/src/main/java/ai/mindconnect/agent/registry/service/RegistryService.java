package ai.mindconnect.agent.registry.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
     * What importing a package would install: every entity the import walks
     * to — its members and whatever they require, in install order, each once —
     * and the references that named nothing. The same walk as
     * {@link #importEntry}, without installing, so a screen can show what is
     * already here before anybody chooses what to leave out.
     *
     * @throws RegistryException when there is no such source, the entry is not
     *         a package, or the index or a manifest cannot be read
     */
    public PackageContents packageContents(RegistrySourceId sourceId, RegistryEntry entry) {
        if (entry.type() != RegistryItemType.PACKAGE) {
            throw new RegistryException("'" + entry.id() + "' is a "
                    + entry.type().label().toLowerCase(Locale.ROOT) + ", not a package");
        }
        RegistrySource source = requireSource(sourceId);
        RegistryIndex index = client.fetchIndex(source);
        List<RegistryEntry> members = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        walk(source, index, entry, Set.of(), new LinkedHashSet<>(), 0, new Visitor() {
            @Override
            public void entity(RegistryEntry member) {
                members.add(member);
            }

            @Override
            public void excluded(RegistryEntry member) {
                // Nothing is left out of a preview.
            }

            @Override
            public void report(ImportedItem item) {
                if (item.status() != ImportStatus.FAILED) {
                    return;
                }
                if (item.type() == null) {
                    unresolved.add(item.entryId());
                } else {
                    throw new RegistryException(item.detail());
                }
            }
        });
        return new PackageContents(members, unresolved, usedOutside(members));
    }

    /**
     * For each member that is already here, what still needs it if the package
     * goes — "Agent 'default-chat'" for the LLM config it runs on.
     *
     * <p>A user inside the package does not count by itself: removing both
     * halves leaves nothing dangling. It counts once it is itself used from
     * outside, because then it stays — the alias every agent runs on keeps the
     * config it delegates to. That is followed until nothing changes.
     */
    private Map<String, List<String>> usedOutside(List<RegistryEntry> members) {
        Map<String, RegistryEntry> memberByKey = new LinkedHashMap<>();
        members.forEach(member -> memberByKey.put(key(member.type(), member.name()), member));

        // Who refers to each member that is here, split by whether the user is in the package.
        Map<String, List<String>> outside = new LinkedHashMap<>();
        Map<String, List<RegistryEntry>> insideUsers = new LinkedHashMap<>();
        for (RegistryEntry member : members) {
            RegistryInstaller own = installers.get(member.type());
            if (own == null || !own.exists(member.name())) {
                continue;
            }
            for (RegistryInstaller installer : installers.values()) {
                for (String user : installer.referencesTo(member.type(), member.name())) {
                    RegistryEntry insider = memberByKey.get(key(installer.type(), user));
                    if (insider == null) {
                        outside.computeIfAbsent(member.id(), id -> new ArrayList<>())
                                .add(installer.type().label() + " '" + user + "'");
                    } else if (!insider.id().equals(member.id())) {
                        insideUsers.computeIfAbsent(member.id(), id -> new ArrayList<>()).add(insider);
                    }
                }
            }
        }

        Map<String, List<String>> usedBy = new LinkedHashMap<>(outside);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, List<RegistryEntry>> users : insideUsers.entrySet()) {
                for (RegistryEntry insider : users.getValue()) {
                    String label = insider.type().label() + " '" + insider.name() + "'";
                    List<String> labels = usedBy.get(users.getKey());
                    if (usedBy.containsKey(insider.id()) && (labels == null || !labels.contains(label))) {
                        usedBy.computeIfAbsent(users.getKey(), id -> new ArrayList<>()).add(label);
                        changed = true;
                    }
                }
            }
        }
        return usedBy;
    }

    private static String key(RegistryItemType type, String name) {
        return type.wireName() + ":" + name;
    }

    /**
     * @param members    the entities a package import installs, in order
     * @param unresolved referenced ids the index has no entry for
     * @param usedBy     per member entry id, what outside the package uses the
     *                   entity of that name here; members nothing else uses are absent
     */
    public record PackageContents(List<RegistryEntry> members, List<String> unresolved,
                                  Map<String, List<String>> usedBy) {
        public PackageContents {
            members = List.copyOf(members);
            unresolved = List.copyOf(unresolved);
            usedBy = usedBy == null ? Map.of() : Map.copyOf(usedBy);
        }

        public PackageContents(List<RegistryEntry> members, List<String> unresolved) {
            this(members, unresolved, Map.of());
        }

        /** What outside the package uses that member; empty when nothing does. */
        public List<String> usedBy(String entryId) {
            return usedBy.getOrDefault(entryId, List.of());
        }
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
        return importEntry(sourceId, entryId, mode, Set.of());
    }

    /**
     * Imports the entry as {@link #importEntry(RegistrySourceId, String, ImportMode)}
     * does, leaving out the entries named in {@code excluded}. A left-out entry
     * is not installed at all — not as a package member, and not as what
     * another entry requires — and the report says so; what only it required is
     * not walked to either.
     *
     * @param excluded entry ids not to install; the entry itself cannot be one
     */
    public ImportReport importEntry(RegistrySourceId sourceId, String entryId, ImportMode mode,
                                    Set<String> excluded) {
        RegistrySource source = requireSource(sourceId);
        RegistryIndex index = client.fetchIndex(source);
        RegistryEntry entry = index.find(entryId).orElseThrow(() -> new RegistryException(
                "Registry '" + source.coordinates() + "' has no entry '" + entryId + "'"));
        ImportMode effectiveMode = mode == null ? ImportMode.SKIP_EXISTING : mode;
        Set<String> leftOut = new HashSet<>(excluded == null ? Set.of() : excluded);
        leftOut.remove(entryId);

        List<ImportedItem> items = new ArrayList<>();
        walk(source, index, entry, leftOut, new LinkedHashSet<>(), 0, new Visitor() {
            @Override
            public void entity(RegistryEntry member) {
                items.add(installOne(source, member, effectiveMode));
            }

            @Override
            public void excluded(RegistryEntry member) {
                items.add(ImportedItem.skipped(member, member.name(), "left out of this import"));
            }

            @Override
            public void report(ImportedItem item) {
                items.add(item);
            }
        });
        ImportReport report = new ImportReport(sourceId.value(), entryId, items);
        log.info("Imported '{}' from {}: {}", entryId, source.coordinates(), report.summary());
        return report;
    }

    // --------------------------------------------------------------- removing

    /**
     * Removes a package from this installation: deletes what its import would
     * install — the set its Contents tab shows — except the entries in
     * {@code kept}. Deletion runs in reverse install order, so a workflow goes
     * before the agents it calls and an agent before the LLM config it runs on.
     * An entry that is not here is skipped; one a store refuses is a
     * {@code FAILED} line, and the rest goes on.
     *
     * <p>Nothing checks what else uses a deleted entity. An agent outside the
     * package that names a deleted LLM config stops working — that is what the
     * checkboxes are for.
     *
     * @param kept entry ids to leave in place
     * @throws RegistryException when there is no such source or entry, the entry
     *         is not a package, or the index cannot be read — nothing was removed then
     */
    public ImportReport removeEntry(RegistrySourceId sourceId, String entryId, Set<String> kept) {
        RegistrySource source = requireSource(sourceId);
        RegistryIndex index = client.fetchIndex(source);
        RegistryEntry entry = index.find(entryId).orElseThrow(() -> new RegistryException(
                "Registry '" + source.coordinates() + "' has no entry '" + entryId + "'"));
        if (entry.type() != RegistryItemType.PACKAGE) {
            throw new RegistryException("Only a package can be removed; '" + entryId + "' is a "
                    + entry.type().label().toLowerCase(Locale.ROOT));
        }
        Set<String> leftOut = new HashSet<>(kept == null ? Set.of() : kept);
        leftOut.remove(entryId);

        List<RegistryEntry> toRemove = new ArrayList<>();
        List<ImportedItem> keptLines = new ArrayList<>();
        List<ImportedItem> problems = new ArrayList<>();
        walk(source, index, entry, leftOut, new LinkedHashSet<>(), 0, new Visitor() {
            @Override
            public void entity(RegistryEntry member) {
                toRemove.add(member);
            }

            @Override
            public void excluded(RegistryEntry member) {
                keptLines.add(ImportedItem.skipped(member, member.name(), "kept — left out of the removal"));
            }

            @Override
            public void report(ImportedItem item) {
                // A reference that names nothing has nothing here to delete.
                if (item.status() == ImportStatus.FAILED && item.type() != null) {
                    problems.add(item);
                }
            }
        });

        List<ImportedItem> items = new ArrayList<>(problems);
        for (RegistryEntry member : toRemove.reversed()) {
            items.add(removeOne(source, member));
        }
        items.addAll(keptLines);
        ImportReport report = new ImportReport(sourceId.value(), entryId, items);
        log.info("Removed '{}' of {}: {}", entryId, source.coordinates(), report.summary());
        return report;
    }

    private ImportedItem removeOne(RegistrySource source, RegistryEntry entry) {
        RegistryInstaller installer = installers.get(entry.type());
        if (installer == null) {
            return ImportedItem.skipped(entry, entry.name(), "this installation has no "
                    + entry.type().label().toLowerCase(Locale.ROOT) + "s");
        }
        try {
            ImportedItem result = installer.remove(entry);
            return result != null ? result : ImportedItem.failed(entry, "the installer reported nothing");
        } catch (Exception e) {
            log.warn("Removing '{}' of {} failed", entry.id(), source.coordinates(), e);
            return ImportedItem.failed(entry, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** What the walk finds: an entity, one left out, or a line for the report. */
    private interface Visitor {
        void entity(RegistryEntry entry);

        void excluded(RegistryEntry entry);

        void report(ImportedItem item);
    }

    /**
     * One node of the walk: dependencies, then this entry. {@code visited}
     * holds the entry ids already handled, so a diamond (two agents requiring
     * the same LLM alias) installs it once and a cycle terminates.
     */
    private void walk(RegistrySource source, RegistryIndex index, RegistryEntry entry,
                      Set<String> excluded, Set<String> visited, int depth, Visitor visitor) {
        if (!visited.add(entry.id())) {
            return;
        }
        if (excluded.contains(entry.id())) {
            visitor.excluded(entry);
            return;
        }
        if (depth > MAX_DEPTH) {
            visitor.report(ImportedItem.failed(entry,
                    "dependency chain deeper than " + MAX_DEPTH + " — stopped here"));
            return;
        }
        for (String required : entry.requires()) {
            Optional<RegistryEntry> dependency = index.find(required);
            if (dependency.isEmpty()) {
                visitor.report(ImportedItem.unresolved(required,
                        "required by '" + entry.id() + "' but not in this registry's index"));
                continue;
            }
            walk(source, index, dependency.get(), excluded, visited, depth + 1, visitor);
        }
        if (entry.type() == RegistryItemType.PACKAGE) {
            walkPackage(source, index, entry, excluded, visited, depth, visitor);
            return;
        }
        visitor.entity(entry);
    }

    /** A package: read its manifest, then walk every member as an entry of its own. */
    private void walkPackage(RegistrySource source, RegistryIndex index, RegistryEntry entry,
                             Set<String> excluded, Set<String> visited, int depth, Visitor visitor) {
        RegistryPackage manifest;
        try {
            manifest = client.fetchPackage(source, entry.path());
        } catch (RuntimeException e) {
            visitor.report(ImportedItem.failed(entry, "package manifest unreadable: " + e.getMessage()));
            return;
        }
        List<String> unresolved = new ArrayList<>();
        List<RegistryEntry> members = manifest.members(index, unresolved);
        for (String missing : unresolved) {
            visitor.report(ImportedItem.unresolved(missing,
                    "listed by package '" + entry.name() + "' but not in this registry's index"));
        }
        if (members.isEmpty() && unresolved.isEmpty()) {
            visitor.report(ImportedItem.skipped(entry, entry.name(), "the package lists nothing"));
            return;
        }
        for (RegistryEntry member : members) {
            walk(source, index, member, excluded, visited, depth + 1, visitor);
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
