package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiIcon;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What one registry offers: search, filter by kind, and import, with the
 * entries filed under their kind the way the agent list files agents under
 * their group.
 *
 * <p>Each row says what this installation would do with it — already here, or
 * a kind this installation cannot install at all — because the alternative is
 * an operator pressing Import and reading the answer afterwards.
 *
 * <p>The row's button imports there and then, and the page that comes back is
 * this one again: the report above the list, the row's badge flipped to
 * <em>Already here</em>, the search and the kind filter as they were. Opening
 * an entry is for reading it — its warning, its description, a package's
 * contents — not a station every install has to pass through.
 */
final class RegistryBrowseView {

    static final String STACK_ID = "registry-browse-stack";
    private static final String FORM_ID = "registry-browse-search";
    static final String CATALOG_CSS_CLASS = "registry-catalog";

    /** The rubrics in the order they earn attention: what you chat with, what it runs on, then the rest. */
    static final List<RegistryItemType> GROUP_ORDER = List.of(
            RegistryItemType.AGENT, RegistryItemType.LLM_CONFIG,
            RegistryItemType.WORKFLOW, RegistryItemType.SKILL, RegistryItemType.PACKAGE);

    private final RegistrySource source;
    private final RegistryIndex index;
    private final List<RegistryEntry> entries;
    private final String query;
    private final RegistryItemType type;
    /** What the installation would do with an entry — the service answers, the view draws. */
    private final Function<RegistryEntry, RegistryService.EntryStatus> status;
    /** What an import started from this screen did; null when none has run. */
    private final RegistryOutcome outcome;

    RegistryBrowseView(RegistrySource source, RegistryIndex index, List<RegistryEntry> entries,
                       String query, RegistryItemType type,
                       Function<RegistryEntry, RegistryService.EntryStatus> status) {
        this(source, index, entries, query, type, status, null);
    }

    RegistryBrowseView(RegistrySource source, RegistryIndex index, List<RegistryEntry> entries,
                       String query, RegistryItemType type,
                       Function<RegistryEntry, RegistryService.EntryStatus> status,
                       RegistryOutcome outcome) {
        this.source = source;
        this.index = index;
        this.entries = entries;
        this.query = query;
        this.type = type;
        this.status = status;
        this.outcome = outcome;
    }

    UiNode render() {
        String base = RegistryListView.BASE + "/api/" + source.id().value();

        UiForm search = UiForm.of(FORM_ID, null);
        search.field(UiField.text("q", "", query)
                .asEditable()
                .icon("search")
                .placeholder("Search " + title() + "…")
                .onChange(UiTrigger.api("POST", base + "/search", FORM_ID)));
        search.field(UiField.select("type", "", type == null ? "" : type.wireName(), typeOptions())
                .asEditable()
                .onChange(UiTrigger.api("POST", base + "/search", FORM_ID)));

        UiList list = UiList.of("registry-entries", title()).icon("box");
        list.headerExtra(search);
        list.action(UiAction.secondary("refresh", "Refresh").icon("refresh")
                .dispatch("POST", base + "/refresh"));
        list.action(UiAction.secondary("back", "All registries").icon("back")
                .dispatch("GET", RegistryListView.BASE + "/api"));

        if (entries.isEmpty()) {
            list.item(UiList.Item.of("none", index.entries().isEmpty()
                            ? "This registry is empty"
                            : "Nothing matches")
                    .description(index.entries().isEmpty()
                            ? "Its index lists no entries. "
                                    + (source.repositoryUrl() != null
                                            ? source.repositoryUrl() : source.coordinates())
                            : "Try a shorter search term, or another kind."));
            return stack(list);
        }

        // A search or a kind filter asks to see the matches, so their rubrics
        // open; unfiltered, the rubrics are the map and start closed, like the
        // agent list and the tool catalog. An import opens the kinds it
        // touched, and only those: the rows that changed are the answer to the
        // click, and opening the whole registry around them would lose the
        // place the button was pressed in.
        boolean filtered = type != null || (query != null && !query.isBlank());
        Set<RegistryItemType> touched = touchedByImport();
        addGrouped(list, "registry-group-", entries, kind -> filtered || touched.contains(kind),
                entry -> row(base, entry), (kind, group) -> rubricActions(base, kind, group));
        if (index.unknownEntries() > 0) {
            list.item(UiList.Item.of("unknown-entries", unknownEntriesLine(index.unknownEntries()))
                    .icon("info")
                    .description("The registry lists them for a newer Mindconnect than this one; "
                            + "they are not shown."));
        }
        return stack(list);
    }

