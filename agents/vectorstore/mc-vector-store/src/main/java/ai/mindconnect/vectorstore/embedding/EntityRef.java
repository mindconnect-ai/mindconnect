package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.EntityId;

/**
 * What an {@link EmbeddingIndex} entry is about — any entity with text: a
 * stored file, a mail, a calendar event, a todo, a drive item. The index
 * does not know these types; the module that owns an entity maps its typed id
 * onto a ref.
 *
 * <p>Together the four parts identify the entity. For some kinds the id alone
 * is not unique: an IMAP UID only means something inside its folder.
 *
 * <p>The id is a plain string on purpose: this ref crosses every module that
 * owns an entity, and the index cannot depend on all of their typed ids. Where
 * the owning module has an {@link EntityId}, build the ref from it
 * ({@link #of(String, String, EntityId)}) rather than from a loose string.
 *
 * @param type      the kind of entity — {@code file}, {@code mail},
 *                  {@code calendar-event}, {@code todo}, {@code drive-item}
 * @param source    where it comes from — the account or store, e.g.
 *                  {@code email.freemail}, {@code caldav.home}, {@code files}
 * @param container where it lies within the source — a mail folder, a
 *                  calendar, a todo list, a drive folder; empty for none
 * @param id        the entity's id as its owning module spells it
 */
public record EntityRef(String type, String source, String container, String id) {

    public EntityRef {
        type = required(type, "type");
        source = required(source, "source");
        container = container == null ? "" : container;
        id = required(id, "id");
    }

    /** An entity that lies in no container. */
    public static EntityRef of(String type, String source, String id) {
        return new EntityRef(type, source, "", id);
    }

    /** An entity addressed by its module's typed id, lying in no container. */
    public static EntityRef of(String type, String source, EntityId id) {
        return new EntityRef(type, source, "", id.value());
    }

    /** The same kind of entity from the same source, lying in {@code container} under {@code id}. */
    public EntityRef movedTo(String container, String id) {
        return new EntityRef(type, source, container, id);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An entity ref needs a " + name);
        }
        return value;
    }
}
