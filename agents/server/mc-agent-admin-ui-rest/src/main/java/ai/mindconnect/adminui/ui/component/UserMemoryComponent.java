package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What agents remember about users across chats, as a table with a view and
 * a delete per entry, and one entry in full — shared by the profile's
 * "Memory" tab (a user's own entries) and the admin's page (everybody's in
 * the namespace). An entry is addressed by user, agent and name; the three
 * travel as one opaque row id so a row action can name them in one path
 * segment ({@link Row}).
 */
public final class UserMemoryComponent {

    /** The profile tab's table. */
    public static final String PROFILE_TABLE_ID = "profile-memory-table";
    /** The admin page's table. */
    public static final String ADMIN_TABLE_ID = "admin-memory-table";
    /** The dialog an entry opens in. */
    public static final String DIALOG_ID = "memory-entry-dialog";

    /** How the memory column names the user's own memory, which every agent shares. */
    static final String SHARED = "Shared by all agents";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private UserMemoryComponent() {}

    /**
     * The entries as a table. {@code api} is the controller the row actions
     * go to ({@code GET <api>/{id}} opens an entry, {@code DELETE} removes
     * it); {@code withUser} adds the user column the admin's page needs.
     */
    public static UiTable table(String id, String api, List<MemoryEntry> entries,
                                Function<AgentId, String> agentName, boolean withUser) {
        UiTable table = UiTable.of(id, "Memory (" + entries.size() + ")").stackOnMobile(true).icon("brain");
        if (withUser) table.column(UiTable.Column.text("user", "User"));
        table.column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("memory", "Memory"))
                .column(UiTable.Column.text("description", "Description"))
                .column(UiTable.Column.text("updated", "Updated"))
                .rowAction(UiAction.secondary("view", "View").icon("eye").dispatch("GET", api + "/{id}"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this memory? The agents will no longer know it.")
                        .dispatch("DELETE", api + "/{id}"));
        for (MemoryEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Row.of(entry).id());
            if (withUser) row.put("user", entry.userId().value());
            row.put("name", entry.name());
            row.put("type", entry.type().wireName());
            row.put("memory", memoryLabel(entry, agentName));
            row.put("description", entry.description());
            row.put("updated", time(entry.updatedAt()));
            table.row(row);
        }
        return table;
    }

    /** What the profile tab says above the table. */
    public static UiText profileHelp() {
        return UiText.of("profile-memory-help",
                "What agents have remembered about you across chats: facts about you, how you like things "
                + "done, ongoing work. Agents with the memory tools write it themselves, and see it in every "
                + "chat — the shared memory all of them, an agent's own memory only that agent. Delete an "
                + "entry that is wrong or that you do not want kept; you can also ask an agent to forget it.");
    }

    /** One entry in full, for the dialog. */
    public static UiNode detail(MemoryEntry entry, Function<AgentId, String> agentName) {
        UiDetail fields = UiDetail.of("memory-entry-fields", entry.name()).icon("brain")
                .field(UiField.text("user", "User", entry.userId().value()))
                .field(UiField.text("memory", "Memory", memoryLabel(entry, agentName)))
                .field(UiField.text("type", "Type", entry.type().wireName()))
                .field(UiField.text("description", "Description", entry.description()))
                .field(UiField.text("created", "Created", time(entry.createdAt())))
                .field(UiField.text("updated", "Updated", time(entry.updatedAt())))
                .field(UiField.text("source", "Written in session",
                        entry.sourceSessionId() == null ? "—" : entry.sourceSessionId().value()));
        return UiStack.of("memory-entry").gap(16)
                .child(fields)
                .child(UiMarkdown.of("memory-entry-content", hardBreaks(entry.content())));
    }

    /**
     * An entry is written like a note — "Why:" and "How to apply:" on lines
     * of their own — and Markdown would run single line breaks together, so
     * every one becomes a hard break; blank lines stay paragraph breaks.
     */
    static String hardBreaks(String text) {
        return text == null ? "" : text.replaceAll("(?<!\n)\n(?!\n)", "  \n");
    }

    static String memoryLabel(MemoryEntry entry, Function<AgentId, String> agentName) {
        if (entry.shared()) return SHARED;
        String name = agentName == null ? null : agentName.apply(entry.agentId());
        return "Agent: " + (name == null || name.isBlank() ? entry.agentId().value() : name);
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    /**
     * An entry's address — user, agent ({@code null} for the shared memory)
     * and name — as one URL-safe token: a user id may be an e-mail address
     * and an agent id carries hyphens, so no separator character is safe,
     * and Base64url of the three lines is.
     */
    public record Row(UserId userId, AgentId agentId, String name) {

        public static Row of(MemoryEntry entry) {
            return new Row(entry.userId(), entry.agentId(), entry.name());
        }

        public String id() {
            String raw = userId.value() + "\n" + (agentId == null ? "" : agentId.value()) + "\n" + name;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        /** Empty for a token that is not one of ours. */
        public static Optional<Row> parse(String id) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8)
                        .split("\n", -1);
                if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) return Optional.empty();
                return Optional.of(new Row(UserId.of(parts[0]),
                        parts[1].isEmpty() ? null : AgentId.of(parts[1]), parts[2]));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