    /**
     * The page: what the last import did, then the catalog. The report goes
     * above the list because that is where the eye is after a click, and it
     * stays there until the next one — a toast would be gone before the line
     * that failed has been read.
     */
    private UiNode stack(UiList list) {
        UiStack stack = UiStack.of(STACK_ID);
        if (outcome == null) {
            return stack.child(list);
        }
        if (outcome.error() != null) {
            stack.child(RegistryNotes.note("registry-browse-error", "✗ " + outcome.error()));
        }
        if (outcome.report() != null) {
            stack.child(RegistryNotes.report("registry-browse-report", outcome.what(), outcome.report()));
        }
        return stack.child(list);
    }

    /**
     * The kinds the last import has a line about — what was asked for and, for
     * a package or a whole rubric, everything the walk reached. Empty when no
     * import has run.
     */
    private Set<RegistryItemType> touchedByImport() {
        if (outcome == null) {
            return Set.of();
        }
        Set<RegistryItemType> kinds = EnumSet.noneOf(RegistryItemType.class);
        if (outcome.rubric() != null) {
            kinds.add(outcome.rubric());
        }
        if (outcome.report() != null) {
            outcome.report().items().stream().map(ImportedItem::type)
                    .filter(Objects::nonNull).forEach(kinds::add);
        }
        return kinds;
    }

    static String unknownEntriesLine(int count) {
        return count == 1 ? "1 entry of a kind this version does not know"
                : count + " entries of a kind this version does not know";
    }

    private UiList.Item row(String base, RegistryEntry entry) {
        RegistryService.EntryStatus entryStatus = status.apply(entry);
        UiList.Item item = UiList.Item.of("entry-" + entry.id(), entry.name())
                .labelNode(header(entry, entryStatus))
                .description(describe(entry))
                .dispatch("GET", base + "/entry/" + entry.id());
        if (!entryStatus.installable()) {
            // Nothing to offer: no installer for this kind is wired in. The
            // row still opens, and its page says so.
            return item;
        }
        // The import runs from here. Both buttons send the search form along,
        // so the list that comes back is the one the operator was looking at.
        String install = base + "/install/" + entry.id() + "?mode=";
        if (entryStatus.present()) {
            item.action(UiAction.danger("overwrite-" + entry.id(), "Overwrite")
                    .icon("refresh")
                    .confirm("Replace what is here with '" + entry.name() + "' from "
                            + source.coordinates() + "? Entities of the same name are "
                            + "overwritten, keeping their local ids.")
                    .dispatch("POST", install + "OVERWRITE", FORM_ID));
        } else {
            // No dialog in front of it: this mode overwrites nothing, and the
            // report below says what landed. The entry page keeps the long
            // warning for whoever wants to read the thing before installing it.
            item.action(UiAction.primary("import-" + entry.id(), "Import")
                    .icon("download")
                    .dispatch("POST", install + "SKIP_EXISTING", FORM_ID));
        }
        return item;
    }

    /**
     * A rubric's own button: install everything in it that is not here yet, in
     * one walk, so what several of them require is fetched once.
     *
     * <p>Only for the kinds whose presence this installation can answer. A
     * package's is always "not here" — an installation knows its agents and its
     * workflows, not which packages they came from — so "what is missing" would
     * be "all six of them, and everything in them", which is not a button, it
     * is an accident. Packages stay one considered click at a time.
     *
     * <p>Unlike a row's Import, this one asks first: it is the one button on
     * the screen whose blast radius is not the thing you are pointing at.
     */
    private List<UiAction> rubricActions(String base, RegistryItemType kind, List<RegistryEntry> group) {
        if (kind == RegistryItemType.PACKAGE) {
            return List.of();
        }
        List<RegistryEntry> missing = group.stream().filter(entry -> {
            RegistryService.EntryStatus entryStatus = status.apply(entry);
            return entryStatus.installable() && !entryStatus.present();
        }).toList();
        if (missing.isEmpty()) {
            return List.of();
        }
        // "the 4 entries of Skills", not "the 4 skills": the rubric titles are
        // written for a heading, and lower-casing one turns LLM configs into
        // llm configs.
        String what = missing.size() == 1 ? "the 1 entry" : "the " + missing.size() + " entries";
        return List.of(UiAction.secondary("import-missing-" + kind.wireName(),
                        "Import missing (" + missing.size() + ")")
                .icon("download")
                .confirm("Import " + what + " of " + groupTitle(kind) + " in "
                        + source.coordinates() + " that are not here yet? "
                        + "What is already here is left alone.")
                .dispatch("POST", base + "/install-group/" + kind.wireName(), FORM_ID));
    }

