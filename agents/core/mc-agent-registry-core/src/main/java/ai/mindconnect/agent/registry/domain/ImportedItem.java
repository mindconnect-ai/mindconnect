package ai.mindconnect.agent.registry.domain;

import java.util.Locale;

/**
 * One line of an {@link ImportReport}: what was installed, under which name,
 * and how it went.
 *
 * @param entryId the registry entry this came from
 * @param type    what it was — {@code null} for a reference that named nothing,
 *                where the kind is exactly what is not known
 * @param name    the name it was installed under — an installer may have to
 *                pick a different one than the entry advertised, and the
 *                person needs to read the one that is now in their list
 * @param status  how it went
 * @param detail  why, when that is not obvious; {@code null} otherwise
 */
public record ImportedItem(
        String entryId,
        RegistryItemType type,
        String name,
        ImportStatus status,
        String detail
) {

    public static ImportedItem imported(RegistryEntry entry, String name) {
        return new ImportedItem(entry.id(), entry.type(), name, ImportStatus.IMPORTED, null);
    }

    public static ImportedItem updated(RegistryEntry entry, String name) {
        return new ImportedItem(entry.id(), entry.type(), name, ImportStatus.UPDATED, null);
    }

    public static ImportedItem removed(RegistryEntry entry, String name) {
        return new ImportedItem(entry.id(), entry.type(), name, ImportStatus.REMOVED, null);
    }

    public static ImportedItem skipped(RegistryEntry entry, String name, String detail) {
        return new ImportedItem(entry.id(), entry.type(), name, ImportStatus.SKIPPED, detail);
    }

    public static ImportedItem failed(RegistryEntry entry, String detail) {
        return new ImportedItem(entry.id(), entry.type(), entry.name(), ImportStatus.FAILED, detail);
    }

    /**
     * A reference in an index or a package manifest that named no entry — the
     * kind is unknown, which is the point.
     */
    public static ImportedItem unresolved(String entryId, String detail) {
        return new ImportedItem(entryId, null, entryId, ImportStatus.FAILED, detail);
    }

    /** "Agent 'web-researcher' — imported", plus the detail when there is one. */
    @Override
    public String toString() {
        String kind = type == null ? "Entry" : type.label();
        String line = kind + " '" + name + "' — " + status.name().toLowerCase(Locale.ROOT);
        return detail == null ? line : line + " (" + detail + ")";
    }
}
