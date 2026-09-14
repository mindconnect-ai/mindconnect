package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * What this chat may reach for, as the picker behind the composer's
 * "+ &rarr; Tools".
 *
 * <p>Every tool the registry has, grouped by the rubric it is filed under,
 * each set right there: {@link ToolState#OFF off}, {@link ToolState#ON on} —
 * offered to the model up front — or {@link ToolState#SEARCH search}: bound,
 * but left for {@code tool_search} to find when the task needs it. Tool
 * search is not a setting of its own: the chat has it exactly when something
 * is set to Search, and it finds exactly those.
 *
 * <p>A group carries the same switch for all its tools at once. It sets the
 * tools the group holds now — one added to the group later starts off.
 *
 * <p>No Apply: every click is its own request and the dialog stays open,
 * redrawn from what the chat now carries. A group opens when something in it
 * is on or searchable, so a chat's actual reach is visible without a click.
 */
public final class ChatToolsPickerComponent {

    private ChatToolsPickerComponent() {}

    /** Stable id of the picker's body, so a toggle can REPLACE it in place. */
    public static final String BODY_ID = "chat-tools-body";

    /** Where one tool stands for this chat. */
    public enum ToolState {
        /** Not bound: the chat cannot use it. */
        OFF,
        /** Bound and offered to the model from the first step on. */
        ON,
        /** Bound as deferred: in the context only once tool search found it. */
        SEARCH
    }

    /**
     * @param byGroup   every tool the chat may be given, by rubric
     * @param states    the tools this chat binds, and how; a missing name is off
     * @param subgroups tool name &rarr; the finer source it comes from (an MCP
     *                  server, a workflow), for the rows that have one
     */
    public static UiNode node(SessionId sessionId, Map<String, ? extends Collection<String>> byGroup,
                              Map<String, ToolState> states, Map<String, String> subgroups) {
        var body = UiStack.of(BODY_ID).gap(12);

        // Counted over the rows the picker shows: a binding the registry
        // cannot resolve here (Gmail without credentials) stays on the chat
        // but has no row, and a count nobody can find the rows for is noise.
        var shown = new TreeSet<String>();
        byGroup.values().forEach(shown::addAll);
        long on = shown.stream().filter(n -> states.get(n) == ToolState.ON).count();
        long searchable = shown.stream().filter(n -> states.get(n) == ToolState.SEARCH).count();
        var groups = UiList.of("chat-tools-groups",
                "Tools · " + on + " on, " + searchable + " by search, " + shown.size() + " available")
                .icon("wrench");

        new TreeMap<String, Collection<String>>(byGroup).forEach((group, names) -> {
            var sorted = new TreeSet<>(names);
            long reachable = sorted.stream().filter(states::containsKey).count();
            String gid = "chat-tool-group-" + slug(group);
            // Empty label: the collapse summary is the heading, and a label
            // would repeat the group name inside the open section.
            var rows = UiList.of(gid + "-rows", "");
            for (String name : sorted) {
                rows.item(row(sessionId, group, name, subgroups.get(name),
                        states.getOrDefault(name, ToolState.OFF)));
            }
            var groupItem = UiList.Item.of(gid, "")
                    .content(rows)
                    .collapsible(displayGroup(group) + "  ·  " + reachable + " of " + sorted.size(),
                            reachable > 0, gid + "-sum");
            // The group's switch shows a state only when all its tools share it.
            var common = sorted.stream().map(n -> states.getOrDefault(n, ToolState.OFF))
                    .distinct().toList();
            segments(groupItem, "tool-group-" + slug(group), common.size() == 1 ? common.get(0) : null,
                    List.of(ToolState.values()), ChatToolsPickerComponent::label,
                    s -> trigger(on(ChatUiController.class)
                            .setToolGroup(sessionId.value(), group, s, null)));
            groups.item(groupItem);
        });
        body.child(groups);

        if (searchable > 0) {
            body.child(UiText.of("chat-tools-search-hint",
                            "Tool search is on: the chat finds the tools set to Search when it needs them.")
                    .withCssClass("chat-picker-hint"));
        }
        return body;
    }

    /** One tool: what it is called, where it comes from, and the switch. */
    private static UiList.Item row(SessionId sessionId, String group, String name,
                                   String subgroup, ToolState state) {
        var item = UiList.Item.of("chat-tool-" + slug(name), name)
                .icon(groupIcon(group))
                .description(subgroup == null || subgroup.isBlank() ? null : "from " + subgroup);
        segments(item, "tool-" + slug(name), state, List.of(ToolState.values()),
                ChatToolsPickerComponent::label,
                s -> trigger(on(ChatUiController.class).setTool(sessionId.value(), name, s, null)));
        return item;
    }

    private static String label(ToolState state) {
        return switch (state) {
            case OFF -> "Off";
            case ON -> "On";
            case SEARCH -> "Search";
        };
    }

    /**
     * A segmented switch: one button per choice, the current one primary and
     * without a trigger — clicking what is already set has nothing to do.
     * With {@code current} {@code null} every choice is a click away.
     *
     * <p>The ids carry the choice, so the three buttons of a row stay apart
     * when a redraw morphs them in place.
     */
    static <T extends Enum<T>> void segments(UiList.Item item, String idPrefix, T current,
                                             List<T> choices,
                                             java.util.function.Function<T, String> label,
                                             java.util.function.Function<T, UiTrigger> click) {
        for (T choice : choices) {
            String id = idPrefix + "-" + choice.name().toLowerCase();
            item.action(choice == current
                    ? UiAction.primary(id, label.apply(choice))
                    : UiAction.secondary(id, label.apply(choice)).onClick(click.apply(choice)));
        }
    }

    /** Groups are lowercase machine names ({@code files}, {@code web}); capitalize for display. */
    static String displayGroup(String group) {
        if (group == null || group.isBlank()) return "General";
        return Character.toUpperCase(group.charAt(0)) + group.substring(1);
    }

    /** A node id has to survive being a DOM id — a tool name or a group need not. */
    static String slug(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\W+", "-");
    }

    /**
     * A glyph per rubric, so the picker reads as a shelf rather than a word
     * list. An unknown group — a tool source nobody here has heard of — gets
     * the generic toolbox.
     */
    static String groupIcon(String group) {
        return switch (group == null ? "" : group) {
            case "files" -> "folder";
            case "web" -> "globe";
            case "documents" -> "file-text";
            case "knowledge" -> "library-big";
            case "agents" -> "bot";
            case "todo" -> "list-todo";
            case "attachments" -> "paperclip";
            case "code" -> "terminal";
            case "utilities" -> "settings-2";
            case "workflow", "workflows" -> "workflow";
            case "mcp" -> "plug";
            default -> "tool-case";
        };
    }
}
