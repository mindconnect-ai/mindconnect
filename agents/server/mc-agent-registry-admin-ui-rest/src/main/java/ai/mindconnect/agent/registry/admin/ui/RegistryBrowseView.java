package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What one registry offers: search, filter by kind, and import, with the
 * entries filed under their kind the way the agent list files agents under
 * their group.
 *
 * <p>Each row says what this installation would do with it — already here, or
 * a kind this installation cannot install at all — because the alternative is
 * an operator pressing Import and reading the answer afterwards.
 */
final class RegistryBrowseView {

    static final String STACK_ID = "registry-browse-stack";
    private static final String FORM_ID = "registry-browse-search";
    static final String CATALOG_CSS_CLASS = "registry-catalog";

    /** The rubrics in the order they earn attention: what you chat with, what it runs on, then the rest. */
    static final List<RegistryItemType> GROUP_ORDER = List.of(
            RegistryItemType.AGENT, RegistryItemType.LLM_CONFIG,
            RegistryItemType.WORKFLOW, RegistryItemType.PACKAGE);

    private final RegistrySource source;
    private final RegistryIndex index;
    private final List<RegistryEntry> entries;
    private final String query;
    private final RegistryItemType type;
    /** What the installation would do with an entry — the service answers, the view draws. */
    private final Function<RegistryEntry, RegistryService.EntryStatus> status;

    RegistryBrowseView(RegistrySource source, RegistryIndex index, List<RegistryEntry> entries,
                       String query, RegistryItemType type,
                       Function<RegistryEntry, RegistryService.EntryStatus> status) {
        this.source = source;
        this.index = index;
        this.entries = entries;
        this.query = query;
        this.type = type;
        this.status = status;
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
            return UiStack.of(STACK_ID).child(list);
        }

        // A search or a kind filter asks to see the matches, so their rubrics
        // open; unfiltered, the rubrics are the map and start closed, like the
        // agent list and the tool catalog.
        boolean open = type != null || (query != null && !query.isBlank());
        addGrouped(list, "registry-group-", entries, open, entry -> row(base, entry));
        return UiStack.of(STACK_ID).child(list);
    }

    private UiList.Item row(String base, RegistryEntry entry) {
        RegistryService.EntryStatus entryStatus = status.apply(entry);
        UiList.Item item = UiList.Item.of("entry-" + entry.id(), entry.name())
                .icon(iconFor(entry.type()))
                .description(describe(entry, entryStatus))
                .dispatch("GET", base + "/entry/" + entry.id());
        // Both open the entry, where the warning and the confirmation sit; the
        // label says what the import would do to this installation.
        if (entryStatus.installable() && entryStatus.present()) {
            item.action(UiAction.danger("overwrite-" + entry.id(), "Overwrite")
                    .icon("refresh")
                    .dispatch("GET", base + "/entry/" + entry.id()));
        } else if (entryStatus.installable()) {
            item.action(UiAction.primary("import-" + entry.id(), "Import")
                    .icon("download")
                    .dispatch("GET", base + "/entry/" + entry.id()));
        }
        return item;
    }

    /**
     * Files {@code entries} into {@code list} as one collapsible rubric per kind,
     * in {@link #GROUP_ORDER}; within a kind they keep the order they came in.
     * A kind with no entries gets no rubric.
     */
    static void addGrouped(UiList list, String idPrefix, List<RegistryEntry> entries, boolean open,
                           Function<RegistryEntry, UiList.Item> row) {
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
            list.item(UiList.Item.of(gid, "")
                    .content(groupList)
                    .collapsible(groupTitle(group.getKey()) + "  (" + group.getValue().size() + ")",
                            open, gid + "-sum"));
        }
    }

    static String groupTitle(RegistryItemType type) {
        return switch (type) {
            case AGENT -> "Agents";
            case LLM_CONFIG -> "LLM configs";
            case WORKFLOW -> "Workflows";
            case PACKAGE -> "Packages";
        };
    }

    private String title() {
        String registryName = index.name() != null && !index.name().isBlank()
                ? index.name() : source.name();
        return registryName + " — " + source.coordinates();
    }

    /** Kind, version, author — then what this installation would do with it. */
    private static String describe(RegistryEntry entry, RegistryService.EntryStatus status) {
        StringBuilder text = new StringBuilder(entry.subtitle());
        if (!status.installable()) {
            text.append(" · this installation cannot import this kind");
        } else if (status.present()) {
            text.append(" · already here");
        }
        return text.append(shortDescription(entry)).toString();
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
            case PACKAGE -> "box";
        };
    }
}
