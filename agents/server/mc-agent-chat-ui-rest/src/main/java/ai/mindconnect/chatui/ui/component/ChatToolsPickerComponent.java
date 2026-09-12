package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * What this chat may reach for, as the picker behind the composer's
 * "+ &rarr; Tools".
 *
 * <p>One row per tool, grouped by the rubric the registry files it under, and
 * one button per row that turns it on or off right there. No Apply: a chat's
 * tool list is not a form you fill in, it is a switch you flick while you are
 * typing — so every click is its own request and the dialog stays open,
 * redrawn from what the chat now carries. The settings dialog used to do this
 * job with a native {@code <select multiple>}, which is the one control
 * nobody ever operated correctly; it keeps the agent, the model and the
 * prompt, which genuinely are a form.
 *
 * <p>A group opens when something in it is on, so a chat's actual reach is
 * visible without a single click; the rest stay shut, because a picker of
 * sixty rows is not a picker.
 */
public final class ChatToolsPickerComponent {

    private ChatToolsPickerComponent() {}

    /** Stable id of the picker's body, so a toggle can REPLACE it in place. */
    public static final String BODY_ID = "chat-tools-body";

    /**
     * @param byGroup   every tool the registry can hand out, by rubric
     * @param active    the names this chat offers up front
     * @param subgroups tool name &rarr; the finer source it comes from (an MCP
     *                  server, a workflow), for the rows that have one
     * @param search    whether the chat may find the remaining tools itself
     */
    public static UiNode node(SessionId sessionId, Map<String, ? extends Collection<String>> byGroup,
                              Set<String> active, Map<String, String> subgroups, boolean search) {
        var body = UiStack.of(BODY_ID).gap(12);
        body.child(searchList(sessionId, search));

        int offered = active.size();
        int known = byGroup.values().stream().mapToInt(Collection::size).sum();
        var groups = UiList.of("chat-tools-groups",
                "Tools · " + offered + " on, " + known + " available").icon("wrench");

        new TreeMap<String, Collection<String>>(byGroup).forEach((group, names) -> {
            var sorted = new TreeSet<>(names);
            long onHere = sorted.stream().filter(active::contains).count();
            String gid = "chat-tool-group-" + slug(group);
            // Empty label: the collapse summary is the heading, and a label
            // would repeat the group name inside the open section.
            var rows = UiList.of(gid + "-rows", "");
            for (String name : sorted) {
                rows.item(row(sessionId, group, name, subgroups.get(name), active.contains(name)));
            }
            groups.item(UiList.Item.of(gid, "")
                    .content(rows)
                    .collapsible(displayGroup(group) + "  ·  " + onHere + " of " + sorted.size(),
                            onHere > 0, gid + "-sum"));
        });
        body.child(groups);
        return body;
    }

    /** The chat's tool-search setting — the one switch here that is not a tool. */
    private static UiNode searchList(SessionId sessionId, boolean search) {
        var list = UiList.of("chat-tools-search", "");
        list.item(UiList.Item.of("chat-tool-search", "Tool search")
                .icon("search")
                .description("Lets the chat look up the tools that are off instead of "
                        + "carrying every definition in its context")
                .action(toggle("tool-search", search,
                        trigger(on(ChatUiController.class)
                                .toggleToolSearch(sessionId.value(), !search, null)))));
        return list;
    }

    /** One tool: what it is called, where it comes from, and the switch. */
    private static UiList.Item row(SessionId sessionId, String group, String name,
                                   String subgroup, boolean active) {
        return UiList.Item.of("chat-tool-" + slug(name), name)
                .icon(groupIcon(group))
                .description(subgroup == null || subgroup.isBlank() ? null : "from " + subgroup)
                .action(toggle("tool-" + slug(name), active,
                        trigger(on(ChatUiController.class)
                                .toggleTool(sessionId.value(), name, !active, null))));
    }

    /**
     * The switch: a tinted "On" that turns the thing off, or a quiet "Add"
     * that turns it on. Both are the same control in the same place, so a row
     * never moves under the cursor between two clicks.
     *
     * <p>The state rides on the action's {@code style}, not on a css class:
     * the framework's action renderer drops {@code cssClass}, so PRIMARY vs
     * SECONDARY is the only per-row marker that reaches the DOM. The
     * stylesheet tones the primary back down to a pill — forty filled buttons
     * in a column are a wall, not a list.
     */
    static UiAction toggle(String id, boolean active, UiTrigger click) {
        return active
                ? UiAction.primary(id, "On").icon("check").onClick(click)
                : UiAction.secondary(id, "Add").icon("plus").onClick(click);
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
