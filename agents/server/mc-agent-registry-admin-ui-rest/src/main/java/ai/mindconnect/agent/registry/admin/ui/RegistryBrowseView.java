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
import java.util.List;
import java.util.function.Function;

/**
 * What one registry offers: search, filter by kind, and import.
 *
 * <p>Each row says what this installation would do with it — already here, or
 * a kind this installation cannot install at all — because the alternative is
 * an operator pressing Import and reading the answer afterwards.
 */
final class RegistryBrowseView {

    static final String STACK_ID = "registry-browse-stack";
    private static final String FORM_ID = "registry-browse-search";

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

        for (RegistryEntry entry : entries) {
            RegistryService.EntryStatus entryStatus = status.apply(entry);
            UiList.Item item = UiList.Item.of("entry-" + entry.id(), entry.name())
                    .icon(iconFor(entry.type()))
                    .description(describe(entry, entryStatus))
                    .dispatch("GET", base + "/entry/" + entry.id());
            if (entryStatus.installable()) {
                item.action(UiAction.primary("import-" + entry.id(),
                                entryStatus.present() ? "Re-import" : "Import")
                        .icon("download")
                        .dispatch("GET", base + "/entry/" + entry.id()));
            }
            list.item(item);
        }
        return UiStack.of(STACK_ID).child(list);
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
        if (entry.description() != null && !entry.description().isBlank()) {
            String description = entry.description().strip();
            if (description.length() > 180) {
                description = description.substring(0, 180) + "…";
            }
            text.append(" · ").append(description);
        }
        return text.toString();
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