    /**
     * The row's title: the kind's icon, the name, and — for what is already
     * installed — the badge that says so, so the state is read at a glance
     * instead of at the end of the description line.
     *
     * <p>A {@code labelNode} takes the item's own icon slot with it, so the
     * icon rides along in the node.
     */
    private static UiNode header(RegistryEntry entry, RegistryService.EntryStatus entryStatus) {
        UiStack head = UiStack.of("entry-head-" + entry.id())
                .direction(UiStack.Direction.HORIZONTAL)
                .gap(8)
                .<UiStack>withCssClass("registry-entry-head")
                .child(UiIcon.of("entry-icon-" + entry.id(), iconFor(entry.type())))
                .child(UiText.of("entry-name-" + entry.id(), entry.name()));
        if (!entryStatus.installable()) {
            head.child(badge(entry, "unavailable", "Not installable here"));
        } else if (entryStatus.present()) {
            head.child(badge(entry, "present", "Already here"));
        }
        return head;
    }

    private static UiText badge(RegistryEntry entry, String state, String text) {
        return UiText.of("entry-state-" + entry.id(), text)
                .withCssClass("registry-entry-badge registry-entry-badge--" + state);
    }

    /**
     * Files {@code entries} into {@code list} as one collapsible rubric per kind,
     * in {@link #GROUP_ORDER}; within a kind they keep the order they came in.
     * A kind with no entries gets no rubric, {@code open} decides per kind
     * whether its rubric starts unfolded, and {@code rubricActions} gives the
     * rubric's own row its buttons — they sit beside the heading, so they are
     * there whether it is folded or not.
     */
    static void addGrouped(UiList list, String idPrefix, List<RegistryEntry> entries,
                           Predicate<RegistryItemType> open,
                           Function<RegistryEntry, UiList.Item> row,
                           BiFunction<RegistryItemType, List<RegistryEntry>, List<UiAction>> rubricActions) {
        // The admin UI's stylesheet gives grouped catalogs readable rubric
        // headings; the framework's summary style is a quiet detail toggle.
        list.withCssClass(CATALOG_CSS_CLASS);
        Map<RegistryItemType, List<RegistryEntry>> byType = new LinkedHashMap<>();
        for (RegistryItemType kind : GROUP_ORDER) {
            List<RegistryEntry> ofKind = entries.stream().filter(e -> e.type() == kind).toList();
            if (!ofKind.isEmpty()) {
                byType.put(kind, ofKind);
            }
        }
        for (Map.Entry<RegistryItemType, List<RegistryEntry>> group : byType.entrySet()) {
            String gid = idPrefix + group.getKey().wireName();
            UiList groupList = UiList.of(gid + "-list", "");
            group.getValue().forEach(entry -> groupList.item(row.apply(entry)));
            // The empty label keeps the kind from being repeated inside the
            // rubric it already titles.
            UiList.Item rubric = UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(groupTitle(group.getKey()) + "  (" + group.getValue().size() + ")",
                            open.test(group.getKey()), gid + "-sum");
            rubricActions.apply(group.getKey(), group.getValue()).forEach(rubric::action);
            list.item(rubric);
        }
    }

    static String groupTitle(RegistryItemType type) {
        return switch (type) {
            case AGENT -> "Agents";
            case LLM_CONFIG -> "LLM configs";
            case WORKFLOW -> "Workflows";
            case SKILL -> "Skills";
            case PACKAGE -> "Packages";
        };
    }

    private String title() {
        String registryName = index.name() != null && !index.name().isBlank()
                ? index.name() : source.name();
        return registryName + " — " + source.coordinates();
    }

    /** Kind, version, author, description — the state is the row's badge. */
    private static String describe(RegistryEntry entry) {
        return entry.subtitle() + shortDescription(entry);
    }

    /** {@code " · "} and the description cut to a line or two, or nothing without one. */
    static String shortDescription(RegistryEntry entry) {
        if (entry.description() == null || entry.description().isBlank()) {
            return "";
        }
        String description = entry.description().strip();
        if (description.length() > 180) {
            description = description.substring(0, 180) + "…";
        }
        return " · " + description;
    }

    private static List<UiField.Option> typeOptions() {
        List<UiField.Option> options = new ArrayList<>();
        options.add(UiField.Option.of("", "Everything"));
        for (RegistryItemType value : RegistryItemType.values()) {
            options.add(UiField.Option.of(value.wireName(), value.label()));
        }
        return options;
    }

    /** A kind reads faster as a symbol than as a word repeated down a column. */
    static String iconFor(RegistryItemType type) {
        return switch (type) {
            case LLM_CONFIG -> "ai";
            case AGENT -> "bot";
            case WORKFLOW -> "branch";
            case SKILL -> "graduation-cap";
            case PACKAGE -> "box";
        };
    }
}
