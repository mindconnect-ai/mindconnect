package ai.mindconnect.agent.registry.domain;

import java.util.List;

/**
 * What one import did, member by member.
 *
 * <p>An import of a package touches several stores and can half-succeed:
 * three agents installed, one workflow skipped because a workflow of that id
 * was already there, one LLM config failed because its JSON does not parse.
 * Nothing is rolled back — an installed agent is useful even if its sibling
 * failed, and a rollback across four independent stores would be a lie
 * anyway. So the report is the honest record, and the screen shows every
 * line.
 *
 * @param sourceId the registry this came from
 * @param entryId  the entry that was asked for — a package's own id, when a
 *                 package was imported
 * @param items    what happened, in install order
 */
public record ImportReport(
        String sourceId,
        String entryId,
        List<ImportedItem> items
) {

    public ImportReport {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether every member came through. */
    public boolean ok() {
        return items.stream().map(ImportedItem::status).allMatch(ImportStatus::ok);
    }

    /** How many members ended in that status. */
    public long count(ImportStatus status) {
        return items.stream().filter(item -> item.status() == status).count();
    }

    /** "2 imported, 1 skipped, 1 failed" — never empty. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        for (ImportStatus status : ImportStatus.values()) {
            long count = count(status);
            if (count == 0) continue;
            if (!text.isEmpty()) text.append(", ");
            text.append(count).append(' ').append(status.name().toLowerCase(java.util.Locale.ROOT));
        }
        return text.isEmpty() ? "nothing to import" : text.toString();
    }
}
